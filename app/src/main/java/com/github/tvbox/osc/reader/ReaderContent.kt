package com.github.tvbox.osc.reader

import android.util.Base64
import com.github.tvbox.osc.sourcedata.SourceHelper
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.LOG
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.Locale

/**
 * Reader content classification for spider `playerContent` results.
 *
 * The de-facto contracts found in real CatVod spiders (verified against the
 * user's own spider tree):
 *
 * COMIC (verified: ACGManHua / LiLiManHua / NanNanManHua / Hahahaman):
 *   {"parse":0, "url":"pics://<img1>&&<img2>&&...", "header":"{UA/Referer}"}
 *   - the episode id itself may already be a pics:// payload (detail-time
 *     pre-resolution, ACGManHua passes it straight through), or the player
 *     result url may be one single image URL.
 *   - some sources return the page list as a plain JSON array string, or a
 *     newline / '#' / '|' separated list; image entries may carry the
 *     app-wide pic suffix "@Referer=..@User-Agent=.." (handled by Coil).
 *
 * NOVEL (text chapters):
 *   - explicit fields on the player result: "format" / "type" in
 *     {novel, book, text, txt, xiaoshuo} or inline body text in
 *     "content" / "text" / "novel";
 *   - the url is a data: URI holding text (plain or base64), a direct
 *     .txt link, or a non-video http(s) link when the result itself is
 *     marked "parse":0 and the source is a text source. Body text is
 *     fetched + extracted by ReaderTextFetcher; HTML chapter pages are
 *     paragraph-extracted, plain text is split on blank lines.
 *
 * Anything video-shaped (DefaultConfig.isVideoFormat, parse/jx flows,
 * .m3u8/.mpd etc.) never reaches the readers.
 */
sealed interface ReaderContent {

    val headers: Map<String, String>

    data class Comic(
        val images: List<String>,
        override val headers: Map<String, String>,
    ) : ReaderContent

    data class Novel(
        /** Inline chapter body text, when the spider returns it directly. */
        val inlineText: String?,
        /** Remote body location (txt file / chapter page / data: URI). */
        val textUrl: String?,
        override val headers: Map<String, String>,
    ) : ReaderContent
}

object ReaderContentClassifier {

