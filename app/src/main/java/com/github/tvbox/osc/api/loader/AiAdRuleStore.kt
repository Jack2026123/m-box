package com.github.tvbox.osc.api.loader

import android.text.TextUtils
import com.github.catvod.utils.Prefers
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// 3.68 C：AI 去广告按站规则库（只存 AI 学习出的规则，不碰静态表与订阅表）。
//
// 存储：Prefers 键 ai_ad_rules，一份 JSON，按 host 分桶：
//   {"example.com": {"domains": [...], "selectors": [...], "triggers": [...]}}
//   domains   —— 命中即在网络层按广告拦截的第三方域名（精确 + 子域后缀匹配）
//   selectors —— DOM 层清理的选择器（display:none + remove + MutationObserver 持续清扫）
//   triggers  —— 点按才触发的广告：域名（window.open 拦截）或 selector:xxx（点击劫持清理）
// 开关态另存一键 ai_ad_enabled：{"example.com": true}，默认关，换站按新站 host 重新读态。
// 放行态另存一键 ai_ad_passthrough：同款 {"host": bool}，默认关（3.70，浏览器顶栏「放行」开关用）。
// 单站域名 / 选择器各上限 50 条；triggers 上限同为 50 条。
// 本文件不扩 AdDomains、不碰 AdBlocker.AD_HOSTS（全局单例且订阅 ads 也在用）。
// 写入必过校验：不含本站主域、不含媒体后缀；选择器禁命中 video 与播放器容器。
// BrowserActivity 是 Java 调用方：全部公开方法均加 @JvmStatic。
object AiAdRuleStore {

    const val KEY_RULES = "ai_ad_rules"
    const val KEY_ENABLED = "ai_ad_enabled"
    const val KEY_PASSTHROUGH = "ai_ad_passthrough"
    const val MAX_PER_KIND = 50

    // 规则 JS 的一次守卫名（自有，不复用 CLEAN_JS 的 __museWebClean）。
    const val GUARD_SITE = "__aiAdGuard"
    const val GUARD_TRIGGER = "__aiAdOpenGuard"

    private val MEDIA_SUFFIXES = arrayOf(
        ".m3u8", ".mp4", ".ts", ".m4s", ".mpd", ".flv", ".mov", ".webm",
        ".mp3", ".m4a", ".aac", ".key"
    )

    // 播放器白名单：选择器含以下任一子串即禁写（宁漏勿杀，防把播放器容器当广告删掉）。
    private val PLAYER_SELECTOR_KEYWORDS = arrayOf(
        "video", "player", "dplayer", "xgplayer", "artplayer", "ckplayer",
        "aliplayer", "plyr", "video-js", "vjs-", "jwplayer", "prism-player"
    )

    // 太宽的选择器禁写：删整页或整 body 的规则一律不要。
    private val TOO_BROAD_SELECTORS = arrayOf("*", "body", "html", "body *", "html *", "div", "iframe")

    data class AiAdRule(
        val host: String,
        val domains: List<String>,
        val selectors: List<String>,
        val triggers: List<String>
    ) {
        fun isEmpty(): Boolean = domains.isEmpty() && selectors.isEmpty() && triggers.isEmpty()
    }

    // 从 URL 取小写 host（去端口，与 WebSourceStore.host 同口径，不去 www 前缀，
    // 与 BrowserActivity.hostOf 的 Uri.getHost 结果保持一致，规则桶键才对得上）。
    @JvmStatic
    fun hostOf(url: String?): String {
        var text = url?.trim() ?: ""
        if (text.isEmpty()) return ""
        val start = text.indexOf("://")
        if (start >= 0) text = text.substring(start + 3)
        val slash = text.indexOf('/')
        if (slash >= 0) text = text.substring(0, slash)
        val colon = text.indexOf(':')
        if (colon >= 0) text = text.substring(0, colon)
        return text.lowercase(Locale.ROOT)
    }

    @JvmStatic
    fun normalizeHost(host: String?): String = hostOf(host)

    // ---------------- 读 ----------------

