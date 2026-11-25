package com.github.tvbox.osc.miniapp.engine

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object MiniAppUa {
    const val MOBILE = "Mozilla/5.0 (Linux; Android 15; 2407FRK8EC Build/AP3A.240617.008; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36"
    const val PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    fun byCode(code: String?): String {
        return when (code) {
            "mobile" -> MOBILE
            "pc" -> PC
            else -> code ?: ""
        }
    }
}

data class MiniAppHttpResult(
    val body: String,
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val headersList: Map<String, List<String>> = emptyMap(),
)

class MiniAppCookieJar : CookieJar {
    private val store = HashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { ArrayList() }
        for (c in cookies) {
            list.removeAll { it.name == c.name }
            list.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val list = store[url.host] ?: return emptyList()
        return list.filter { it.expiresAt >= now }
    }

    @Synchronized
    fun headerFor(url: okhttp3.HttpUrl): String {
        val now = System.currentTimeMillis()
        val list = store[url.host] ?: return ""
        return list.filter { it.expiresAt >= now }.joinToString("; ") { "${it.name}=${it.value}" }
    }
}

object MiniAppHttp {

    const val MAX_BODY_BYTES = 8 * 1024 * 1024
    const val CONNECT_TIMEOUT_MS = 15000L
    const val READ_TIMEOUT_MS = 15000L

    var allowPrivateNetwork: Boolean = false

    private val clients = HashMap<String, OkHttpClient>()