    private val IMAGE_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif", ".jfif", ".heic", ".heif",
    )

    private val NOVEL_MARKERS = setOf(
        "novel", "book", "text", "txt", "xiaoshuo", "story", "read",
    )

    fun classify(result: JSONObject?): ReaderContent? {
        if (result == null) return null
        val headers = SourceHelper.extractPlayHeaders(result) ?: emptyMap()

        // --- novel: explicit novel:// payload (first rule) ------------------
        // Verified against real py spiders (qimao / zhijinhe _novel_play):
        //   {"parse":0, "url":"novel://" + {"title":..,"content":<full text>}}
        // The whole chapter body rides inline in the url — no fetch, no
        // probe, the reader opens instantly. Before this rule existed the
        // url fell through to the video player, which tried to decode the
        // JSON payload as media and sat on a 0x0 black screen.
        val rawUrl = rawUrlString(result)
        if (rawUrl != null) {
            val novel = parseNovelPayload(rawUrl, headers)
            if (novel != null) return novel
        }

        // --- comic: explicit pics:// payload --------------------------------
        if (rawUrl != null) {
            val pics = parsePicsPayload(rawUrl)
            if (pics != null) return ReaderContent.Comic(pics, headers)
        }

        // --- novel: explicit markers / inline body ---------------------------
        val format = listOf(
            result.optString("format", ""),
            result.optString("type", ""),
            result.optString("contentType", ""),
        ).joinToString(" ").lowercase(Locale.ROOT)
        val inlineText = firstTextField(result)
        if (format.split(Regex("[^a-z]+")).any { it in NOVEL_MARKERS } ||
            result.optBoolean("isNovel", false) || result.optBoolean("novel", false)
        ) {
            return ReaderContent.Novel(inlineText, novelUrl(result, rawUrl), headers)
        }
        if (inlineText != null && inlineText.length >= 40 && !looksLikeMarkup(inlineText)) {
            // Inline body with no other playable url: treat as a chapter.
            if (rawUrl.isNullOrBlank() || !isVideoLike(rawUrl)) {
                return ReaderContent.Novel(inlineText, null, headers)
            }
        }

        if (rawUrl == null) return null

        // --- comic: array / separated list / single image --------------------
        val pieces = splitPieces(rawUrl)
        if (pieces.isNotEmpty()) {
            val imagePieces = pieces.filter { isImageLike(it) }
            if (imagePieces.size == pieces.size && pieces.size >= 2) {
                return ReaderContent.Comic(imagePieces, headers)
            }
            if (pieces.size == 1 && isImageLike(pieces[0])) {
                return ReaderContent.Comic(pieces, headers)
            }
        }

        // --- novel: data: text, .txt link ------------------------------------
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("data:", ignoreCase = true)) {
            val mediaType = trimmed.substring(5).substringBefore(';').substringBefore(',')
                .lowercase(Locale.ROOT)
            if (mediaType.startsWith("text/") || mediaType.contains("json") || mediaType.isEmpty()) {
                return ReaderContent.Novel(null, trimmed, headers)
            }
            if (mediaType.startsWith("image/")) {
                return ReaderContent.Comic(listOf(trimmed), headers)
            }
            return null
        }
        val path = urlPath(trimmed)
        if (path.endsWith(".txt")) {
            return ReaderContent.Novel(null, trimmed, headers)
        }

        // --- novel: chapter page link ---------------------------------------
        // The most common novel shape in the wild: parse=0 and the url is a
        // plain web page whose HTML holds the chapter text. Feeding it to
        // the video pipeline only spins forever, so divert synchronously.
        val parseFlag = result.optString("parse", "")
        val jxFlag = result.optString("jx", "")
        if (parseFlag == "0" && jxFlag != "1" &&
            (path.endsWith(".html") || path.endsWith(".htm") ||
                path.endsWith(".shtml") || path.endsWith(".xhtml"))
        ) {
            return ReaderContent.Novel(null, trimmed, headers)
        }
        return null
    }

    /**
     * True for play results whose url shape stays ambiguous after
     * [classify]: an http(s) link (local proxy addresses included) that is
     * not video-shaped and carries no parser/sniff instruction. Two shapes
     * qualify:
     *  - explicit direct play (`parse` == "0"): the spider hands over a
     *    final address, so a text/html/json Content-Type alone is enough
     *    evidence for the probe to divert ("lenient" mode);
     *  - no `parse` at all: the app only stamps parse=0 onto known video
     *    formats and proxy links (see PlayLoader.normalizePlayerResult),
     *    so a leftover extension-less http link from a lazy spider is
     *    exactly the ambiguous bucket — local proxy chapter endpoints and
     *    novel APIs live here. The probe judges those by the body itself
     *    ("strict" mode), never by Content-Type alone.
     * Explicit parse=1 / jx flows, results carrying their own playUrl
     * parser endpoint, video-shaped urls and already-classified results
     * never probe, so normal video playback keeps zero added latency.
     * Live/music never reaches this code (they register no reader router).
     */
    fun shouldProbe(result: JSONObject?): Boolean {
        if (result == null) return false
        if (result.optString("jx", "") == "1") return false
        val parse = result.optString("parse", "").trim()
        if (parse.isNotEmpty() && parse != "0") return false
        // A result carrying its own parser endpoint is a sniff flow, not
        // a direct link (an explicit parse=0 overrides the endpoint in the
        // player too, so only the parse-absent shape is guarded here).
        if (parse.isEmpty() && result.optString("playUrl", "").isNotBlank()) return false
        val raw = rawUrlString(result) ?: return false
        val url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains(".m3u8") || lower.contains(".mpd")) return false
        if (isVideoLike(url)) return false
        if (runCatching { DefaultConfig.isVideoFormat(url) }.getOrDefault(false)) return false
        val skipExt = listOf(
            ".apk", ".zip", ".rar", ".7z", ".pdf", ".exe", ".bin", ".iso", ".torrent",
        )
        if (skipExt.any { urlPath(url).endsWith(it) }) return false
        return classify(result) == null
    }

    private val probeClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .apply {
                com.github.tvbox.osc.net.OkGoHelper.getDefaultClient()?.dns?.let { dns(it) }
            }
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Fetches the head of an ambiguous link (bounded Range request, ~4s
     * timeouts) and decides from the served Content-Type / body head
     * whether it is reader content. Returns null both for "definitely
     * video/binary" and for any probe failure, so the caller falls back
     * to the video pipeline — a probe can never turn a playable video
     * into a dead end, and a text chapter gets a definite reader outcome
     * instead of a 0x0 black screen. Every decision is logged so a miss
     * can be diagnosed from the float log next round.
     */
    suspend fun probe(result: JSONObject): ReaderContent? {
        val raw = rawUrlString(result)?.trim() ?: return null
        val headers = SourceHelper.extractPlayHeaders(result) ?: emptyMap()
        val strict = result.optString("parse", "").isBlank()
        LOG.i(
            "echo-reader probe start: " + urlLabel(raw) +
                if (strict) " (parse absent, strict)" else " (parse=0)"
        )
        val probed = kotlinx.coroutines.withTimeoutOrNull(6_500L) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { probeHttp(raw, headers, strict) }
                    .onFailure { th ->
                        LOG.i("echo-reader probe failed: " + urlLabel(raw) + " -> " + th.javaClass.simpleName)
                    }
                    .getOrNull()
            }
        }
        if (probed == null) {
            LOG.i("echo-reader probe done: " + urlLabel(raw) + " -> keep video pipeline")
        }
        return probed
    }

    /** scheme://host/path only — query strings may carry tokens. */
    private fun urlLabel(url: String): String {
        val uri = try {
            java.net.URI(url)
        } catch (th: Throwable) {
            return "(bad url)"
        }
        val scheme = uri.scheme ?: return "(bad url)"
        val host = uri.host ?: return "(bad url)"
        val label = scheme + "://" + host + (uri.rawPath ?: "")
        return if (label.length > 120) label.substring(0, 120) + "..." else label
    }

    private fun probeHttp(url: String, headers: Map<String, String>, strict: Boolean): ReaderContent? {
        val builder = okhttp3.Request.Builder().url(url)
        for ((k, v) in headers) {
            runCatching { builder.header(k, v) }
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
            )
        }
        // Only the head of the body is ever judged: bound the transfer.
        if (headers.keys.none { it.equals("Range", ignoreCase = true) }) {
            builder.header("Range", "bytes=0-65535")
        }
        probeClient.newCall(builder.build()).execute().use { response ->
            // Error pages (CDN 403/404 walls etc.) prove nothing about the
            // content kind: never divert on them.
            if (!response.isSuccessful) {
                LOG.i("echo-reader probe: HTTP " + response.code + " for " + urlLabel(url) + ", keep video pipeline")
                return null
            }
            val finalUrl = response.request.url.toString()
            val label = urlLabel(finalUrl)
            val finalLower = finalUrl.lowercase(Locale.ROOT)
            // Redirected onto a playlist: still video pipeline territory.
            if (finalLower.contains(".m3u8") || finalLower.contains(".mpd")) {
                LOG.i("echo-reader probe: " + label + " redirected to a playlist, keep video pipeline")
                return null
            }
            val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
            if (contentType.contains("mpegurl") || contentType.contains("dash+xml")) {
                LOG.i("echo-reader probe: " + label + " is a playlist (" + contentType + "), keep video pipeline")
                return null
            }
            when {
                contentType.startsWith("image/") -> {
                    LOG.i("echo-reader probe: " + label + " image (" + contentType + ") -> comic")
                    return ReaderContent.Comic(listOf(finalUrl), headers)
                }
                contentType.startsWith("video/") || contentType.startsWith("audio/") -> {
                    LOG.i("echo-reader probe: " + label + " media (" + contentType + "), keep video pipeline")
                    return null
                }
            }
            val textFamily = contentType.contains("text/") || contentType.contains("html") ||
                contentType.contains("json") || contentType.contains("xhtml") ||
                contentType.contains("xml")
            if (textFamily && !strict) {
                LOG.i("echo-reader probe: " + label + " text family (" + contentType + ") -> novel")
                return ReaderContent.Novel(null, finalUrl, headers)
            }
            // Strict mode (parse absent) and ambiguous content types are
            // judged by the body head, never by Content-Type alone.
            val peeked = try {
                response.peekBody(65_536).bytes()
            } catch (th: Throwable) {
                ByteArray(0)
            }
            if (peeked.isEmpty()) {
                LOG.i("echo-reader probe: " + label + " empty body head, keep video pipeline")
                return null
            }
            if (isMediaMagic(peeked)) {
                LOG.i("echo-reader probe: " + label + " media magic bytes, keep video pipeline")
                return null
            }
            val text = String(peeked, Charsets.UTF_8)
            val head = text.trimStart()
            if (head.startsWith("#EXTM3U")) {
                LOG.i("echo-reader probe: " + label + " is an m3u8 body, keep video pipeline")
                return null
            }
            // JSON API shape (the fanqie-style chapter endpoints): a long
            // prose string field is decisive in both modes.
            if (ReaderText.extractJsonContent(text) != null) {
                LOG.i("echo-reader probe: " + label + " JSON body with a text field -> novel")
                return ReaderContent.Novel(null, finalUrl, headers)
            }
            if (head.startsWith("{") || head.startsWith("[")) {
                LOG.i("echo-reader probe: " + label + " JSON body without a text field, keep video pipeline")
                return null
            }
            if (head.startsWith("<")) {
                if (!strict) {
                    LOG.i("echo-reader probe: " + label + " HTML body -> novel")
                    return ReaderContent.Novel(null, finalUrl, headers)
                }
                val paragraphChars = ReaderText.extractParagraphs(text).sumOf { it.length }
                if (paragraphChars >= 160) {
                    LOG.i("echo-reader probe: " + label + " HTML with " + paragraphChars + " chars of paragraphs -> novel")
                    return ReaderContent.Novel(null, finalUrl, headers)
                }
                LOG.i("echo-reader probe: " + label + " HTML head too thin (" + paragraphChars + " chars), keep video pipeline")
                return null
            }
            val junk = text.count { ch ->
                ch.code < 0x09 || ch.code in 0x0E..0x1F || ch == '�'
            }
            if (text.isNotEmpty() && junk * 20 < text.length && text.trim().length >= 120) {
                LOG.i("echo-reader probe: " + label + " clean plain text -> novel")
                return ReaderContent.Novel(null, finalUrl, headers)
            }
            LOG.i("echo-reader probe: " + label + " body inconclusive (" + contentType + "), keep video pipeline")
            return null
        }
    }

    private fun isMediaMagic(bytes: ByteArray): Boolean {
        fun at(offset: Int, marker: String): Boolean {
            if (bytes.size < offset + marker.length) return false
            for (j in marker.indices) {
                if (bytes[offset + j] != marker[j].code.toByte()) return false
            }
            return true
        }
        if (at(4, "ftyp")) return true // mp4 / m4a / mov family
        if (at(0, "ID3")) return true // mp3 with tag
        if (at(0, "FLV")) return true // flv
        if (at(0, "RIFF")) return true // avi / wav
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        ) {
            return true // mkv / webm (EBML)
        }
        if (bytes.size >= 376 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()) {
            return true // MPEG-TS sync bytes
        }
        return false
    }

    /**
     * A non-video http(s) chapter link returned with parse=0 and no video
     * shape is fetched by the novel reader only when the caller knows the
     * episode came from a text-shaped detail (flag/id heuristics are too
     * weak to divert generic web pages, so this stays conservative here).
     */
    fun novelUrl(result: JSONObject, rawUrl: String?): String? {
        val direct = result.optString("textUrl", "").ifBlank { result.optString("novelUrl", "") }
        if (direct.isNotBlank()) return direct
        return rawUrl?.takeIf { it.isNotBlank() }
    }

    private fun firstTextField(result: JSONObject): String? {
        for (key in listOf("content", "text", "novel", "body")) {
            val value = result.optString(key, "")
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun rawUrlString(result: JSONObject): String? {
        val opt = result.opt("url") ?: return null
        val value = when (opt) {
            is JSONArray -> opt.toString()
            else -> opt.toString()
        }
        return value.takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Parses a `novel://` url: the payload after the scheme is a JSON
     * object {"title":..,"content":..} whose content field is the whole
     * chapter text (verified spider shape). Returns a Novel with the body
     * inline; null when the payload carries no usable text — a non-JSON
     * payload is taken as the body itself.
     */
    fun parseNovelPayload(raw: String, headers: Map<String, String>): ReaderContent.Novel? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("novel://")) return null
        val body = trimmed.substring("novel://".length).trim()
        if (body.isEmpty()) return null
        if (body.startsWith("{")) {
            try {
                val json = JSONObject(body)
                for (key in listOf("content", "text", "novel", "body")) {
                    val value = json.optString(key, "")
                    if (value.isNotBlank()) {
                        return ReaderContent.Novel(value, null, headers)
                    }
                }
                LOG.i("echo-reader novel:// payload without a text field")
                return null
            } catch (th: Throwable) {
                LOG.i("echo-reader novel:// payload is not JSON, treating as raw text")
            }
        }
        return ReaderContent.Novel(body, null, headers)
    }

    fun parsePicsPayload(raw: String): List<String>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("pics://")) return null
        val body = trimmed.substring("pics://".length)
        if (body.isBlank()) return emptyList()
        return body.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun splitPieces(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return try {
                val array = JSONArray(trimmed)
                val out = ArrayList<String>()
                for (i in 0 until array.length()) {
                    val item = array.opt(i)
                    if (item is String && item.isNotBlank()) out.add(item.trim())
                }
                // quality arrays are [name, url, name, url...]: keep only
                // entries that are themselves image-like.
                val imageOnly = out.filter { isImageLike(it) }
                if (out.size >= 4 && imageOnly.size * 2 == out.size && imageOnly.isNotEmpty()) {
                    imageOnly
                } else {
                    out
                }
            } catch (th: Throwable) {
                emptyList()
            }
        }
        val separators = listOf("&&", "\n", "#", "|", ",")
        for (sep in separators) {
            if (trimmed.contains(sep)) {
                val parts = trimmed.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) return parts
            }
        }
        return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
    }

    fun isImageLike(value: String): Boolean {
        val v = value.trim()
        if (v.startsWith("data:image/", ignoreCase = true)) return true
        if (v.startsWith("pics://")) return true
        val base = v.substringBefore('@')
        val path = urlPath(base)
        if (IMAGE_EXTENSIONS.any { path.endsWith(it) }) return true
        return false
    }

    fun isVideoLike(value: String): Boolean {
        val path = urlPath(value)
        return path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mp4") ||
            path.endsWith(".mkv") || path.endsWith(".flv") || path.endsWith(".ts") ||
            path.endsWith(".avi") || path.endsWith(".mov") || path.endsWith(".mp3") ||
            path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".flac")
    }

    fun urlPath(value: String): String {
        var v = value.trim()
        val q = v.indexOf('?')
        if (q >= 0) v = v.substring(0, q)
        val f = v.indexOf('#')
        if (f >= 0) v = v.substring(0, f)
        return v.lowercase(Locale.ROOT)
    }

    private fun looksLikeMarkup(text: String): Boolean {
        val head = text.trimStart().take(64).lowercase(Locale.ROOT)
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("{")
    }
}

