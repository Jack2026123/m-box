package com.github.tvbox.osc.api.ai

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.tvbox.osc.api.loader.AiAdRuleStore
import com.github.tvbox.osc.ui.dialog.AiTaskBoard
import com.github.tvbox.osc.ui.dialog.callChatHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// 3.68 C：AI 去广告模拟学习（开关打开即跑一遍，学完写规则）。
//
// 流程：离屏 WebView 真加载本站（复用 JsRender 的离屏思路，但绝不在当前 WebView 上模拟）
//   → shouldInterceptRequest 记下全部子请求 URL，媒体只记录不加载
//   → 渲染后取可疑元素 DOM 快照（fixed 定位、大 z-index、含 ad 特征 class 的 iframe/div）
//   → 对可疑元素逐个 dispatchEvent 模拟点按，看新开请求 / 弹窗 / window.open，抓点按触发的广告
//   → 证据截断后喂模型（callChatHistory，120 秒超时，心跳日志复用其内部实现）
//   → 模型只返回 JSON {domains, selectors, triggers}，过 AiAdRuleStore 校验后整站写规则。
// 学习失败（无模型 / 超时 / 空返回 / 解析失败）时回调 onFailure，规则不写半截。
// 未配模型时直接拒绝，不出空壳。
//
// 调用方是 Java（BrowserActivity）：入口 learn() 与 Callback 均按 Java 友好设计，
// 回调一律回到主线程再调，调用方可在回调里直接改 UI 与开关态。
object AiAdLearner {

    const val EVIDENCE_MAX_CHARS = 12000
    private const val PAGE_TIMEOUT_MS = 25000L
    private const val SIM_TIMEOUT_MS = 90000L
    private const val MAX_CANDIDATES = 40
    private const val MAX_CLICKS = 10
    private const val CLICK_SETTLE_MS = 900L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val learning = AtomicBoolean(false)

    interface Callback {
        fun onSuccess(host: String, domains: Int, selectors: Int, triggers: Int)
        fun onFailure(host: String, reason: String)
    }

    @JvmStatic
    fun isLearning(): Boolean = learning.get()

    // Java 入口：context 只用来取 Application 兜底（首选 App.getInstance，与 JsRender 同口径）。
    @JvmStatic
    fun learn(context: Context, url: String, callback: Callback?) {
        val siteHost = AiAdRuleStore.hostOf(url)
        if (siteHost.isEmpty()) {
            postFail(callback, "", "网址无效，取不到 host")
            return
        }
        if (!learning.compareAndSet(false, true)) {
            postFail(callback, siteHost, "已有学习任务在跑")
            return
        }
        if (!AiConfig.isConfigured()) {
            learning.set(false)
            postFail(callback, siteHost, "未配置模型，无法学习（请先在 AI 辅助里配好模型）")
            return
        }
        if (AiTaskBoard.activeTitle != null) {
            learning.set(false)
            postFail(callback, siteHost, "已有任务在跑：" + AiTaskBoard.activeTitle)
            return
        }
        AiTaskBoard.setActive("AI 去广告学习")
        AiTaskBoard.setProgress(0, "开始学习")
        AiTaskBoard.scope.launch {
            try {
                runLearn(context, url, siteHost, callback)
            } catch (e: Throwable) {
                AiTaskBoard.log("学习失败：" + (e.message ?: e.javaClass.simpleName))
                postFail(callback, siteHost, e.message ?: e.javaClass.simpleName)
            } finally {
                AiTaskBoard.setActive(null)
                learning.set(false)
            }
        }
    }

