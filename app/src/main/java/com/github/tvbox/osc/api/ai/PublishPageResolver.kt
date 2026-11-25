package com.github.tvbox.osc.api.ai

import android.os.Handler
import android.os.Looper
import com.github.tvbox.osc.util.AdBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 3.68 A：发布页解析器（给 B 浏览器侧调用，也供工坊复用）。
 *
 * 发布页（如 shewo1.cc、mtyy.tv）：本身不是影视站，首页只有一个或几个
 * 「进入真站」按钮（力争上游 / 奋发图强 / 持之以恒 / 最快线路），点进去才是真站。
 * 本解析器找出真站 URL，并用 AvSiteScout 定站型；非发布页直接返回原 URL。
 *
 * 三档：
 * 1. OkHttp 静态抓 a[href] + 按钮文本加权；
 * 2. 抓不到或正文过短 → JsRender.render（离屏 WebView 真执行 JS）再抓，
 *    shewo1.cc 白屏约 5 秒后 JS 才出按钮，静态抓永远空；
 * 3. 候选去重 + 过 AdBlocker.isAd 排除广告域 + 排除下载链 → AvSiteScout.scout 定站型。
 *
 * 关于 mtyy2.cc 的 closed：AvSiteScout.probeCmsApi 要求 /api.php/provide/vod/
 * 返回 HTTP 200 + JSON + 非空分类表才算命中；正文是 closed 不是 JSON，直接判
 * 不命中、落到 HTML 分支。本解析器只把 closed 记进 notes，不把它当 JSON 源，
 * 站型以 scout 的最终定性为准（有详情链接即 HTML 站）。
 *
 * 纪律：只输出结构（URL、站型、notes），不回显页面正文。
 */
object PublishPageResolver {

    /** 解析结果。finalUrl 非空；isPublishPage 为 false 时 finalUrl 就是原 URL。 */
    data class PublishResolveResult(
        val finalUrl: String,
        val isPublishPage: Boolean,
        val kind: String,
        val cms: String = "",
        val notes: List<String> = emptyList(),
    )

    /** Java 友好回调。onResult 在主线程回，result 为 null 只表示入参 URL 非法。 */
    interface Callback {
        fun onResult(result: PublishResolveResult?)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // 入口链接最低加权分：必须命中一个入口关键词才算发布页，普通外链不算。
    private const val MIN_ENTRY_SCORE = 5

    private val KNOWN_PUBLISH_BUTTONS = arrayOf("力争上游", "奋发图强", "持之以恒")

    private val DOWNLOAD_HOSTS = arrayOf("lanzou", "lanzous", "pan.baidu.com", "123pan", "aliyundrive")
    private val DOWNLOAD_EXTS = arrayOf(".apk", ".exe", ".zip", ".rar", ".7z", ".dmg", ".iso")

    private data class Candidate(val url: String, val score: Int, val text: String)

    /**
     * Kotlin suspend 版：解析 url，返回结果；入参空白/非法返回 null，
     * 其余情况总返回一个结果（失败时 finalUrl 为原 URL、kind 为 UNKNOWN，原因在 notes）。
     */
    suspend fun resolvePublishPage(url: String): PublishResolveResult? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val start = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://" + raw
        val notes = ArrayList<String>()
        val first = fetch(start)
        if (first.first == -1) {
            notes.add("抓取失败：" + first.third)
            return PublishResolveResult(start, false, AvSiteScout.KIND_UNKNOWN, "", notes)
        }
        var html = first.second
        val base = first.third.ifEmpty { start }
        var candidates = extractCandidates(html, base)
        if (candidates.none { it.score >= MIN_ENTRY_SCORE } && (html.length < 1200 || candidates.isEmpty())) {
            notes.add("静态抓不到入口或正文过短（len=" + html.length + "），改用无头 WebView 渲染再抓")
            try {
                val rendered = JsRender.render(base, 25000L)
                if (rendered.length > html.length) {
                    html = rendered
                    candidates = extractCandidates(html, base)
                    notes.add("渲染后正文 len=" + html.length + "，候选 " + candidates.size + " 条")
                } else {
                    notes.add("渲染未获得更多内容，沿用静态抓取结果")
                }
            } catch (ignored: Throwable) {
                notes.add("渲染失败，沿用静态抓取结果")
            }
        }
        val best = candidates.filter { it.score >= MIN_ENTRY_SCORE }.maxByOrNull { it.score }
        val isPublish = best != null
        val target = best?.url ?: start
        if (isPublish) {
            notes.add("发布页入口：" + (best?.text ?: "") + " -> " + target)
        } else {
            notes.add("未发现发布页入口，按普通站处理")
        }
        val report = scoutTarget(target, notes)
        return PublishResolveResult(
            finalUrl = target,
            isPublishPage = isPublish,
            kind = report?.kind ?: AvSiteScout.KIND_UNKNOWN,
            cms = report?.cms ?: "",
            notes = notes,
        )
    }

