package com.github.tvbox.osc.reading

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class LegadoUrlOptions(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val charset: String? = null,
    val webView: Boolean = false,
)

data class LegadoResponse(
    val url: String,
    val body: String,
    val webView: Boolean = false,
    val code: Int = 200,
)

/**
 * Legado URL semantics: `{{key}}` / `{{page}}` / `{{rule or js}}` templates,
 * a whole-URL `@js:` script, a trailing `@js:` post-processor, and the
 * `,{...}` option object (method / headers / body / charset / webView)
 * after the last top-level comma.
 */
object AnalyzeUrl {

    private val OPTION_KEYS = setOf(
        "method", "headers", "body", "charset", "webView", "type", "retry",
    )

    fun splitOptions(raw: String): Pair<String, LegadoUrlOptions> {
        val trimmed = raw.trim()
        var depth = 0
        var inSingle = false
        var inDouble = false
        var commaAt = -1
        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (inSingle) {
                if (c == '\'') inSingle = false
            } else if (inDouble) {
                if (c == '\\') i++
                else if (c == '"') inDouble = false
            } else when (c) {
                '\'' -> inSingle = true
                '"' -> inDouble = true
                '{', '[', '(' -> depth++
                '}', ']', ')' -> depth--
                ',' -> if (depth == 0) commaAt = i
            }
            i++
        }
        if (commaAt > 0) {
            val tail = trimmed.substring(commaAt + 1).trim()
            if (tail.startsWith("{") && tail.endsWith("}")) {
                val obj = LenientJson.parseObjectLenient(tail)
                val keys = obj.keys().asSequence().toList()
                if (keys.any { it in OPTION_KEYS }) {
                    return trimmed.substring(0, commaAt) to parseOptions(obj)
                }
            }
        }
        return trimmed to LegadoUrlOptions()
    }

    private fun parseOptions(obj: JSONObject): LegadoUrlOptions {
        val headers = LinkedHashMap<String, String>()
        val headerObj = when (val h = obj.opt("headers")) {
            is JSONObject -> h
            is String -> LenientJson.parseObjectLenient(h)
            else -> null
        }
        headerObj?.let {
            val keys = it.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                headers[k] = it.optString(k)
            }
        }
        val bodyRaw = obj.opt("body")
        val body = when (bodyRaw) {
            null, JSONObject.NULL -> null
            is String -> bodyRaw
            else -> bodyRaw.toString()
        }
        return LegadoUrlOptions(
            method = obj.optString("method", "GET").uppercase(),
            headers = headers,
            body = body,
            charset = obj.optString("charset").takeIf { it.isNotBlank() },
            webView = obj.optBoolean("webView", false) ||
                obj.optString("webView").equals("true", ignoreCase = true),
        )
    }

    /**
     * Build the final URL text from a rule URL string: `@js:` whole-URL
     * scripts, template makeup ({{key}} etc. come from the session), then a
     * trailing `@js:` transform whose `result` is the URL built so far.
     */
    fun build(session: AnalyzeRule, ruleText: String): String {
        var text = ruleText.trim()
        if (text.startsWith("@js:")) {
            return session.js.evalString(text.substring(4), null).trim()
        }
        // trailing @js: segment (possibly after a newline)
        val jsIdx = findTrailingJs(text)
        var jsTail: String? = null
        if (jsIdx >= 0) {
            jsTail = text.substring(jsIdx + 4)
            text = text.substring(0, jsIdx).trimEnd()
        }
        var built = session.makeUpUrl(text)
        if (jsTail != null) {
            built = session.js.evalString(jsTail, built).trim()
        }
        return built
    }

    private fun findTrailingJs(text: String): Int {
        // "@js:" at start handled by caller; find a later top-level one.
        var idx = text.indexOf("@js:")
        while (idx >= 0) {
            if (idx > 0) return idx
            idx = text.indexOf("@js:", idx + 1)
        }
        return -1
    }

    fun encodeKey(key: String): String =
        runCatching { URLEncoder.encode(key, "UTF-8").replace("+", "%20") }
            .getOrDefault(key)
}