    @Synchronized
    fun clientFor(ruleTitle: String): OkHttpClient {
        return clients.getOrPut(ruleTitle) {
            OkHttpClient.Builder()
                .cookieJar(MiniAppCookieJar())
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    fun clearClient(ruleTitle: String) {
        synchronized(clients) {
            clients.remove(ruleTitle)
        }
    }

    fun cookieHeader(ruleTitle: String, url: String): String {
        return try {
            val httpUrl = url.toHttpUrlOrNull() ?: return ""
            val jar = clientFor(ruleTitle).cookieJar
            if (jar is MiniAppCookieJar) jar.headerFor(httpUrl) else ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun fetchCookieHeader(url: String, headers: Map<String, String>): String {
        return try {
            val client = OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val builder = okhttp3.Request.Builder().url(url)
            for ((k, v) in headers) builder.header(k, v)
            client.newCall(builder.build()).execute().use { resp ->
                resp.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(";").trim().takeIf { s -> s.contains("=") } }
                    .joinToString("; ")
            }
        } catch (e: Throwable) {
            ""
        }
    }

    fun checkPublicUrl(url: String) {
        val parsed = try {
            java.net.URL(url)
        } catch (e: Throwable) {
            throw IllegalArgumentException("bad url: $url")
        }
        val scheme = parsed.protocol.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw SecurityException("scheme not allowed: $scheme")
        }
        if (allowPrivateNetwork) return
        val host = parsed.host ?: return
        try {
            val addr = InetAddress.getByName(host)
            if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
                throw SecurityException("private network blocked: $host")
            }
            val ip = addr.hostAddress ?: return
            if (ip.startsWith("10.") || ip.startsWith("192.168.") ||
                ip.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*"))
            ) {
                throw SecurityException("private network blocked: $host")
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Throwable) {
        }
    }

    fun fetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        formParams: Map<String, String>? = null,
        rawBody: String? = null,
        rawBodyMediaType: String? = null,
        charset: String = "UTF-8",
        ruleTitle: String = "",
        followRedirects: Boolean = true,
        timeoutMs: Long = READ_TIMEOUT_MS,
    ): MiniAppHttpResult {
        checkPublicUrl(url)
        val base = clientFor(ruleTitle)
        val client = if (followRedirects == base.followRedirects) {
            base
        } else {
            base.newBuilder().followRedirects(followRedirects).followSslRedirects(followRedirects).build()
        }
        val builder = Request.Builder().url(url)
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        if (ua == null) {
            builder.header("User-Agent", MiniAppUa.MOBILE)
        }
        for ((k, v) in headers) {
            if (k.equals("User-Agent", ignoreCase = true)) {
                builder.header("User-Agent", v)
            } else if (k.equals("Cookie", ignoreCase = true)) {
                builder.header("Cookie", v)
            } else {
                builder.header(k, v)
            }
        }
        if (method.equals("POST", ignoreCase = true)) {
            val body = when {
                formParams != null -> {
                    val fb = FormBody.Builder()
                    for ((k, v) in formParams) fb.add(k, v)
                    fb.build()
                }
                rawBody != null -> rawBody.toRequestBody(
                    (rawBodyMediaType ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()
                )
                else -> FormBody.Builder().build()
            }
            builder.post(body)
        } else {
            builder.get()
        }
        val call = client.newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())
        val response = call.execute()
        response.use { resp ->
            val raw = resp.body?.bytes() ?: ByteArray(0)
            val bytes = if (raw.size > MAX_BODY_BYTES) raw.copyOf(MAX_BODY_BYTES) else raw
            val cs = try {
                if (charset.isBlank() || charset.equals("UTF-8", ignoreCase = true) || charset == "*") {
                    Charsets.UTF_8
                } else {
                    charset(charset)
                }
            } catch (e: Throwable) {
                Charsets.UTF_8
            }
            val respHeaders = HashMap<String, String>()
            for (name in resp.headers.names()) {
                respHeaders[name] = resp.header(name) ?: ""
            }
            return MiniAppHttpResult(String(bytes, cs), resp.code, respHeaders, resp.headers.toMultimap())
        }
    }

    fun encodeUrl(s: String?, charset: String?): String? {
        if (s == null) return null
        if (charset.isNullOrEmpty() || charset.equals("UTF-8", ignoreCase = true) || charset == "*") {
            return s
        }
        return try {
            URLEncoder.encode(s, charset)
        } catch (e: Throwable) {
            s
        }
    }

    fun encodeUrl1(s: String?, charset: String?): String? {
        if (s == null) return null
        if (charset.isNullOrEmpty() || charset == "*") {
            return s
        }
        return try {
            URLEncoder.encode(s, charset)
        } catch (e: Throwable) {
            s
        }
    }

    fun encodeUrlUtf8(s: String?): String? {
        if (s == null) return null
        return try {
            URLEncoder.encode(s, "UTF-8")
        } catch (e: Throwable) {
            s
        }
    }

    fun decodeUrl(s: String?, charset: String?): String? {
        if (s == null) return null
        return try {
            URLDecoder.decode(
                s.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25").replace("\\+", "%2B"),
                charset ?: "UTF-8",
            )
        } catch (e: Throwable) {
            s
        }
    }

    fun replaceKey(template: String?, encodedKeyword: String?): String? {
        if (template.isNullOrEmpty() || encodedKeyword.isNullOrEmpty()) return template
        return if (template.contains("%%")) {
            template.replace("%%", encodedKeyword)
        } else {
            template.replace("**", encodedKeyword)
        }
    }

    fun parseSearchUrl(template: String, keyword: String, keepTemplate: Boolean = false): String {
        val split = template.split(";")
        if (split.size == 1) {
            return replaceKey(template, encodeUrl1(keyword, "UTF-8")) ?: template
        }
        if (split.size == 2) {
            val enc = if (split[1].equals("get", ignoreCase = true) || split[1] == "*" ||
                split[1].equals("post", ignoreCase = true)
            ) {
                encodeUrl1(keyword, "UTF-8")
            } else {
                encodeUrl1(keyword, split[1])
            }
            val base = if (!keepTemplate) split[0] else template
            return replaceKey(base, enc) ?: base
        }
        val enc = encodeUrl1(keyword, split[2])
        val base = if (!keepTemplate) split[0] else template
        return replaceKey(base, enc) ?: base
    }

    data class SearchRequest(
        val method: String,
        val url: String,
        val charset: String?,
        val headers: Map<String, String>?,
        val formParams: Map<String, String>?,
    )

    fun parseSearchUrlForHtml(
        keyword: String,
        searchUrl: String,
        jsEval: ((String, String) -> String)? = null,
    ): SearchRequest {
        val split = searchUrl.split(";")
        if (split.size == 1) {
            val url = parseParamsByJs(replaceKey(split[0], encodeUrl(keyword, "UTF-8")) ?: split[0], jsEval)
            return SearchRequest("GET", url, null, null, null)
        }
        if (split.size == 2) {
            if (split[1].equals("get", ignoreCase = true) || split[1] == "*") {
                val url = parseParamsByJs(replaceKey(split[0], encodeUrl(keyword, "UTF-8")) ?: split[0], jsEval)
                return SearchRequest("GET", url, null, getHeaders(searchUrl, jsEval), null)
            }
            if (split[1].equals("post", ignoreCase = true)) {
                val url = parseParamsByJs(replaceKey(split[0], encodeUrl(keyword, "UTF-8")) ?: split[0], jsEval)
                return SearchRequest("POST", url, null, getHeaders(searchUrl, jsEval), formParamsOf(url))
            }
            var charset = split[1]
            if (charset.startsWith("{") && charset.endsWith("}")) {
                charset = "utf-8"
            }
            val url = parseParamsByJs(replaceKey(split[0], encodeUrl(keyword, charset)) ?: split[0], jsEval)
            return SearchRequest("GET", url, charset, getHeaders(searchUrl, jsEval), null)
        }
        var enc = encodeUrl(keyword, split[2])
        if (split[1].equals("post", ignoreCase = true)) {
            if (split[2].isNotEmpty() && !split[2].equals("utf-8", ignoreCase = true)) {
                enc = decodeUrl(enc, split[2])
            }
            val url = parseParamsByJs(replaceKey(split[0], enc) ?: split[0], jsEval)
            return SearchRequest("POST", url, split[2], getHeaders(searchUrl, jsEval), formParamsOf(url))
        }
        val url = parseParamsByJs(replaceKey(split[0], enc) ?: split[0], jsEval)
        return SearchRequest("GET", url, split[2], getHeaders(searchUrl, jsEval), null)
    }

    fun formParamsOf(urlWithQuery: String): Map<String, String> {
        val parts = MiniAppText.splitUrlByQuestionMark(urlWithQuery)
        if (parts.size <= 1) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in parts[1].split("&")) {
            if (pair.isEmpty()) continue
            val kv = pair.split("=")
            if (kv.size >= 2) {
                out[kv[0]] = MiniAppText.arrayToString(kv.toTypedArray(), 1, "=")
            }
        }
        return out
    }