/** Decodes data: URIs and extracts readable paragraphs from fetched bodies. */
object ReaderText {

    fun decodeDataUri(uri: String): String? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(0, comma).lowercase(Locale.ROOT)
        val payload = uri.substring(comma + 1)
        return try {
            if (meta.contains("base64")) {
                val bytes = Base64.decode(payload, Base64.DEFAULT)
                val charset = charsetFromMeta(meta) ?: Charsets.UTF_8
                String(bytes, charset)
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8")
            }
        } catch (th: Throwable) {
            null
        }
    }

    private fun charsetFromMeta(meta: String): Charset? {
        val idx = meta.indexOf("charset=")
        if (idx < 0) return null
        val name = meta.substring(idx + "charset=".length).substringBefore(';').trim()
        return try {
            Charset.forName(name)
        } catch (th: Throwable) {
            null
        }
    }

    private val JSON_TEXT_KEYS = listOf(
        "content", "text", "novel", "body", "chapterContent", "chapterText", "article",
    )

    /**
     * JSON chapter shape (fanqie-style APIs): the body is a JSON object
     * whose prose lives in a long string field — a well-known key
     * (content / text / ...), or any long string that is not a link —
     * possibly nested under data / result wrappers. Returns the prose
     * (which may itself carry <p> markup; callers paragraph-extract it),
     * or null when the JSON holds no prose field.
     */
    fun extractJsonContent(body: String): String? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        val root = try {
            JSONObject(trimmed)
        } catch (th: Throwable) {
            return null
        }
        return findJsonTextField(root, 0)
    }

    private fun findJsonTextField(obj: JSONObject, depth: Int): String? {
        var best: String? = null
        fun consider(value: Any?, named: Boolean) {
            if (value !is String) return
            val v = value.trim()
            if (v.isEmpty()) return
            // A link is not prose, however long its query string gets.
            if (v.startsWith("http") && v.length < 300) return
            val threshold = if (named) 20 else 200
            val current = best
            if (v.length >= threshold && (current == null || v.length > current.length)) {
                best = v
            }
        }
        for (key in JSON_TEXT_KEYS) consider(obj.opt(key), named = true)
        val keys = obj.keys()
        while (keys.hasNext()) consider(obj.opt(keys.next()), named = false)
        best?.let { return it }
        if (depth < 2) {
            val nested = obj.keys()
            while (nested.hasNext()) {
                val value = obj.opt(nested.next())
                if (value is JSONObject) {
                    val found = findJsonTextField(value, depth + 1)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    /**
     * Turns a fetched chapter body into paragraphs. HTML is paragraph
     * extracted (p / br / div boundaries), scripts and styles dropped;
     * plain text is split on blank lines, falling back to one paragraph
     * per line. Returns the paragraph list; empty means "no readable text".
     */
    fun extractParagraphs(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val trimmed = body.trim()
        val looksHtml = trimmed.startsWith("<") || trimmed.contains("<p", ignoreCase = true) ||
            trimmed.contains("<br", ignoreCase = true) || trimmed.contains("<div", ignoreCase = true)
        val rawParagraphs: List<String> = if (looksHtml) {
            var html = trimmed
            html = Regex("(?is)<script.*?</script>").replace(html, "")
            html = Regex("(?is)<style.*?</style>").replace(html, "")
            html = Regex("(?i)</p\\s*>|<br\\s*/?>|</div\\s*>|</li\\s*>").replace(html, "\n")
            html = Regex("(?is)<[^>]+>").replace(html, "")
            decodeEntities(html).split("\n")
        } else {
            val normalized = trimmed.replace("\r\n", "\n").replace('\r', '\n')
            if (normalized.contains("\n\n")) {
                normalized.split(Regex("\n\\s*\n"))
            } else {
                normalized.split("\n")
            }
        }
        return rawParagraphs
            .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun decodeEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    /** Joins paragraphs into the reader body; paragraphs keep their breaks. */
    fun joinParagraphs(paragraphs: List<String>): String = paragraphs.joinToString("\n")

    /** Splits a stored body back into paragraphs (one per line). */
    fun splitParagraphs(body: String): List<String> =
        body.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}
