package com.github.tvbox.osc.util
import com.github.tvbox.osc.api.loader.BrowserMode
import com.github.tvbox.osc.api.loader.SiteLiveHost
import com.github.tvbox.osc.bean.SourceBean
import com.google.gson.JsonParser
import java.net.URL
/**
 * 解析一个源在「网页 Tab / 浏览器模式」里应当打开的首页候选列表（按优先级排序）。
 *
 * r481：顺序照 muse 的 resolveHomePageUrl（VideoActivity）来 ——
 * 条目「主页url」> webPage 模板 > 规则「发布站/防丢url」> 规则「域名-c/域名*」
 * > 浏览器记下的活域名 > siteBaseUrl 兜底（api/ext/行内 JSON/.py/.js 脚本/.json 规则）。
 * muse 不从视频数据里取站地址（没有 fix 7 那种记法），所以 SortLoader 的视频记活域名已整段删掉；
 * 活域名只由浏览器实际浏览时记，放在规则之后、兜底之前。
 * 键识别沿用 3.75 更全的口径：主页url/homepage/homePage > 域名-c > 含「域名」的键 > site/host/domain/baseUrl/base_url/url；
 * 候选列表：把逗号/分号分隔的多域名全部拆出，本地服务地址(127.x/localhost/::1/0.0.0.0)一律过滤；
 * 抽出 candidatesFrom(key,api,ext,homePageUrl,webPage) 供 BrowserSource 直接调用，实现一源两用。
 */
object SiteHomePageResolver {
    // 3.75 键识别口径（主页url > 域名-c > 含「域名」键 > site/host/domain/baseUrl/base_url/url）
    private val HOME_KEYS = arrayOf("主页url", "homepage", "homePage")
    private val SITE_KEYS = arrayOf("site", "host", "domain", "baseUrl", "base_url", "url")

    /** 兼容旧调用：返回首个候选首页。 */
    fun resolve(source: SourceBean?): String? = resolveCandidates(source).firstOrNull()

    @JvmStatic
    fun resolveCandidates(source: SourceBean?): List<String> {
        if (source == null) return emptyList()
        return candidatesFrom(source.key, source.api, source.ext, source.homePageUrl, source.webPage)
    }

    /** 旧 4 参调用（BrowserSource）兼容：webPage 为空。 */
    @JvmStatic
    fun candidatesFrom(key: String?, api: String?, ext: String?, homePageUrl: String?): List<String> =
        candidatesFrom(key, api, ext, homePageUrl, null)