    fun formUrlOf(urlWithQuery: String): String {
        return MiniAppText.splitUrlByQuestionMark(urlWithQuery)[0]
    }

    fun parseParamsByJs(url: String, jsEval: ((String, String) -> String)?): String {
        var result = url
        val qParts = MiniAppText.splitUrlByQuestionMark(result)
        if (qParts.size > 1) {
            val pairs = qParts[1].split("&")
            val rewritten = ArrayList<String>()
            for (pair in pairs) {
                val kv = pair.split("=")
                if (kv.size > 1) {
                    var value = MiniAppText.arrayToString(kv.toTypedArray(), 1, "=")
                    val jsSplit = value.split("\\.js:")
                    if (jsSplit.size > 1 && jsEval != null) {
                        value = try {
                            jsEval.invoke(jsSplit[1], jsSplit[0])
                        } catch (e: Throwable) {
                            value
                        }
                    }
                    rewritten.add(kv[0] + "=" + value)
                } else {
                    rewritten.add(pair)
                }
            }
            result = qParts[0] + "?" + MiniAppText.listToString(rewritten, "&")
        }
        val pathParts = result.split("?")
        val fixed = pathParts.toMutableList()
        fixed[0] = getUrlByJs(fixed[0], jsEval)
        return MiniAppText.arrayToString(fixed.toTypedArray(), 0, "?")
    }