    private suspend fun runLearn(context: Context, url: String, siteHost: String, callback: Callback?) {
        AiTaskBoard.log("正在加载页面做模拟：" + url)
        AiTaskBoard.setProgress(10, "正在加载页面…")
        val sim = withTimeoutOrNull(SIM_TIMEOUT_MS) { simulate(context, url) }
        if (sim == null) {
            AiTaskBoard.log("学习失败：模拟超时")
            postFail(callback, siteHost, "模拟超时（页面 90 秒内未完成模拟）")
            return
        }
        AiTaskBoard.setProgress(55, "正在请模型分析…")
        AiTaskBoard.log("模拟完成：子请求 " + sim.requests.size + " 条，可疑元素 " + sim.candidateCount + " 个，点按命中 " + sim.clickHits.size + " 次")
        val evidence = buildEvidence(url, siteHost, sim)
        val systemPrompt = "你是网页去广告规则分析员。只根据给出的模拟证据，为当前站点生成去广告规则，" +
            "只返回一个 JSON 对象，不要任何解释、不要 markdown 代码块。" +
            "格式：{\"domains\": [], \"selectors\": [], \"triggers\": []}。" +
            "domains：第三方广告域名（只要 host，不要带协议与路径；不要本站主域与本站子域；不要媒体地址）。" +
            "selectors：广告元素的 CSS 选择器（优先 id 选择器；绝不能命中 video、播放器容器、播放按钮、正文与导航）。" +
            "triggers：点按才触发广告的证据：能确定落地域名的写裸域名；只能确定元素的写 \"selector:\" + 选择器。" +
            "没有把握的宁可不写；空数组表示本站干净。"
        val reply = try {
            withContext(Dispatchers.Default) {
                callChatHistory(systemPrompt, listOf("user" to evidence))
            }
        } catch (e: Throwable) {
            AiTaskBoard.log("学习失败：模型调用失败：" + (e.message ?: e.javaClass.simpleName))
            postFail(callback, siteHost, "模型调用失败：" + (e.message ?: e.javaClass.simpleName))
            return
        }
        if (reply.isBlank()) {
            AiTaskBoard.log("学习失败：模型返回为空")
            postFail(callback, siteHost, "模型返回为空")
            return
        }
        val parsed = parseRuleJson(reply)
        if (parsed == null) {
            AiTaskBoard.log("学习失败：模型返回无法解析为规则 JSON")
            postFail(callback, siteHost, "模型返回无法解析为规则 JSON")
            return
        }
        AiTaskBoard.setProgress(85, "正在写入规则…")
        val domains = AiAdRuleStore.sanitizeDomains(siteHost, parsed.first)
        val selectors = AiAdRuleStore.sanitizeSelectors(parsed.second)
        val triggers = AiAdRuleStore.sanitizeTriggers(siteHost, parsed.third)
        if (domains.isEmpty() && selectors.isEmpty() && triggers.isEmpty()) {
            AiTaskBoard.log("学习完成：本站未发现可用的广告规则（模型有返回，但全部被安全校验拦下或为空）")
            AiTaskBoard.setProgress(100, "学习完成")
            postSuccess(callback, siteHost, 0, 0, 0)
            return
        }
        AiAdRuleStore.saveRule(siteHost, domains, selectors, triggers)
        AiTaskBoard.log("学习完成：已写规则 域名 " + domains.size + " 条，选择器 " + selectors.size + " 条，触发器 " + triggers.size + " 条")
        AiTaskBoard.setProgress(100, "学习完成")
        postSuccess(callback, siteHost, domains.size, selectors.size, triggers.size)
    }