    /** 从原始字段解析候选首页列表（不依赖 SourceBean，供 BrowserSource 等直接调用，实现一源两用）。 */
    @JvmStatic
    fun candidatesFrom(key: String?, api: String?, ext: String?, homePageUrl: String?, webPage: String?): List<String> {
        val apiT = api?.trim().orEmpty()
        val extT = ext?.trim().orEmpty()
        val redirect = isRedirectSite(apiT, extT)
        val raw = ArrayList<String>()
        // 1) muse 第 1 步：条目顶层「主页url」（重定向站不含 [工具: 才用；http 的重定向站升 https）
        homePageUrl?.trim()?.let {
            if (it.isNotEmpty() && !it.contains("[工具:")) {
                var origin = originOf(it)
                if (origin != null) {
                    if (redirect && origin.startsWith("http://")) origin = "https://" + origin.substring("http://".length)
                    raw.add(origin)
                }
            }
        }
        // 2) muse 第 2 步：webPage 模板（顶层 webPage 优先，其次行内 JSON ext 里的 webPage 键）——只取域名
        webPage?.trim()?.let { if (it.isNotEmpty()) originOf(it)?.let { o -> raw.add(o) } }
        if (extT.startsWith("{")) {
            try {
                val obj = JsonParser.parseString(extT).asJsonObject
                stringValue(obj, "webPage")?.let { originOf(it)?.let { o -> raw.add(o) } }
            } catch (_: Exception) {
            }
        }
        // 3) AVBox 自有：web/browser 形态 api/ext（muse 没有这一步；网页源/浏览器模式靠它）
        browserSiteHome(apiT, extT, key)?.let { raw.add(it) }
        // 4) muse 第 3/4 步：规则里的「发布站/防丢url」>「域名-c/域名*」（规则 = 行内 JSON ext 或 .json 规则文件）
        ruleJsonText(apiT, extT)?.let { ruleText ->
            try {
                val obj = JsonParser.parseString(ruleText).asJsonObject
                for (prefix in arrayOf("发布站", "防丢url")) {
                    for (k in obj.keySet()) {
                        if (!k.startsWith(prefix)) continue
                        val t = stringValue(obj, k) ?: continue
                        originOf(t)?.let { raw.add(it) }
                    }
                }
                stringValue(obj, "域名-c")?.let { originOf(it)?.let { o -> raw.add(o) } }
                for (k in obj.keySet()) {
                    if (k == "域名-c" || !k.contains("域名")) continue
                    val t = stringValue(obj, k) ?: continue
                    originOf(t)?.let { raw.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        // 5) muse 第 5 步的等价物：浏览器实际浏览记下的活域名（muse 只对重定向站用 detailOrigin；
        //    这里是真实浏览过的站，放规则之后、兜底之前）
        SiteLiveHost.get(key)?.let { raw.add(it) }
        // 6) muse 第 6 步 siteBaseUrl 兜底：api 是真实远程 http > ext 直接是网址 > 行内 JSON 各键 > 脚本/规则文件
        if (apiT.startsWith("http") && !apiT.contains("/file/")) raw.add(apiT)
        if (extT.startsWith("http") && !extT.contains("/file/")) raw.add(extT)
        if (extT.startsWith("{")) {
            try {
                val obj = JsonParser.parseString(extT).asJsonObject
                for (k in HOME_KEYS) {
                    val t = stringValue(obj, k); if (t != null) raw.add(t)
                }
                for (k in SITE_KEYS) {
                    val t = stringValue(obj, k); if (t != null) raw.add(t)
                }
            } catch (_: Exception) {
            }
        }
        // .py/.js 脚本文件：先读 # homepage: / // homepage: 注释，再读 host/api 赋值或第一个 http 地址
        if (apiT.endsWith(".py") || apiT.endsWith(".js") || extT.endsWith(".py") || extT.endsWith(".js")) {
            scriptSiteHost(apiT)?.let { raw.add(it) }
            if (extT.endsWith(".py") || extT.endsWith(".js")) scriptSiteHost(extT)?.let { raw.add(it) }
        }
        // .json 规则文件：先取 site/host/domain 等键，没有再按脚本读
        if (extT.endsWith(".json")) {
            ruleJsonText(apiT, extT)?.let { ruleText ->
                try {
                    val obj = JsonParser.parseString(ruleText).asJsonObject
                    for (k in SITE_KEYS) {
                        val t = stringValue(obj, k); if (t != null) raw.add(t)
                    }
                } catch (_: Exception) {
                }
            }
        }
        // 归一：展开逗号/分号/空白分隔的多域名 → 补 scheme → 过滤本地/IP/代码站/对象存储 → 去重保序
        val out = LinkedHashSet<String>()
        for (item in raw) {
            for (u in expand(item)) {
                val s = withScheme(u)
                if (isBrowsable(s) && !isIpHost(s)) out.add(s)
            }
        }
        return ArrayList(out)
    }

    /** muse 的 domainFromScript + homepageFromScript：读缓存里的 .py/.js 脚本取站地址。
     * 顺序：脚本注释里的 # homepage: URL / // homepage: URL 优先，再是 host/api/domain/site/baseurl 赋值，
     * 最后是文件里第一个 http 地址（本地/代码站过滤）。 */
    private fun scriptSiteHost(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val low = url.lowercase()
        if (!low.contains(".py") && !low.contains(".js")) return null
        val ctx = AppContextHolder.context() ?: return null
        // 文件名口径与 Python 侧 downloadPlugin 一致：取 URL 最后一段、第一个点之前的名字 + .py
        val last = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (last.isEmpty()) return null
        val name = last.substringBefore('.')
        if (name.isEmpty()) return null
        val suffix = if (low.contains(".py")) ".py" else ".js"
        val file = java.io.File(java.io.File(ctx.cacheDir, "py"), name + suffix)
        if (!file.exists()) return null
        val text = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { return null }
        if (text.isEmpty()) return null
        homepageFromScript(text)?.let { return it }
        val assign = Regex("""(?i)(host|api|domain|site|baseurl|base_url|base)\s*=\s*["'](https?://[^"'\s]+)["']""").find(text)
        if (assign != null) {
            originOf(assign.groupValues[2])?.let { return it }
        }
        for (m in Regex("""https?://[A-Za-z0-9.\-:]+""").findAll(text)) {
            originOf(m.value)?.let { return it }
        }
        return null
    }

    /** muse 的 homepageFromScript：从 py/js 脚本注释里找显式前端网址
     * （# homepage: URL 或 // homepage: URL，注释不执行，不会跟蜘蛛冲突）。 */
    private fun homepageFromScript(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val m = Regex("""(?i)(?:#|//)\s*(homepage|home_page|主页url|主页|前台|前端|域名-c|域名c)\s*[:：]\s*(https?://[^\s'"<>]+)""").find(text)
            ?: return null
        val origin = originOf(m.groupValues[2]) ?: return null
        if (isIpHost(origin)) return null
        return origin
    }

    /** muse 的 ruleJsonObject：规则 JSON 文本——行内 JSON ext 直接用；ext 是 .json 文件时读文件。 */
    private fun ruleJsonText(api: String, ext: String): String? {
        if (ext.startsWith("{")) return ext
        val target = if (ext.endsWith(".json")) ext else if (api.endsWith(".json")) api else return null
        val ctx = AppContextHolder.context() ?: return null
        // 规则文件与脚本同一基准：先按配置目录找，再走缓存 py 目录
        val name = target.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (name.isEmpty()) return null
        val file = java.io.File(java.io.File(ctx.cacheDir, "py"), name)
        if (!file.exists()) return null
        return try { file.readText(Charsets.UTF_8).trim().takeIf { it.startsWith("{") } } catch (_: Exception) { null }
    }

    /** muse 的 isRedirectSite：api/ext 含 [工具: 模板的是重定向/域名跳转站，配置首页是跳转页。 */
    private fun isRedirectSite(api: String, ext: String): Boolean =
        api.contains("[工具:") || ext.contains("[工具:")

    /** muse 的 isIpHost：纯 IP 主机不是网站首页（最后一步才拦）。 */
    private fun isIpHost(raw: String): Boolean {
        var host = raw.trim()
        val di = host.indexOf("://")
        if (di >= 0) host = host.substring(di + 3)
        val slash = host.indexOf('/'); if (slash >= 0) host = host.substring(0, slash)
        val colon = host.indexOf(':'); if (colon >= 0) host = host.substring(0, colon)
        return host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))
    }

    /** 只留协议+域名（+端口），过滤本地和代码站。 */
    private fun originOf(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val scheme = raw.indexOf("://")
        if (scheme < 0) return null
        val start = scheme + 3
        var end = raw.length
        for (i in start until raw.length) {
            val c = raw[i]
            if (c == '/' || c == '?' || c == '#' || c == ',') { end = i; break }
        }
        if (end <= start) return null
        val host = raw.substring(start, end).lowercase()
        if (host.startsWith("127.") || host.startsWith("localhost") || host.startsWith("0.0.0.0")
            || host.startsWith("raw.githubusercontent.com") || host.startsWith("github.com")
            || host.startsWith("cdn.jsdelivr.net") || host.startsWith("unpkg.com")) return null
        // r478：对象存储/文件桶不是网站首页（.py 里常有 OSS/S3 的数据、配置、图片地址，误当成站会打开 AccessDenied）。
        if (isStorageHost(host)) return null
        return raw.substring(0, end)
    }

    /** 对象存储/文件桶域名：aliyuncs(OSS)、amazonaws(S3)、谷歌云存储、华为 OBS、腾讯 COS 等，绝不是网站首页。 */
    private fun isStorageHost(host: String): Boolean {
        if (host.contains("aliyuncs.com") || host.contains("amazonaws.com")
            || host.contains("storage.googleapis.com") || host.contains("myqcloud.com")
            || host.contains(".obs.") || host.startsWith("obs.")
            || host.contains("oss-") || host.endsWith(".oss")
            || host.contains("cos.") || host.contains("bcebos.com")
            || host.contains("qiniucs.com") || host.contains("upcdn.net")) return true
        return false
    }

    private fun stringValue(obj: com.google.gson.JsonObject, key: String): String? {
        if (!obj.has(key)) return null
        val value = obj.get(key)
        if (value == null || !value.isJsonPrimitive) return null
        val text = value.asString.trim()
        return text.ifEmpty { null }
    }

    /** 从 web/browser 形态的 api/ext 里取真实站点首页；取不到返回 null。 */
    private fun browserSiteHome(api: String, ext: String, key: String?): String? {
        val low = api.lowercase()
        if (low.startsWith("browser://")) return api.substring("browser://".length).trim().ifEmpty { null }
        if (low.startsWith("web://")) return api.substring("web://".length).trim().ifEmpty { null }
        val isWebKind = low == "web" || low == "browser" || BrowserMode.isOn(key.orEmpty())
        if (isWebKind && ext.startsWith("http")) return ext
        if (!low.startsWith("http") && ext.startsWith("http")) return ext
        return null
    }

    /** 把 "a,b;c / 空格 / 换行" 分隔的多个域名拆成列表。 */
    private fun expand(v: String?): List<String> {
        if (v.isNullOrEmpty()) return emptyList()
        val parts = v.split(Regex("[,;，；\\s\\n]+"))
        val r = ArrayList<String>()
        for (p in parts) {
            val t = p.trim()
            if (t.isNotEmpty() && (t.startsWith("http") || t.contains("."))) r.add(t)
        }
        return r
    }

    private fun withScheme(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        return "https://" + text
    }

    /** 护栏：只允许浏览外部站点；指向内置 server（127.x / localhost / ::1）的地址一律拒绝。 */
    private fun isBrowsable(raw: String): Boolean {
        val host = hostOfLoose(raw)
        if (host.isEmpty()) return false
        return !isLocalHost(host)
    }

    private fun hostOfLoose(raw: String): String {
        var s = raw.trim()
        val di = s.indexOf("://")
        if (di >= 0) s = s.substring(di + 3)
        if (s.startsWith("//")) s = s.substring(2)
        val slash = s.indexOf('/'); if (slash >= 0) s = s.substring(0, slash)
        val q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q)
        val h = s.indexOf('#'); if (h >= 0) s = s.substring(0, h)
        if (s.startsWith("[")) {
            val e = s.indexOf(']'); return if (e > 0) s.substring(0, e + 1).lowercase() else s.lowercase()
        }
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.substring(colon + 1).isNotEmpty() && s.substring(colon + 1).all { it.isDigit() }) {
            s = s.substring(0, colon)
        }
        return s.lowercase()
    }

    private fun isLocalHost(host: String): Boolean {
        return host == "localhost" || host == "::1" || host == "[::1]" ||
            host == "0.0.0.0" || host == "[::]" || host.startsWith("127.")
    }

    /** 公开护栏：给 MainScreen 等调用方复用 —— 该地址是否指向内置 server（不可作为浏览首页）。 */
    fun isLocalServer(raw: String?): Boolean {
        if (raw == null) return true
        val host = hostOfLoose(raw)
        return host.isEmpty() || isLocalHost(host)
    }
}