    private fun getUrlByJs(url: String, jsEval: ((String, String) -> String)?): String {
        val split = url.split("\\.js:")
        if (split.size > 1 && jsEval != null) {
            return try {
                jsEval.invoke(split[1], split[0])
            } catch (e: Throwable) {
                url
            }
        }
        return url
    }

    fun parseHeadersByJs(headers: String, jsEval: ((String, String) -> String)?): String {
        val parts = headers.split("&&")
        val out = ArrayList<String>()
        for (part in parts) {
            val kv = part.split("@")
            if (kv.size > 1) {
                val jsSplit = kv[1].split("\\.js:")
                var value = kv[1]
                if (jsSplit.size > 1 && jsEval != null) {
                    value = try {
                        jsEval.invoke(jsSplit[1], jsSplit[0])
                    } catch (e: Throwable) {
                        value
                    }
                }
                out.add(kv[0] + "@" + value)
            } else {
                out.add(part)
            }
        }
        return MiniAppText.listToString(out, "&&")
    }

    fun getHeaders(url: String, jsEval: ((String, String) -> String)? = null): Map<String, String>? {
        if (url.isEmpty()) return null
        if (url.startsWith("{") && url.endsWith("}")) return null
        val split = url.split(";")
        val last = split[split.size - 1]
        if (last.startsWith("{") && last.endsWith("}")) {
            val decoded = MiniAppText.decodeConflictStr(last) ?: last
            val inner = decoded.substring(1, decoded.length - 1)
            val parsed = parseHeadersByJs(inner, jsEval)
            val map = HashMap<String, String>()
            for (entry in parsed.split("&&")) {
                val kv = entry.split("@")
                if (kv.size >= 2) {
                    if (kv[1] == "getTimeStamp()") {
                        map[kv[0]] = System.currentTimeMillis().toString()
                    } else {
                        map[kv[0]] = kv[1]
                    }
                }
            }
            return map
        }
        return null
    }