    /** Java 友好版：后台解析，主线程回调。入参非法时回调 null。 */
    @JvmStatic
    fun resolve(url: String, callback: Callback) {
        scope.launch {
            val result = try {
                resolvePublishPage(url)
            } catch (ignored: Throwable) {
                null
            }
            mainHandler.post {
                try {
                    callback.onResult(result)
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    /**
     * 3.68 B：按 live WebView 已有 DOM 的候选列表判定真入口（不重新抓取页面）。
     * candidatesJson：JSON 数组 [{u: 链接, t: 链接/按钮文本}]，由浏览器 evaluateJavascript 回传。
     * 判定口径与静态抓取同一套：入口关键词加权（MIN_ENTRY_SCORE）+ 广告/下载排除 → AvSiteScout.scout 定站型。
     * 非发布页时 finalUrl 为 baseUrl、isPublishPage=false；入参非法时回调 null。
     */
    @JvmStatic
    fun resolveCandidates(baseUrl: String, candidatesJson: String, callback: Callback) {
        scope.launch {
            val result = try {
                resolveCandidatesSync(baseUrl, candidatesJson)
            } catch (ignored: Throwable) {
                null
            }
            mainHandler.post {
                try {
                    callback.onResult(result)
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private suspend fun resolveCandidatesSync(baseUrl: String, candidatesJson: String): PublishResolveResult? {
        val raw = baseUrl.trim()
        if (raw.isEmpty()) return null
        val start = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://" + raw
        val notes = ArrayList<String>()
        val found = LinkedHashMap<String, Candidate>()
        val baseHost = try {
            URI(start).host.orEmpty().lowercase().removePrefix("www.")
        } catch (ignored: Throwable) {
            ""
        }
        var total = 0
        try {
            val arr = JSONArray(candidatesJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                total++
                collectCandidate(found, start, baseHost, o.optString("u", ""), o.optString("t", ""))
            }
        } catch (ignored: Throwable) {
        }
        notes.add("live DOM 候选 " + total + " 条，去重后 " + found.size + " 条")
        val best = found.values.filter { it.score >= MIN_ENTRY_SCORE }.maxByOrNull { it.score }
        val isPublish = best != null
        val target = best?.url ?: start
        if (isPublish) {
            notes.add("发布页入口：" + (best?.text ?: "") + " -> " + target)
        } else {
            notes.add("live DOM 未发现发布页入口，按普通站处理")
        }
        val report = scoutTarget(target, notes)
        return PublishResolveResult(
            finalUrl = target,
            isPublishPage = isPublish,
            kind = report?.kind ?: AvSiteScout.KIND_UNKNOWN,
            cms = report?.cms ?: "",
            notes = notes,
        )
    }

    // 定站型（resolvePublishPage 与 resolveCandidatesSync 共用）：AvSiteScout.scout 定站型，
    // /api.php/provide/vod/ 返回 closed 正文时记一条 notes，不把它当 JSON 源。
    private suspend fun scoutTarget(target: String, notes: MutableList<String>): AvSiteScout.Report? {
        var cmsApiClosed = false
        val fetchForScout: (String) -> Pair<Int, String> = { u ->
            val r = fetch(u)
            if (u.contains("/api.php/provide/vod/") && r.second.trim().equals("closed", ignoreCase = true)) {
                cmsApiClosed = true
            }
            Pair(r.first, r.second)
        }
        val report = try {
            AvSiteScout.scout(target, fetchForScout) { }
        } catch (ignored: Throwable) {
            null
        }
        if (cmsApiClosed) {
            notes.add("采集接口返回 closed，不能当 JSON 源，已按 HTML 站处理")
        }
        if (report != null) {
            notes.add("站型：" + report.headline())
            notes.addAll(report.notes)
        }
        return report
    }

    // 候选收集（extractCandidates 与 resolveCandidatesSync 共用）：去广告/下载链 + 入口关键词加权，同 URL 取最高分。
    private fun collectCandidate(found: LinkedHashMap<String, Candidate>, base: String, baseHost: String, rawHref: String, rawText: String) {
        val href = rawHref.trim()
        if (href.isEmpty() || href.startsWith("javascript:") || href.startsWith("mailto:") ||
            href.startsWith("tel:") || href == "#"
        ) return
        val abs = resolveUrl(base, href) ?: return
        val text = cleanText(rawText)
        if (isDownloadLink(abs, text)) return
        if (isAdLink(abs)) return
        val host = try {
            URI(abs).host.orEmpty().lowercase().removePrefix("www.")
        } catch (ignored: Throwable) {
            ""
        }
        val score = scoreCandidate(text, host, baseHost)
        val key = abs.lowercase().trimEnd('/')
        val prev = found[key]
        if (prev == null || score > prev.score) {
            found[key] = Candidate(abs, score, text)
        }
    }

    // 抓一次：返回（状态码，正文，跟随跳转后的最终 URL）；网络错返回（-1，"", 错误信息）。
    private fun fetch(url: String): Triple<Int, String, String> {
        val first = fetchOnce(url, UA_MOBILE)
        if (first.first != -1 && first.second.length >= 200) return first
        if (first.first == -1) return first
        val retry = fetchOnce(url, UA_DESKTOP)
        return if (retry.second.length > first.second.length) retry else first
    }

    private fun fetchOnce(url: String, ua: String): Triple<Int, String, String> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            client.newCall(req).execute().use { resp ->
                Triple(resp.code, resp.body?.string().orEmpty(), resp.request.url.toString())
            }
        } catch (e: Throwable) {
            Triple(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    // 从 HTML 里提入口候选：a[href] 带链接文本 + button/JS 跳转（window.open / location.href）。
    private fun extractCandidates(html: String, base: String): List<Candidate> {
        val found = LinkedHashMap<String, Candidate>()
        val baseHost = try {
            URI(base).host.orEmpty().lowercase().removePrefix("www.")
        } catch (ignored: Throwable) {
            ""
        }
        fun add(rawHref: String, rawText: String) {
            collectCandidate(found, base, baseHost, rawHref, rawText)
        }
        try {
            Regex("<a[^>]+href=[\"']([^\"']{1,500})[\"'][^>]*>([\\s\\S]{0,300}?)</a>", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { m -> add(m.groupValues[1], m.groupValues[2]) }
        } catch (ignored: Throwable) {
        }
        try {
            Regex("<button[^>]*onclick=[\"'][^\"']*(https?://[^\"'\\s<>]{1,500})[^\"']*[\"'][^>]*>([\\s\\S]{0,200}?)</button>", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { m -> add(m.groupValues[1], m.groupValues[2]) }
        } catch (ignored: Throwable) {
        }
        try {
            Regex("(?:window\\.open\\s*\\(\\s*|location\\.href\\s*=\\s*|location\\s*=\\s*)[\"'](https?://[^\"'\\s<>]{1,500})[\"']", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { m -> add(m.groupValues[1], "") }
        } catch (ignored: Throwable) {
        }
        return found.values.toList()
    }

    // 入口加权：发布页按钮文本命中才给分，普通链接不加分。
    private fun scoreCandidate(text: String, host: String, baseHost: String): Int {
        var score = 0
        if (text.contains("最快线路")) score += 10
        for (known in KNOWN_PUBLISH_BUTTONS) {
            if (text.contains(known)) score += 8
        }
        if (text.contains("进入")) score += 5
        if (text.contains("线路")) score += 3
        if (host.isNotEmpty() && baseHost.isNotEmpty() && host != baseHost) score += 2
        return score
    }

    private fun isDownloadLink(absUrl: String, text: String): Boolean {
        if (text.contains("下载")) return true
        val low = absUrl.lowercase()
        val host = try {
            URI(absUrl).host.orEmpty().lowercase()
        } catch (ignored: Throwable) {
            ""
        }
        if (DOWNLOAD_HOSTS.any { host.contains(it) }) return true
        val path = try {
            URI(absUrl).path.orEmpty().lowercase()
        } catch (ignored: Throwable) {
            ""
        }
        return DOWNLOAD_EXTS.any { path.endsWith(it) } || low.contains("lanzou")
    }

    private fun isAdLink(absUrl: String): Boolean {
        return try {
            AdBlocker.isAd(absUrl)
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun resolveUrl(base: String, href: String): String? {
        return try {
            val resolved = URI(base).resolve(href.trim()).toString()
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun cleanText(raw: String): String {
        return raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(60)
    }
}