/** OkHttp fetcher carrying a source's static headers. */
class LegadoFetcher(source: BookSource) {

    private val sourceHeaders: Map<String, String> = run {
        val map = LinkedHashMap<String, String>()
        source.header?.let { headerText ->
            val obj = LenientJson.parseObjectLenient(headerText)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k)
            }
        }
        map
    }

    fun getSimple(url: String): String = execute(url, LegadoUrlOptions()).body

    fun postSimple(url: String, body: String): String =
        execute(url, LegadoUrlOptions(method = "POST", body = body)).body

    fun execute(rawUrl: String, options: LegadoUrlOptions): LegadoResponse {
        // A default options object means the caller handed us a raw rule
        // URL that may still carry its `,{...}` option tail.
        val (url, split) = if (options == LegadoUrlOptions()) {
            AnalyzeUrl.splitOptions(rawUrl)
        } else {
            rawUrl to options
        }
        return executeResolved(url.trim(), split)
    }

    private fun executeResolved(url: String, options: LegadoUrlOptions): LegadoResponse {
        val builder = Request.Builder().url(url)
        val merged = LinkedHashMap<String, String>()
        merged["User-Agent"] = DEFAULT_UA
        merged.putAll(sourceHeaders)
        merged.putAll(options.headers)
        for ((k, v) in merged) builder.header(k, v)
        if (options.method.equals("POST", ignoreCase = true)) {
            val bodyText = options.body ?: ""
            val headerType = merged.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            val baseType = when {
                headerType != null -> headerType.substringBefore(';').trim()
                bodyText.trimStart().startsWith("{") -> "application/json"
                else -> "application/x-www-form-urlencoded"
            }
            // A rule charset (e.g. GBK sources) governs how the body is
            // encoded on the wire, not just how the response is decoded.
            val contentType = when {
                options.charset != null -> "$baseType; charset=${options.charset}"
                headerType != null -> headerType
                else -> "$baseType; charset=utf-8"
            }
            builder.post(bodyText.toRequestBody(contentType.toMediaTypeOrNull()))
        }
        val response = client.newCall(builder.build()).execute()
        // Chapter URLs of audio sources often ARE the media file (their
        // content rule reads @js:baseUrl, not the body): never download a
        // whole episode just to hand its URL to the player.
        val responseType = response.header("Content-Type").orEmpty().lowercase()
        val bytes = if (responseType.startsWith("audio/") ||
            responseType.startsWith("video/") ||
            responseType.startsWith("image/")
        ) {
            response.body?.close()
            ByteArray(0)
        } else {
            response.body?.bytes() ?: ByteArray(0)
        }
        val charsetName = options.charset
            ?: response.header("Content-Type")?.let { ct ->
                Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1)
            }
            ?: "UTF-8"
        val body = runCatching { String(bytes, charset(charsetName)) }
            .getOrElse { String(bytes, Charsets.UTF_8) }
        return LegadoResponse(
            url = response.request.url.toString(),
            body = body,
            webView = options.webView,
            code = response.code,
        )
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(LegadoCookies)
            .build()
    }
}

/**
 * In-memory cookie store for the reading fetcher: login-walled sources
 * set cookies during search/info that later chains (and
 * `java.getCookie`) rely on. Cookies live for the process lifetime,
 * keyed by host, exactly like a browser session without persistence.
 */
object LegadoCookies : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { ArrayList() }
        synchronized(list) {
            for (cookie in cookies) {
                list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) list.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Cookie>()
        for ((_, list) in store) {
            synchronized(list) {
                val it = list.iterator()
                while (it.hasNext()) {
                    val cookie = it.next()
                    if (cookie.expiresAt <= now) it.remove()
                    else if (cookie.matches(url)) out.add(cookie)
                }
            }
        }
        return out
    }

    /** `name=value; name2=value2` for [url], Legado getCookie() form. */
    fun cookieString(url: String): String {
        val httpUrl = runCatching { url.toHttpUrl() }.getOrNull() ?: return ""
        return loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun cookieValue(url: String, key: String): String? {
        val httpUrl = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
        return loadForRequest(httpUrl).firstOrNull { it.name == key }?.value
    }
}