    // 模型回复里抠第一个完整 JSON 对象，取三类数组；抠不到或非法返回 null。
    private fun parseRuleJson(content: String): Triple<List<String>, List<String>, List<String>>? {
        return try {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = JSONObject(content.substring(start, end + 1))
            Triple(jsonStrings(obj.optJSONArray("domains")), jsonStrings(obj.optJSONArray("selectors")), jsonStrings(obj.optJSONArray("triggers")))
        } catch (ignored: Throwable) {
            null
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

    // 证据拼装：子请求 host 聚合 + 可疑元素快照 + 点按命中，整体截断到 12000 字。
    private fun buildEvidence(url: String, siteHost: String, sim: SimResult): String {
        val sb = StringBuilder()
        sb.append("站点：").append(url).append("\n本站 host：").append(siteHost).append("\n")
        val hostCount = LinkedHashMap<String, Int>()
        for (u in sim.requests) {
            val h = AiAdRuleStore.hostOf(u)
            if (h.isEmpty()) continue
            hostCount[h] = (hostCount[h] ?: 0) + 1
        }
        sb.append("子请求 host（host x 次数，共 ").append(sim.requests.size).append(" 条）：\n")
        for ((h, c) in hostCount) sb.append(h).append(" x").append(c).append("\n")
        sb.append("弹窗次数：").append(sim.popupCount).append("\n")
        sb.append("window.open 记录：\n")
        for (u in sim.openedUrls) sb.append(u).append("\n")
        sb.append("可疑元素快照（JSON）：\n").append(sim.candidatesJson).append("\n")
        sb.append("点按模拟命中（选择器 -> 触发结果）：\n")
        for (hit in sim.clickHits) sb.append(hit).append("\n")
        val text = sb.toString()
        return if (text.length > EVIDENCE_MAX_CHARS) text.substring(0, EVIDENCE_MAX_CHARS) else text
    }

    // ---------------- 离屏模拟（主线程建 WebView，用完销毁） ----------------

    private data class SimResult(
        val requests: List<String>,
        val popupCount: Int,
        val openedUrls: List<String>,
        val candidatesJson: String,
        val candidateCount: Int,
        val clickHits: List<String>
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun simulate(context: Context, url: String): SimResult {
        val app: Application? = try {
            com.github.tvbox.osc.base.App.getInstance()
        } catch (ignored: Throwable) {
            null
        } ?: try {
            context.applicationContext as? Application
        } catch (ignored: Throwable) {
            null
        }
        if (app == null) return SimResult(emptyList(), 0, emptyList(), "[]", 0, emptyList())
        return withContext(Dispatchers.Main) {
            simulateOnMain(app, url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun simulateOnMain(app: Application, url: String): SimResult {
        val requests = ArrayList<String>()
        val opened = ArrayList<String>()
        val clickHits = ArrayList<String>()
        var popupCount = 0
        var navAttempts = 0
        var finished = false
        var web: WebView? = null
        try {
            val w = WebView(app)
            web = w
            val settings = w.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.setJavaScriptCanOpenWindowsAutomatically(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // 全拦：首次本站导航放行一次，其余任何导航（点按弹窗 / 跳转）只记录、不执行。
                    val target = request.url.toString()
                    if (!finished && target == url) return false
                    navAttempts++
                    synchronized(opened) { opened.add("nav:" + target) }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    synchronized(requests) { requests.add(u) }
                    // 媒体只记录、不加载（不下载视频/音频，防模拟跑偏成播放器）。
                    if (AiAdRuleStore.isMediaUrl(u)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun onPageStarted(view: WebView, startedUrl: String, favicon: android.graphics.Bitmap?) {
                    // 尽早包住 window.open：只记录、不执行跳转；页面脚本拿到的就是已包过的版本。
                    view.evaluateJavascript(
                        "(function(){if(window.__aiAdOpened)return;window.__aiAdOpened=[];" +
                            "var _o=window.open;window.open=function(u){try{window.__aiAdOpened.push(String(u));}catch(e){}return null;};})()",
                        null
                    )
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    finished = true
                }
            }
            w.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    popupCount++
                    result.confirm()
                    return true
                }

                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    popupCount++
                    result.confirm()
                    return true
                }

                override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                    popupCount++
                    result.cancel()
                    return true
                }

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                    navAttempts++
                    return false
                }
            }
            w.loadUrl(url)
            withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                while (!finished) kotlinx.coroutines.delay(200)
            }
            // 广告脚本常在 load 后才渲染 / 才弹，静置一拍再快照。
            kotlinx.coroutines.delay(2000)
            val openedRaw = evalJs(w, "(window.__aiAdOpened?JSON.stringify(window.__aiAdOpened):'[]')")
            for (u in jsonStrings(parseJsonArray(openedRaw))) {
                synchronized(opened) { opened.add("open:" + u) }
            }
            val candidatesRaw = evalJs(w, CANDIDATE_SNAPSHOT_JS) ?: "[]"
            val candidatesJson = unescapeJs(candidatesRaw)
            val candidates = parseCandidates(candidatesJson)
            var step = 0
            for (sel in candidates) {
                if (step >= MAX_CLICKS) break
                step++
                AiTaskBoard.log("正在模拟第 " + step + " 步…（" + sel + "）")
                AiTaskBoard.setProgress(10 + step * 4, "正在模拟第 " + step + " 步…")
                val beforeOpened = opened.size
                val beforeNav = navAttempts
                val beforePopup = popupCount
                val beforeReq = requests.size
                val clickBack = evalJs(w, clickJs(sel))
                if (clickBack == null || clickBack.contains("missing") || clickBack.contains("err:")) continue
                kotlinx.coroutines.delay(CLICK_SETTLE_MS)
                val newOpened = ArrayList<String>()
                synchronized(opened) {
                    for (i in beforeOpened until opened.size) newOpened.add(opened[i])
                }
                val newReqHosts = LinkedHashSet<String>()
                synchronized(requests) {
                    for (i in beforeReq until requests.size) {
                        val h = AiAdRuleStore.hostOf(requests[i])
                        if (h.isNotEmpty()) newReqHosts.add(h)
                    }
                }
                val hit = describeClickHit(sel, newOpened, navAttempts - beforeNav, popupCount - beforePopup, newReqHosts)
                if (hit != null) clickHits.add(hit)
            }
            val openedOut = ArrayList<String>()
            synchronized(opened) { openedOut.addAll(opened) }
            val reqOut = ArrayList<String>()
            synchronized(requests) { reqOut.addAll(requests) }
            return SimResult(reqOut, popupCount, openedOut, candidatesJson, candidates.size, clickHits)
        } finally {
            try {
                web?.destroy()
            } catch (ignored: Throwable) {
            }
        }
    }

    // 点按命中判定：新开 window.open / 导航拦截 / 弹窗出现其一即命中。
    // 落地域名能取到写裸域名，否则写 selector: 选择器（只证明该元素点按会开广告）。
    private fun describeClickHit(
        sel: String,
        newOpened: List<String>,
        newNav: Int,
        newPopup: Int,
        newReqHosts: Set<String>
    ): String? {
        for (entry in newOpened) {
            val raw = entry.removePrefix("open:").removePrefix("nav:")
            val h = AiAdRuleStore.hostOf(raw)
            if (h.isNotEmpty()) return sel + " -> " + h
        }
        if (newNav > 0 || newPopup > 0) return "selector:" + sel + " -> click-opens-ad(nav=" + newNav + ",popup=" + newPopup + ")"
        if (newReqHosts.isNotEmpty()) return sel + " -> new-requests:" + newReqHosts.joinToString(",")
        return null
    }

    private fun parseCandidates(json: String): List<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_CANDIDATES) break
                val sel = arr.optJSONObject(i)?.optString("sel", "")?.trim() ?: ""
                if (sel.isNotEmpty() && !out.contains(sel)) out.add(sel)
            }
        } catch (ignored: Throwable) {
        }
        return out
    }

    private fun parseJsonArray(raw: String?): JSONArray? {
        if (raw == null) return null
        return try {
            JSONArray(unescapeJs(raw))
        } catch (ignored: Throwable) {
            null
        }
    }

    // evaluateJavascript 回调是异步的：5 秒不回按空处理，避免永久挂起。
    private suspend fun evalJs(web: WebView, script: String): String? {
        return suspendCancellableCoroutine { cont ->
            var done = false
            val timeout = Runnable {
                if (done) return@Runnable
                done = true
                if (cont.isActive) cont.resume(null)
            }
            mainHandler.postDelayed(timeout, 5000L)
            try {
                web.evaluateJavascript(script) { raw ->
                    if (done) return@evaluateJavascript
                    done = true
                    mainHandler.removeCallbacks(timeout)
                    if (cont.isActive) cont.resume(raw)
                }
            } catch (ignored: Throwable) {
                if (!done) {
                    done = true
                    mainHandler.removeCallbacks(timeout)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun clickJs(selector: String): String {
        val sel = JSONObject.quote(selector)
        return "(function(){try{var el=document.querySelector(" + sel + ");" +
            "if(!el)return 'missing';" +
            "el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));return 'ok';}" +
            "catch(e){return 'err:'+e;}})()"
    }

    // WebView 回传的 JS 字符串带转义，还原成真实文本（与 JsRender.unescapeJs 同法）。
    private fun unescapeJs(raw: String): String {
        var s = raw.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        s = s.replace("\\\\", "\u0000")
        s = s.replace("\\\"", "\"")
        s = s.replace("\\/", "/")
        s = s.replace("\\n", "\n")
        s = s.replace("\\r", "\r")
        s = s.replace("\\t", "\t")
        s = s.replace("\u0000", "\\")
        return s
    }

    private fun postSuccess(callback: Callback?, host: String, domains: Int, selectors: Int, triggers: Int) {
        if (callback == null) return
        mainHandler.post { callback.onSuccess(host, domains, selectors, triggers) }
    }

    private fun postFail(callback: Callback?, host: String, reason: String) {
        if (callback == null) return
        mainHandler.post { callback.onFailure(host, reason) }
    }

    // 可疑元素快照 JS：iframe 必列；fixed 定位、大 z-index（>=1000）、id/class 含 ad 特征的 div 也列。
    // 只回 tag/sel/pos/z/src 四个短字段，40 条封顶，证据省字。
    private const val CANDIDATE_SNAPSHOT_JS =
        "(function(){var out=[];var re=/ad|ads|advert|guanggao|banner|popup|popunder|sponsor|promotion/i;" +
            "var els=document.querySelectorAll('iframe,div,ins,a,img');" +
            "for(var i=0;i<els.length&&out.length<40;i++){var el=els[i];" +
            "var cs=getComputedStyle(el);var id=el.id||'';" +
            "var cl=(typeof el.className==='string'?el.className:'')||'';" +
            "var z=parseInt(cs.zIndex||'0',10)||0;" +
            "var sus=re.test(id+' '+cl);" +
            "if(el.tagName==='IFRAME'||cs.position==='fixed'||z>=1000||sus){" +
            "var sel=id?('#'+id):(cl?('.'+cl.trim().split(/\\s+/)[0]):el.tagName.toLowerCase());" +
            "out.push({tag:el.tagName,sel:sel,pos:cs.position,z:z,src:(el.src||el.href||'').slice(0,120)});}}" +
            "return JSON.stringify(out);})()"
}