    fun getHeadersStr(map: Map<String, String>?): String {
        if (map.isNullOrEmpty()) return "{}"
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append("&&")
            sb.append(k).append("@").append(v.replace(";", "；；"))
            first = false
        }
        sb.append("}")
        return sb.toString()
    }

    fun getRealUrlFilterHeaders(url: String): String {
        if (url.isEmpty()) return ""
        val split = url.split(";")
        val last = split[split.size - 1]
        return if (last.startsWith("{") && last.endsWith("}")) split[0] else url
    }

    data class PlayData(
        val url: String = "",
        val urls: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val headers: List<Map<String, String>> = emptyList(),
    )

    fun getPlayData(raw: String): PlayData {
        if (raw.isEmpty()) return PlayData()
        if (raw.startsWith("{") && raw.endsWith("}")) {
            try {
                val j = org.json.JSONObject(raw)
                val urls = ArrayList<String>()
                val urlArr = j.optJSONArray("urls")
                if (urlArr != null) {
                    for (i in 0 until urlArr.length()) urls.add(urlArr.optString(i))
                }
                val names = ArrayList<String>()
                val nameArr = j.optJSONArray("names")
                if (nameArr != null) {
                    for (i in 0 until nameArr.length()) names.add(nameArr.optString(i))
                }
                val finalNames = when {
                    urls.isNotEmpty() && names.isEmpty() -> (1..urls.size).map { "线路$it" }
                    names.size > urls.size -> names.subList(0, urls.size)
                    else -> names
                }
                return PlayData(url = j.optString("url"), urls = urls, names = finalNames)
            } catch (e: Throwable) {
                return PlayData(url = raw)
            }
        }
        return PlayData(url = raw.split(";")[0])
    }

    fun getPlayUrlWithHeader(raw: String): String {
        val data = getPlayData(raw)
        val posUrl = if (data.urls.isNotEmpty()) data.urls[0] else raw
        val split = raw.split(";")
        return if (split.size <= 1) posUrl else posUrl + ";" + MiniAppText.arrayToString(split.toTypedArray(), 1, ";")
    }

    fun getUrlAppendUA(url: String, uaCode: String): String {
        if (uaCode.isEmpty() || uaCode == "auto") return url
        val split = url.split(";")
        if (split.size == 1) return "$url;get;UTF-8;{User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}}"
        if (split.size == 2) return "$url;UTF-8;{User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}}"
        if (split.size == 3) return "$url;{User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}}"
        if (url.contains("User-Agent@")) return url
        val last = split[split.size - 1]
        if (!last.startsWith("{") || !last.endsWith("}")) return url
        val inner = last.substring(0, last.length - 1)
        if (inner.length == 1) {
            return MiniAppText.arrayToString(split.toTypedArray(), 0, split.size - 1, ";") +
                ";{User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}}"
        }
        return MiniAppText.arrayToString(split.toTypedArray(), 0, split.size - 1, ";") +
            ";" + (inner + "&&User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}}")
    }

    fun getUrlReplaceUA(url: String, uaCodeInput: String): String {
        if (uaCodeInput.isEmpty()) return url
        val uaCode = if (uaCodeInput == "auto") "" else uaCodeInput
        val split = url.split(";")
        if (split.size == 1) return "$url;get;UTF-8;{User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}}"
        if (split.size == 2) return "$url;UTF-8;{User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}}"
        if (split.size == 3) return "$url;{User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}}"
        if (url.contains("User-Agent@")) {
            val last = split[split.size - 1]
            if (!last.startsWith("{") || !last.endsWith("}")) return url
            val inner = last.substring(1, last.length - 1)
            if (inner.isEmpty()) {
                return MiniAppText.arrayToString(split.toTypedArray(), 0, split.size - 1, ";") +
                    ";{User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}}"
            }
            val parts = inner.split("&&").toMutableList()
            for (i in parts.indices) {
                if (parts[i].startsWith("User-Agent@")) {
                    parts[i] = "User-Agent@${MiniAppUa.byCode(uaCode).replace(";", "；；")}"
                    break
                }
            }
            return MiniAppText.arrayToString(split.toTypedArray(), 0, split.size - 1, ";") +
                ";{" + MiniAppText.listToString(parts, "&&") + "}"
        }
        return getUrlAppendUA(url, uaCodeInput)
    }

    fun replaceQuery(url: String, key: String, value: String): String {
        if (url.isEmpty()) return url
        val qParts = MiniAppText.splitUrlByQuestionMark(url.split(";")[0])
        if (qParts.size > 1) {
            val map = LinkedHashMap<String, String>()
            for (pair in qParts[1].split("&")) {
                if (pair.isEmpty()) continue
                val kv = pair.split("=")
                if (kv.size >= 2) {
                    map[kv[0]] = MiniAppText.decodeConflictStr(MiniAppText.arrayToString(kv.toTypedArray(), 1, "=")) ?: ""
                }
            }
            val sb = StringBuilder(qParts[0]).append("?")
            var first = true
            for ((k, v) in map) {
                if (!first) sb.append("&")
                if (k == key) sb.append(k).append("=").append(value) else sb.append(k).append("=").append(v)
                first = false
            }
            return sb.toString()
        }
        return "$url?$key=$value"
    }

    fun getParamsByUrl(url: String): Map<String, String> {
        if (url.isEmpty()) return HashMap()
        val split = url.split(";")
        val map = HashMap<String, String>()
        val qParts = MiniAppText.splitUrlByQuestionMark(split[0])
        val pairs = if (qParts.size > 1) qParts[1].split("&") else emptyList()
        for (pair in pairs) {
            if (pair.isEmpty()) continue
            val kv = pair.split("=")
            if (kv.size >= 2) {
                map[kv[0]] = MiniAppText.decodeConflictStr(MiniAppText.arrayToString(kv.toTypedArray(), 1, "=")) ?: ""
            }
        }
        return map
    }

    fun getCode(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val split = url.split(";")
        val code = if (split.size >= 3) split[2] else null
        return if (code == "*") "UTF-8" else code
    }

    fun getThirdDownloadSource(url: String): String {
        if (url.isEmpty()) return url
        return url.replace("download://", "").split(";")[0].split("##")[0]
    }
}