    @JvmStatic
    fun getRule(host: String?): AiAdRule {
        val h = normalizeHost(host)
        if (h.isEmpty()) return AiAdRule("", emptyList(), emptyList(), emptyList())
        val root = loadRoot()
        val obj = root.optJSONObject(h) ?: return AiAdRule(h, emptyList(), emptyList(), emptyList())
        return AiAdRule(h, jsonStrings(obj.optJSONArray("domains")), jsonStrings(obj.optJSONArray("selectors")), jsonStrings(obj.optJSONArray("triggers")))
    }

    @JvmStatic
    fun getDomains(host: String?): List<String> = getRule(host).domains

    @JvmStatic
    fun getSelectors(host: String?): List<String> = getRule(host).selectors

    @JvmStatic
    fun getTriggers(host: String?): List<String> = getRule(host).triggers

    @JvmStatic
    fun hasRules(host: String?): Boolean = !getRule(host).isEmpty()

    @JvmStatic
    fun hosts(): List<String> {
        val out = ArrayList<String>()
        val root = loadRoot()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next() ?: continue
            if (!root.isNull(k)) out.add(k)
        }
        return out
    }

    // ---------------- 写（落盘前必过校验） ----------------

    // 整站保存：三类各自过校验、各自截到 50 条；三类全空且本站已有桶时删桶（不留空桶）。
    // 返回 true 表示已落盘（即使个别条目被校验丢掉）；host 为空返回 false。
    @JvmStatic
    fun saveRule(host: String?, domains: List<String>?, selectors: List<String>?, triggers: List<String>?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        val d = sanitizeDomains(h, domains)
        val s = sanitizeSelectors(selectors)
        val t = sanitizeTriggers(h, triggers)
        val root = loadRoot()
        if (d.isEmpty() && s.isEmpty() && t.isEmpty()) {
            root.remove(h)
        } else {
            val obj = JSONObject()
            obj.put("domains", JSONArray(d))
            obj.put("selectors", JSONArray(s))
            obj.put("triggers", JSONArray(t))
            root.put(h, obj)
        }
        saveRoot(root)
        return true
    }

    @JvmStatic
    fun addDomain(host: String?, domain: String?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        val clean = sanitizeDomains(h, listOf(domain ?: ""))
        if (clean.isEmpty()) return false
        val rule = getRule(h)
        if (rule.domains.contains(clean[0])) return true
        if (rule.domains.size >= MAX_PER_KIND) return false
        val next = ArrayList(rule.domains)
        next.add(clean[0])
        return saveRule(h, next, rule.selectors, rule.triggers)
    }

    @JvmStatic
    fun addSelector(host: String?, selector: String?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        val clean = sanitizeSelectors(listOf(selector ?: ""))
        if (clean.isEmpty()) return false
        val rule = getRule(h)
        if (rule.selectors.contains(clean[0])) return true
        if (rule.selectors.size >= MAX_PER_KIND) return false
        val next = ArrayList(rule.selectors)
        next.add(clean[0])
        return saveRule(h, rule.domains, next, rule.triggers)
    }

    @JvmStatic
    fun addTrigger(host: String?, trigger: String?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        val clean = sanitizeTriggers(h, listOf(trigger ?: ""))
        if (clean.isEmpty()) return false
        val rule = getRule(h)
        if (rule.triggers.contains(clean[0])) return true
        if (rule.triggers.size >= MAX_PER_KIND) return false
        val next = ArrayList(rule.triggers)
        next.add(clean[0])
        return saveRule(h, rule.domains, rule.selectors, next)
    }

    // ---------------- 删 ----------------

    @JvmStatic
    fun removeDomain(host: String?, domain: String?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        val rule = getRule(h)
        val target = normalizeHost(domain)
        if (!rule.domains.contains(target)) return false
        val next = ArrayList(rule.domains)
        next.remove(target)
        return saveRule(h, next, rule.selectors, rule.triggers)
    }

    @JvmStatic
    fun removeSelector(host: String?, selector: String?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        val rule = getRule(h)
        val target = selector?.trim() ?: ""
        if (!rule.selectors.contains(target)) return false
        val next = ArrayList(rule.selectors)
        next.remove(target)
        return saveRule(h, rule.domains, next, rule.triggers)
    }

    @JvmStatic
    fun removeTrigger(host: String?, trigger: String?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        val rule = getRule(h)
        val target = trigger?.trim() ?: ""
        if (!rule.triggers.contains(target)) return false
        val next = ArrayList(rule.triggers)
        next.remove(target)
        return saveRule(h, rule.domains, rule.selectors, next)
    }

    // 清空本站整桶规则（开关态不动，规则留着与开关无关——开关只决定用不用）。
    @JvmStatic
    fun clearHost(host: String?) {
        val h = normalizeHost(host)
        if (h.isEmpty()) return
        val root = loadRoot()
        root.remove(h)
        saveRoot(root)
    }

    @JvmStatic
    fun clearAll() {
        Prefers.put(KEY_RULES, "")
    }

    // ---------------- 开关态（默认关，换站按新站 host 重新读） ----------------

    @JvmStatic
    fun isEnabled(host: String?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        return try {
            JSONObject(Prefers.getString(KEY_ENABLED, "")).optBoolean(h, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    @JvmStatic
    fun setEnabled(host: String?, enabled: Boolean) {
        val h = normalizeHost(host)
        if (h.isEmpty()) return
        val obj = try {
            JSONObject(Prefers.getString(KEY_ENABLED, ""))
        } catch (ignored: Throwable) {
            JSONObject()
        }
        if (enabled) obj.put(h, true) else obj.remove(h)
        Prefers.put(KEY_ENABLED, obj.toString())
    }

    // ---------------- 放行态（3.70，默认关，换站按新站 host 重新读） ----------------

    // 放行开着时本站当普通浏览器用：主浏览器静态拦截（AdBlocker.isAd 与 AI 规则域名档）、
    // FILTER_JS 与 CLEAN_JS / buildSiteJs / buildTriggerJs 注入全跳过；记录开关的已有规则
    // 也被短路盖住（规则本身不删，放行关掉即恢复生效）。与 isEnabled 同款存储口径。
    @JvmStatic
    fun isPassthrough(host: String?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        return try {
            JSONObject(Prefers.getString(KEY_PASSTHROUGH, "")).optBoolean(h, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    @JvmStatic
    fun setPassthrough(host: String?, enabled: Boolean) {
        val h = normalizeHost(host)
        if (h.isEmpty()) return
        val obj = try {
            JSONObject(Prefers.getString(KEY_PASSTHROUGH, ""))
        } catch (ignored: Throwable) {
            JSONObject()
        }
        if (enabled) obj.put(h, true) else obj.remove(h)
        Prefers.put(KEY_PASSTHROUGH, obj.toString())
    }

    // ---------------- isAd() 查询（BrowserActivity.isAd 里用） ----------------

    // 请求 URL 的 host 是否命中本站 AI 规则域名（精确 + 子域后缀）。
    // 调用点约束由 BrowserActivity 保证：放在 AdBlocker.isAd 之后、isSelfHost 豁免之后，
    // 且调用前先过 isMediaRequest 豁免；只在本站开关开着时才调。
    @JvmStatic
    fun isAdDomain(siteHost: String?, url: String?): Boolean {
        if (TextUtils.isEmpty(url)) return false
        return matchesDomain(siteHost, hostOf(url))
    }

    @JvmStatic
    fun matchesDomain(siteHost: String?, requestHost: String?): Boolean {
        val h = normalizeHost(requestHost)
        if (h.isEmpty()) return false
        for (d in getDomains(siteHost)) {
            if (h == d || h.endsWith("." + d)) return true
        }
        return false
    }

    // ---------------- 校验（写规则前用，Learner 与 saveRule 共用） ----------------

    // 媒体 URL 判定（路径后缀口径与 BrowserActivity.isMediaRequest 一致）。
    @JvmStatic
    fun isMediaUrl(url: String?): Boolean {
        if (TextUtils.isEmpty(url)) return false
        var p = url!!.lowercase(Locale.ROOT)
        val q = p.indexOf('?')
        if (q >= 0) p = p.substring(0, q)
        val hash = p.indexOf('#')
        if (hash >= 0) p = p.substring(0, hash)
        for (s in MEDIA_SUFFIXES) if (p.endsWith(s)) return true
        return false
    }

    @JvmStatic
    fun isPlayerSelector(selector: String?): Boolean {
        val s = selector?.trim()?.lowercase(Locale.ROOT) ?: ""
        if (s.isEmpty()) return true
        for (b in TOO_BROAD_SELECTORS) if (s == b) return true
        if (s == "video" || s.startsWith("video ") || s.startsWith("video.") || s.startsWith("video#") || s.startsWith("video[")) return true
        for (k in PLAYER_SELECTOR_KEYWORDS) if (s.contains(k)) return true
        return false
    }

    // 域名清洗：抽 host、小写、去本站主域与同父域、去媒体后缀、去重、截 50。
    // 条目带完整 URL 时只取 host 部分；裸域名直接用。
    @JvmStatic
    fun sanitizeDomains(siteHost: String?, domains: List<String>?): List<String> {
        val site = normalizeHost(siteHost)
        val out = ArrayList<String>()
        if (domains == null) return out
        for (raw in domains) {
            var d = hostOf(raw)
            if (d.isEmpty()) continue
            if (d.startsWith("www.")) d = d.substring(4)
            if (d == site) continue
            if (site.isNotEmpty() && (d.endsWith("." + site) || site.endsWith("." + d))) continue
            var media = false
            for (s in MEDIA_SUFFIXES) if (d.endsWith(s)) media = true
            if (media) continue
            if (!out.contains(d)) out.add(d)
            if (out.size >= MAX_PER_KIND) break
        }
        return out
    }

    @JvmStatic
    fun sanitizeSelectors(selectors: List<String>?): List<String> {
        val out = ArrayList<String>()
        if (selectors == null) return out
        for (raw in selectors) {
            val s = raw?.trim() ?: ""
            if (s.isEmpty() || s.length > 200) continue
            if (isPlayerSelector(s)) continue
            if (!out.contains(s)) out.add(s)
            if (out.size >= MAX_PER_KIND) break
        }
        return out
    }

    // triggers 条目两种形态：裸域名（与 domains 同规则清洗）与 selector:xxx（CSS 部分过选择器校验）。
    @JvmStatic
    fun sanitizeTriggers(siteHost: String?, triggers: List<String>?): List<String> {
        val out = ArrayList<String>()
        if (triggers == null) return out
        for (raw in triggers) {
            val t = raw?.trim() ?: ""
            if (t.isEmpty()) continue
            if (t.startsWith("selector:")) {
                val sel = t.substring("selector:".length).trim()
                if (sel.isEmpty() || isPlayerSelector(sel)) continue
                val norm = "selector:" + sel
                if (!out.contains(norm)) out.add(norm)
            } else {
                val clean = sanitizeDomains(siteHost, listOf(t))
                if (clean.isNotEmpty() && !out.contains(clean[0])) out.add(clean[0])
            }
            if (out.size >= MAX_PER_KIND) break
        }
        return out
    }

    // ---------------- 生成本站规则 JS ----------------

    // 完整规则 JS（onPageFinished 里 CLEAN_JS 之后注，主框才跑）。
    // 选择器 display:none + remove + MutationObserver 持续清扫；域名层清 iframe/script/img/a；
    // window.open 拦截 + 点击劫持清理。无规则返回空串。
    @JvmStatic
    fun buildSiteJs(host: String?): String {
        val rule = getRule(host)
        if (rule.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("(function(){if(window.").append(GUARD_SITE).append(")return;window.").append(GUARD_SITE).append("=true;")
        sb.append("var D=").append(jsonArray(rule.domains)).append(";")
        sb.append("var S=").append(jsonArray(rule.selectors)).append(";")
        sb.append("var T=").append(jsonArray(rule.triggers)).append(";")
        sb.append("function hostOf(u){try{return new URL(u,location.href).hostname.toLowerCase();}catch(e){return '';}}")
        sb.append("function hit(list,h){if(!h)return false;for(var i=0;i<list.length;i++){if(h===list[i]||h.endsWith('.'+list[i]))return true;}return false;}")
        sb.append("function trigHit(u){var h=hostOf(u);for(var i=0;i<T.length;i++){var t=T[i];if(t.indexOf('selector:')===0)continue;if(h===t||h.endsWith('.'+t))return true;}return false;}")
        sb.append("function hide(el){try{el.style.display='none';el.remove();}catch(e){}}")
        sb.append("function clean(root){try{")
        sb.append("for(var i=0;i<S.length;i++){try{var els=root.querySelectorAll(S[i]);for(var j=0;j<els.length;j++)hide(els[j]);}catch(e){}}")
        sb.append("var nodes=root.querySelectorAll('iframe[src],script[src],img[src]');for(var k=0;k<nodes.length;k++){var s=nodes[k].getAttribute('src')||'';if(s&&hit(D,hostOf(s)))hide(nodes[k]);}")
        sb.append("var links=root.querySelectorAll('a[href]');for(var m=0;m<links.length;m++){var href=links[m].getAttribute('href')||'';if(href&&(hit(D,hostOf(href))||trigHit(href)))hide(links[m]);}")
        sb.append("}catch(e){}}")
        sb.append("function guardOpen(){try{if(window.open&&!window.open.__aiAdWrapped){var _o=window.open;var w=function(u){try{if(u&&(hit(D,hostOf(String(u)))||trigHit(String(u))))return null;}catch(e){}return _o.apply(window,arguments);};w.__aiAdWrapped=true;window.open=w;}}catch(e){}}")
        sb.append("function guardClick(){try{document.addEventListener('click',function(e){try{for(var i=0;i<T.length;i++){var t=T[i];if(t.indexOf('selector:')!==0)continue;var sel=t.slice(9);var el=e.target&&e.target.closest?e.target.closest(sel):null;if(el){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();hide(el);return;}}}catch(err){}},true);}catch(e){}}")
        sb.append("guardOpen();guardClick();clean(document);")
        sb.append("try{var ob=new MutationObserver(function(){clean(document);});if(document.documentElement)ob.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}")
        sb.append("})();")
        return sb.toString()
    }

    // 仅禁止自动开窗类规则（onPageStarted 里注，比 DOM 清理更早建好 window.open 拦截与点击劫持清理）。
    // 与 buildSiteJs 各用各的守卫，两份都注不冲突；window.open 已包过一层时不再重包。
    @JvmStatic
    fun buildTriggerJs(host: String?): String {
        val rule = getRule(host)
        val hasTrigger = rule.triggers.isNotEmpty()
        if (rule.domains.isEmpty() && !hasTrigger) return ""
        val sb = StringBuilder()
        sb.append("(function(){if(window.").append(GUARD_TRIGGER).append(")return;window.").append(GUARD_TRIGGER).append("=true;")
        sb.append("var D=").append(jsonArray(rule.domains)).append(";")
        sb.append("var T=").append(jsonArray(rule.triggers)).append(";")
        sb.append("function hostOf(u){try{return new URL(u,location.href).hostname.toLowerCase();}catch(e){return '';}}")
        sb.append("function hit(list,h){if(!h)return false;for(var i=0;i<list.length;i++){if(h===list[i]||h.endsWith('.'+list[i]))return true;}return false;}")
        sb.append("function trigHit(u){var h=hostOf(u);for(var i=0;i<T.length;i++){var t=T[i];if(t.indexOf('selector:')===0)continue;if(h===t||h.endsWith('.'+t))return true;}return false;}")
        sb.append("try{if(window.open&&!window.open.__aiAdWrapped){var _o=window.open;var w=function(u){try{if(u&&(hit(D,hostOf(String(u)))||trigHit(String(u))))return null;}catch(e){}return _o.apply(window,arguments);};w.__aiAdWrapped=true;window.open=w;}}catch(e){}")
        sb.append("try{document.addEventListener('click',function(e){try{for(var i=0;i<T.length;i++){var t=T[i];if(t.indexOf('selector:')!==0)continue;var sel=t.slice(9);var el=e.target&&e.target.closest?e.target.closest(sel):null;if(el){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();try{el.style.display='none';el.remove();}catch(err){}return;}}}catch(err){}},true);}catch(e){}")
        sb.append("})();")
        return sb.toString()
    }

    // ---------------- 内部 ----------------

    private fun loadRoot(): JSONObject {
        return try {
            val text = Prefers.getString(KEY_RULES, "")
            if (text.isEmpty()) JSONObject() else JSONObject(text)
        } catch (ignored: Throwable) {
            JSONObject()
        }
    }

    private fun saveRoot(root: JSONObject) {
        try {
            Prefers.put(KEY_RULES, root.toString())
        } catch (ignored: Throwable) {
        }
    }

    private fun jsonStrings(arr: JSONArray?): List<String> {
        val out = ArrayList<String>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun jsonArray(values: List<String>): String {
        val arr = JSONArray()
        for (v in values) arr.put(v)
        return arr.toString()
    }
}
