package com.github.tvbox.osc.miniapp

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object MiniAppImporter {

    enum class Kind { EXPIRED, NETWORK, PARSE, TITLE_EMPTY }

    sealed interface Result {
        data class Ok(val item: MiniAppItem) : Result
        data class Err(val kind: Kind) : Result
    }

    private const val UA = "Mozilla/5.0"

    // logtest44 precise algorithm (AutoImportHelper.realCheckAutoText):
    // top-level parsing is ￥ segmentation, not @@. The 20 identifiers
    // below are the switch cases verified in the decompiled helper.
    // @@ is only meaningful inside home_sub / page_detail content.
    private val KNOWN_IDS = setOf(
        "home_rule",
        "home_rule_v2",
        "home_rule_url",
        "home_sub",
        "page_detail",
        "ad_url_rule",
        "web-proxy",
        "bookmark",
        "bookmark_url",
        "publish_account",
        "projection_screen",
        "require_url",
        "publish",
        "ad_block_url",
        "search_engine_v2",
        "file_url",
        "search_engine_url",
        "ad_subscribe_url",
        "js_url",
        "xiu_tan",
    )

    // StringUtil.trimBlanks: strips only \n \r \f \t from both ends.
    private fun trimBlanks(s: String): String =
        s.trim { it == '\n' || it == '\r' || it == '\u000C' || it == '\t' }

    private fun normalize(s: String): String = trimBlanks(s.trim())

    private fun isWrappedJsonCandidate(t: String): Boolean =
        t.startsWith("{") && t.endsWith("}") &&
            t.contains("col_type") && t.contains("find_rule")

    suspend fun resolve(input: String): Result = withContext(Dispatchers.IO) {
        try {
            val trimmed = input.trim()
            when {
                trimmed.startsWith("rule://") -> {
                    val payload = trimmed.removePrefix("rule://").replace("\n", "").trim()
                    val decoded = Base64.decode(payload, Base64.NO_WRAP).toString(Charsets.UTF_8)
                    parseShareText(decoded, trimmed)
                }
                trimmed.startsWith("{") -> {
                    if (isWrappedJsonCandidate(trimmed)) {
                        parseSlogan(
                            "海阔视界规则分享，当前分享的是：小程序￥home_rule￥$trimmed",
                            trimmed,
                        )
                    } else {
                        // AVBox extension, kept from 43: a directly pasted
                        // rule JSON (no col_type/find_rule) still imports.
                        parseRuleJson(trimmed, trimmed)
                    }
                }
                trimmed.contains("￥") -> parseShareText(trimmed, trimmed)
                else -> {
                    var code = trimmed.lineSequence().firstOrNull().orEmpty().trim()
                    // Cloud-code suffixes ("小程序：…" / "插件：…" second line
                    // or trailing note) are stripped before dispatch.
                    for (marker in listOf("小程序：", "插件：")) {
                        if (code.contains(marker)) code = code.substringBefore(marker).trim()
                    }
                    when {
                        code.startsWith("云5oooole") -> fetchCmd(code.removePrefix("云5oooole").trim().trimStart('/').trim(), code)
                        code.startsWith("云6oooole") -> fetchBailan(code)
                        code.startsWith("http://") || code.startsWith("https://") -> fetchUrl(code)
                        else -> Result.Err(Kind.PARSE)
                    }
                }
            }
        } catch (e: IOException) {
            Result.Err(Kind.NETWORK)
        } catch (e: Exception) {
            Result.Err(Kind.PARSE)
        }
    }

    private fun fetchCmd(id: String, source: String): Result {
        if (id.isEmpty()) return Result.Err(Kind.PARSE)
        val conn = (URL("https://cmd.im/$id").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", UA)
        }
        return try {
            val code = conn.responseCode
            if (code in 300..399) return Result.Err(Kind.EXPIRED)
            if (code != HttpURLConnection.HTTP_OK) return Result.Err(Kind.NETWORK)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val text = extractTestBox(body)
            if (text.isBlank()) Result.Err(Kind.EXPIRED) else parseShareText(text, source)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchBailan(code: String): Result {
        val id = code.removePrefix("云6oooole").trim().trimStart('/').removePrefix("xxxxxx/").trim()
        if (id.isEmpty()) return Result.Err(Kind.PARSE)
        val conn = (URL("https://pasteme.tyrantg.com/api/getContent/$id").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://pasteme.tyrantg.com/")
        }
        return try {
            val httpCode = conn.responseCode
            if (httpCode == HttpURLConnection.HTTP_NOT_FOUND) return Result.Err(Kind.EXPIRED)
            if (httpCode != HttpURLConnection.HTTP_OK) return Result.Err(Kind.NETWORK)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val data = JSONObject(body).optString("data")
            if (data.isBlank()) Result.Err(Kind.EXPIRED) else parseShareText(data, code)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchUrl(url: String): Result {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", UA)
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return Result.Err(Kind.NETWORK)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (body.isBlank()) Result.Err(Kind.PARSE) else parseShareText(body, url)
        } finally {
            conn.disconnect()
        }
    }

    internal fun extractTestBox(html: String): String {
        val regex = Regex(
            "<[^>]*class=\"[^\"]*test_box[^\"]*\"[^>]*>(.*?)</",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val raw = regex.find(html)?.groupValues?.getOrNull(1) ?: return ""
        return unescapeHtml(raw).trim()
    }

    private fun unescapeHtml(raw: String): String =
        raw.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&amp;", "&")

    // Top-level entry for fetched / pasted share text. ￥ segmentation
    // comes first; a bare @@ payload with no ￥ is no longer accepted
    // at this level (@@ only lives inside home_sub/page_detail content).
    internal fun parseShareText(text: String, source: String): Result {
        val t = normalize(text)
        if (isWrappedJsonCandidate(t)) {
            return parseSlogan(
                "海阔视界规则分享，当前分享的是：小程序￥home_rule￥$t",
                source,
            )
        }
        if (t.contains("￥")) return parseSlogan(t, source)
        // AVBox extension, kept from 43: a directly pasted / linked
        // bare rule JSON still imports.
        if (t.startsWith("{")) return parseRuleJson(t, source)
        return Result.Err(Kind.PARSE)
    }

    private fun parseSlogan(text: String, source: String): Result {
        val parts = normalize(text).split("￥")
        if (parts.size < 3) return Result.Err(Kind.PARSE)
        val id = parts[1].trim()
        if (id !in KNOWN_IDS) return Result.Err(Kind.PARSE)
        val content = normalize(parts.drop(2).joinToString("￥"))
        if (content.isEmpty()) return Result.Err(Kind.PARSE)
        return parseSloganContent(id, content, source)
    }

    private fun parseSloganContent(id: String, content: String, source: String): Result {
        if (id == "home_rule" || id == "home_rule_v2") {
            if (content.startsWith("base64://")) {
                val atParts = content.split("@")
                if (atParts.size < 3) return Result.Err(Kind.PARSE)
                val b64 = atParts[2].replace("\n", "").trim()
                if (b64.isEmpty()) return Result.Err(Kind.PARSE)
                return try {
                    val decoded = Base64.decode(b64, Base64.NO_WRAP).toString(Charsets.UTF_8).trim()
                    if (decoded.startsWith("{")) parseRuleJson(decoded, source)
                    else Result.Err(Kind.PARSE)
                } catch (e: Exception) {
                    Result.Err(Kind.PARSE)
                }
            }
            // Otherwise the content itself is JSON.
            if (content.startsWith("{")) return parseRuleJson(content, source)
            return Result.Err(Kind.PARSE)
        }
        // Other allowlisted identifiers (AVBox extension): Haikuo would
        // import these as other rule types; AVBox only has the mini-app
        // slot, so a JSON content, or a @@ payload inside the content
        // (the page_detail / home_sub shape), is extracted when present.
        if (content.startsWith("{")) return parseRuleJson(content, source)
        if (content.contains("@@")) {
            val payload = content.substringAfter("@@").trim()
            if (payload.isEmpty()) return Result.Err(Kind.PARSE)
            return try {
                val decoded = Base64.decode(payload, Base64.DEFAULT).toString(Charsets.UTF_8).trim()
                if (decoded.startsWith("{")) parseRuleJson(decoded, source)
                else Result.Err(Kind.PARSE)
            } catch (e: Exception) {
                Result.Err(Kind.PARSE)
            }
        }
        return Result.Err(Kind.PARSE)
    }

    internal fun parseRuleJson(json: String, source: String): Result {
        val obj = JSONObject(json)
        val title = obj.optString("title").trim()
        // Empty title is a silent failure in Haikuo; in AVBox it gets
        // its own kind so the prompt is distinct from decode/JSON failure.
        if (title.isEmpty()) return Result.Err(Kind.TITLE_EMPTY)
        return Result.Ok(
            MiniAppItem(
                name = title,
                icon = obj.optString("icon").trim(),
                source = source,
                category = "",
                ruleJson = json,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }
}
