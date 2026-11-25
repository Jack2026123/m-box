package com.github.tvbox.osc.reading

import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * The four Legado chains (search / book info / toc / content) over
 * [AnalyzeRule] + [AnalyzeUrl], mirroring webBook's data flow.
 *
 * A chain run shares one [LegadoJs] (and its variable map) from info
 * through toc to content, because sources stash values with @put during
 * the info pass and read them back chapters later.
 */
class WebBook(val source: BookSource) {

    /** One step of streaming cross-source search. */
    sealed interface SearchStreamEvent {
        /** One source's batch, emitted on arrival (not in source order). */
        data class Batch(
            val sourceName: String,
            val sourceKey: String,
            val books: List<LegadoBook>,
        ) : SearchStreamEvent

        /** One source failed; the rest keep going. */
        data class Failed(
            val sourceName: String,
            val sourceKey: String,
            val error: String,
        ) : SearchStreamEvent

        /** All sources finished; failures keyed by source name. */
        data class Done(val failures: Map<String, String>) : SearchStreamEvent
    }

    val fetcher = LegadoFetcher(source)
    val vars: MutableMap<String, String> = HashMap()

    private fun newJs(ruleRef: () -> AnalyzeRule?): LegadoJs {
        val host = object : LegadoJs.RuleHost {
            override fun evalRuleString(rule: String): String =
                ruleRef()?.getString(rule) ?: ""
            override fun evalRuleElements(rule: String): List<Any> =
                ruleRef()?.getElements(rule) ?: emptyList()
            override fun evalRuleStringList(rule: String): List<String> =
                ruleRef()?.getStringList(rule) ?: emptyList()
            override fun setContent(content: Any?, baseUrl: String?) {
                val rule = ruleRef() ?: return
                rule.setContent(content, baseUrl ?: rule.baseUrl)
            }
            override fun httpGet(url: String): String {
                val (u, opt) = AnalyzeUrl.splitOptions(url)
                return fetcher.execute(u, opt).body
            }
            override fun httpPost(url: String, body: String, headers: Map<String, String>): String =
                fetcher.execute(
                    url,
                    LegadoUrlOptions(method = "POST", body = body, headers = headers),
                ).body
        }
        return LegadoJs(source, host, vars)
    }

    private class Session(
        val js: LegadoJs,
        val rule: AnalyzeRule,
    )

    private fun session(content: Any?, baseUrl: String): Session {
        var rule: AnalyzeRule? = null
        val js = newJs { rule }
        val created = AnalyzeRule(source, js, content, baseUrl)
        rule = created
        js.book = null
        return Session(js, created)
    }

    // ------------------------------------------------------------ search

    fun search(key: String, page: Int = 1): List<LegadoBook> {
        val s = session(null, source.bookSourceUrl)
        s.js.key = key
        s.js.page = page
        val built = AnalyzeUrl.build(s.rule, source.searchUrl)
        val (url, options) = AnalyzeUrl.splitOptions(built)
        val response = fetcher.execute(url, options)
        s.rule.setContent(response.body, response.url)
        s.js.baseUrl = response.url
        val elements = s.rule.getElements(source.searchRule("bookList"))
        val checkRule = source.searchRule("checkKeyWord")
        val out = ArrayList<LegadoBook>()
        for (element in elements) {
            // One malformed result item must not sink the whole search.
            val book = try {
                readSearchItem(s, element, key, checkRule)
            } catch (e: Throwable) {
                LegadoLog.fieldError(source, "searchItem", source.searchRule("bookList"), e)
                null
            }
            if (book != null) out.add(book)
        }
        s.js.close()
        return out
    }

    private fun readSearchItem(
        s: Session,
        element: Any,
        key: String,
        checkRule: String,
    ): LegadoBook? {
        val item = s.rule.child(element)
        if (checkRule.isNotBlank()) {
            val check = item.getString(checkRule)
            if (check.isNotBlank() && !check.contains(key)) return null
        }
        val name = item.getString(source.searchRule("name"))
        if (name.isBlank()) return null
        val bookUrl = item.getString(source.searchRule("bookUrl"), isUrl = true)
        if (bookUrl.isBlank()) return null
        return LegadoBook(
            sourceKey = source.sourceKey,
            sourceName = source.bookSourceName,
            sourceType = source.bookSourceType,
            bookUrl = bookUrl,
            name = name,
            author = item.getString(source.searchRule("author")),
            intro = item.getString(source.searchRule("intro")),
            coverUrl = item.getString(source.searchRule("coverUrl"), isUrl = true),
            kind = item.getString(source.searchRule("kind")),
            lastChapter = item.getString(source.searchRule("lastChapter")),
            wordCount = item.getString(source.searchRule("wordCount")),
        )
    }

    // ------------------------------------------------------------ book info

    /** Fetch the book page and fill info fields + tocUrl into [book]. */
    fun loadBookInfo(book: LegadoBook): LegadoBook {
        val (url, options) = AnalyzeUrl.splitOptions(book.bookUrl)
        val response = fetcher.execute(url, options)
        val s = session(response.body, response.url)
        s.js.book = book
        s.js.baseUrl = response.url
        val init = source.infoRule("init")
        if (init.isNotBlank()) {
            val root = s.rule.getElement(init)
            if (root != null) s.rule.setContent(root, response.url)
        }
        book.name = s.rule.getString(source.infoRule("name")).ifBlank { book.name }
        book.author = s.rule.getString(source.infoRule("author")).ifBlank { book.author }
        book.intro = s.rule.getString(source.infoRule("intro")).ifBlank { book.intro }
        book.coverUrl = s.rule.getString(source.infoRule("coverUrl"), isUrl = true)
            .ifBlank { book.coverUrl }
        book.kind = s.rule.getString(source.infoRule("kind")).ifBlank { book.kind }
        book.lastChapter = s.rule.getString(source.infoRule("lastChapter"))
            .ifBlank { book.lastChapter }
        book.wordCount = s.rule.getString(source.infoRule("wordCount"))
            .ifBlank { book.wordCount }
        val tocRule = source.infoRule("tocUrl")
        book.tocUrl = if (tocRule.isBlank()) {
            book.bookUrl
        } else {
            s.rule.getString(tocRule, isUrl = true).ifBlank { book.bookUrl }
        }
        s.js.close()
        // vars (@put during info) live in this WebBook instance's shared
        // map, so toc/content sessions still see them after close.
        return book
    }

    // ------------------------------------------------------------ toc

    fun loadToc(book: LegadoBook): List<LegadoChapter> {
        val tocUrl = book.tocUrl.ifBlank { book.bookUrl }
        // A leading '-' on chapterList is Legado's reverse-order marker:
        // strip it, parse normally, reverse the assembled list at the end.
        val rawListRule = source.tocRule("chapterList")
        val reversedToc = rawListRule.trimStart().startsWith("-")
        val listRule = if (reversedToc) {
            rawListRule.trimStart().removePrefix("-").trim()
        } else {
            rawListRule
        }
        val chapters = ArrayList<LegadoChapter>()
        val seen = HashSet<String>()
        var pageUrl: String? = tocUrl
        var guard = 0
        while (pageUrl != null && guard++ < 30) {
            val (url, options) = AnalyzeUrl.splitOptions(pageUrl)
            val response = fetcher.execute(url, options)
            val s = session(response.body, response.url)
            s.js.book = book
            s.js.baseUrl = response.url
            chapters.addAll(readChapterPage(s, chapters.size, listRule))
            // nextTocUrl is read while the session (and its JS runtime)
            // is still alive: JS-array results need evalStringList.
            val nextRule = source.tocRule("nextTocUrl")
            val next = if (nextRule.isBlank()) {
                emptyList()
            } else {
                s.rule.getStringList(nextRule, isUrl = true)
                    .filter { it.isNotBlank() && seen.add(it) && it != response.url }
            }
            s.js.close()
            pageUrl = next.firstOrNull()
            // Extra next-page URLs (a JS list) are all fetched in order.
            for (extra in next.drop(1)) {
                if (guard++ >= 30) break
                val (u2, o2) = AnalyzeUrl.splitOptions(extra)
                val r2 = fetcher.execute(u2, o2)
                val s2 = session(r2.body, r2.url)
                s2.js.book = book
                s2.js.baseUrl = r2.url
                chapters.addAll(readChapterPage(s2, chapters.size, listRule))
                s2.js.close()
            }
        }
        if (!reversedToc) return chapters
        return chapters.reversed().mapIndexed { index, chapter ->
            LegadoChapter(
                name = chapter.name,
                url = chapter.url,
                index = index,
                payload = chapter.payload,
                isVip = chapter.isVip,
                updateTime = chapter.updateTime,
            )
        }
    }

    private fun readChapterPage(
        s: Session,
        offset: Int,
        listRule: String,
    ): List<LegadoChapter> {
        val elements = s.rule.getElements(listRule)
        val out = ArrayList<LegadoChapter>()
        for (element in elements) {
            val chapter = try {
                readChapterItem(s, element, offset + out.size)
            } catch (e: Throwable) {
                LegadoLog.fieldError(source, "chapterItem", listRule, e)
                null
            }
            if (chapter != null) out.add(chapter)
        }
        return out
    }

    private fun readChapterItem(s: Session, element: Any, index: Int): LegadoChapter? {
        val item = s.rule.child(element)
        val name = item.getString(source.tocRule("chapterName"))
        if (name.isBlank()) return null
        val urlRule = source.tocRule("chapterUrl")
        val rawUrl = item.getString(urlRule, isUrl = true)
        val payload = if (rawUrl.isBlank() || rawUrl == "-") {
            when (element) {
                is org.json.JSONObject, is org.json.JSONArray -> element.toString()
                is org.jsoup.nodes.Element -> element.outerHtml()
                else -> element.toString()
            }
        } else {
            null
        }
        return LegadoChapter(
            name = name,
            url = if (payload != null) "" else rawUrl,
            index = index,
            payload = payload,
            isVip = item.getString(source.tocRule("isVip"))
                .let { it == "true" || it == "1" },
            updateTime = item.getString(source.tocRule("updateTime")),
        )
    }

    // ------------------------------------------------------------ content

    fun loadContent(book: LegadoBook, chapter: LegadoChapter): LegadoContent {
        val contentRule = source.contentRule("content")
        var raw: String
        var pageBody: String? = null
        var responseUrl = chapter.url
        if (chapter.payload != null) {
            val s = session(chapter.payload, book.tocUrl.ifBlank { book.bookUrl })
            s.js.book = book
            s.js.chapter = chapter
            s.js.baseUrl = book.tocUrl.ifBlank { book.bookUrl }
            raw = s.rule.getString(contentRule)
            s.js.close()
        } else {
            val builtSession = session(null, book.tocUrl.ifBlank { book.bookUrl })
            builtSession.js.book = book
            builtSession.js.chapter = chapter
            val built = runCatching {
                AnalyzeUrl.build(builtSession.rule, chapter.url)
            }.getOrDefault(chapter.url)
            builtSession.js.close()
            val (url, options) = AnalyzeUrl.splitOptions(built)
            val response = fetcher.execute(url, options)
            pageBody = response.body
            responseUrl = response.url
            // Follow ruleContent.nextContentUrl: many sources split one
            // chapter over several pages; concatenate the extracted
            // pieces in order (loop-protected, capped).
            val nextContentRule = source.contentRule("nextContentUrl")
            val pieces = ArrayList<String>()
            val visited = HashSet<String>()
            var currentBody = response.body
            var currentUrl = response.url
            var currentWebView = response.webView
            var pageGuard = 0
            while (true) {
                visited.add(currentUrl)
                val s = session(currentBody, currentUrl)
                s.js.book = book
                s.js.chapter = chapter
                s.js.baseUrl = currentUrl
                var piece = s.rule.getString(contentRule)
                if (currentWebView && piece.isBlank()) piece = currentBody
                val next = if (nextContentRule.isNotBlank() && pageGuard < MAX_CONTENT_PAGES - 1) {
                    s.rule.getString(nextContentRule, isUrl = true)
                } else {
                    ""
                }
                s.js.close()
                pieces.add(piece)
                if (next.isBlank() || next in visited) break
                pageGuard++
                val (u2, o2) = AnalyzeUrl.splitOptions(next)
                val r2 = runCatching { fetcher.execute(u2, o2) }.getOrNull() ?: break
                currentBody = r2.body
                currentUrl = r2.url
                currentWebView = r2.webView
                pageBody = r2.body
            }
            raw = pieces.joinToString("")
            responseUrl = currentUrl
        }
        // ruleContent.replaceRegex: a bare ## chain applied to the text.
        val replaceRegex = source.contentRule("replaceRegex")
        if (replaceRegex.isNotBlank() && raw.isNotBlank()) {
            raw = applyReplaceChain(raw, replaceRegex)
        }
        // sourceRegex: sniff a media URL out of the result / page body.
        val sourceRegex = source.contentRule("sourceRegex")
        if (sourceRegex.isNotBlank()) {
            val direct = raw.trim()
            if (!isMediaUrl(direct)) {
                val haystack = if (direct.length > 200) direct else (pageBody ?: direct)
                val match = runCatching { Regex(sourceRegex).find(haystack) }.getOrNull()
                if (match != null) raw = match.value
            }
        }
        return classify(book, raw)
    }

    private fun applyReplaceChain(text: String, chain: String): String {
        var out = text
        val parts = chain.split("##")
        var i = 1
        while (i < parts.size) {
            val regex = parts[i]
            val replacement = parts.getOrNull(i + 1) ?: ""
            out = try {
                Regex(regex).replace(out, replacement)
            } catch (e: Exception) {
                out.replace(regex, replacement)
            }
            i += 2
        }
        return out
    }

    private fun classify(book: LegadoBook, raw: String): LegadoContent {
        val text = raw.trim()
        val isAudioSource = source.bookSourceType == 1
        val isImageSource = source.bookSourceType == 2
        val decodeJs = source.contentRule("imageDecode")
        if (text.startsWith("http://") || text.startsWith("https://")) {
            val urlPart = text.lineSequence().first().trim().let { stripImgSrcPayload(it).first }
            if (isAudioSource || isMediaUrl(urlPart)) {
                return LegadoContent.Audio(urlPart, sourceHeaders())
            }
            if (looksLikeImage(urlPart) || (isImageSource && urlPart.startsWith("http"))) {
                return LegadoContent.Images(listOf(urlPart), sourceHeaders(), decodeJs)
            }
        }
        val extracted = extractImageList(text, isImageSource)
        if (extracted != null) {
            val (images, extraHeaders) = extracted
            val headers = LinkedHashMap(sourceHeaders()).apply { putAll(extraHeaders) }
            if (isImageSource || images.size >= 1) {
                return LegadoContent.Images(images, headers, decodeJs)
            }
        }
        // 漫画源但正文被规则写成 <img> 行：再扫一遍
        if (isImageSource) {
            val fromHtml = extractImgTags(text)
            if (fromHtml.first.isNotEmpty()) {
                val headers = LinkedHashMap(sourceHeaders()).apply { putAll(fromHtml.second) }
                return LegadoContent.Images(fromHtml.first, headers, decodeJs)
            }
        }
        return LegadoContent.Text(cleanText(text))
    }

    /**
     * 支持：纯 URL 行、JSON 数组、Legado `<img src="url,{headers}">`、
     * `url,{...}` 选项尾巴。漫画源不强制后缀（CDN 常无 .jpg）。
     */
    private fun extractImageList(text: String, looseHttp: Boolean): Pair<List<String>, Map<String, String>>? {
        val headers = LinkedHashMap<String, String>()
        val parsed = LenientJson.parseAny(text)
        if (parsed is org.json.JSONArray) {
            val urls = ArrayList<String>()
            for (i in 0 until parsed.length()) {
                val s = parsed.opt(i) as? String ?: continue
                val (u, h) = stripImgSrcPayload(s)
                if (looksLikeImage(u) || (looseHttp && u.startsWith("http"))) {
                    urls.add(u)
                    headers.putAll(h)
                }
            }
            if (urls.isNotEmpty()) return urls to headers
        }
        val fromTags = extractImgTags(text)
        if (fromTags.first.isNotEmpty()) return fromTags
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val urls = ArrayList<String>()
        for (line in lines) {
            val (u, h) = stripImgSrcPayload(line)
            if (looksLikeImage(u) || (looseHttp && u.startsWith("http"))) {
                urls.add(u)
                headers.putAll(h)
            }
        }
        if (urls.size >= 1 && (looseHttp || urls.size >= 2 || urls.all { looksLikeImage(it) })) {
            return urls to headers
        }
        return null
    }

    private fun extractImgTags(text: String): Pair<List<String>, Map<String, String>> {
        val urls = ArrayList<String>()
        val headers = LinkedHashMap<String, String>()
        val re = Regex("""(?is)<img[^>]+src\s*=\s*['"]([^'"]+)['"]""")
        for (m in re.findAll(text)) {
            val (u, h) = stripImgSrcPayload(m.groupValues[1])
            if (u.startsWith("http")) {
                urls.add(u)
                headers.putAll(h)
            }
        }
        return urls to headers
    }

    /** Legado 图片地址常写成 `https://..jpg,{"headers":{"Referer":".."}}` */
    private fun stripImgSrcPayload(raw: String): Pair<String, Map<String, String>> {
        var s = raw.trim()
        if (s.startsWith("<img", ignoreCase = true)) {
            val m = Regex("""(?is)src\s*=\s*['"]([^'"]+)['"]""").find(s)
            if (m != null) s = m.groupValues[1]
        }
        val headers = LinkedHashMap<String, String>()
        val comma = s.lastIndexOf(",{")
        if (comma > 0 && s.endsWith("}")) {
            val url = s.substring(0, comma).trim()
            val json = s.substring(comma + 1)
            runCatching {
                val obj = org.json.JSONObject(json)
                val h = obj.optJSONObject("headers") ?: obj
                val keys = h.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.equals("headers", true)) continue
                    val v = h.optString(k)
                    if (v.isNotBlank()) headers[k] = v
                }
            }
            if (url.startsWith("http")) return url to headers
        }
        return s to headers
    }

    private fun looksLikeImage(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".avif") ||
            // 无后缀 CDN / data-r-src 加密图地址
            clean.contains("/static/") || clean.contains("/upload/") || clean.contains("manwa")
    }

    private fun isMediaUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".mp4") ||
            clean.endsWith(".aac") || clean.endsWith(".flac") || clean.endsWith(".wav") ||
            clean.endsWith(".ogg") || clean.endsWith(".m3u8")
    }

    private fun cleanText(text: String): String {
        var out = text
        if (out.contains("<") && out.contains(">")) {
            out = out.replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</p>\\s*<p[^>]*>"), "\n\n")
                .replace(Regex("(?i)</p>"), "\n\n")
                .replace(Regex("<[^>]+>"), "")
            out = android.text.Html.fromHtml(out, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString()
        }
        return out.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun sourceHeaders(): Map<String, String> {
        val obj = LenientJson.parseObjectLenient(source.header)
        val map = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k)
        }
        return map
    }

    companion object {
        /** Hard cap on nextContentUrl page following for one chapter. */
        private const val MAX_CONTENT_PAGES = 50

        /** Cross-source search: one failing source never sinks the rest. */
        suspend fun searchAll(
            sources: List<BookSource>,
            key: String,
            page: Int = 1,
        ): Pair<List<LegadoBook>, Map<String, String>> = coroutineScope {
            val books = ArrayList<LegadoBook>()
            var failures: Map<String, String> = emptyMap()
            searchStream(sources, key, page).collect { event ->
                when (event) {
                    is SearchStreamEvent.Batch -> books.addAll(event.books)
                    is SearchStreamEvent.Done -> failures = event.failures
                    is SearchStreamEvent.Failed -> Unit
                }
            }
            books to failures
        }

        /**
         * Cross-source search as a stream: each source's batch is emitted
         * on arrival instead of waiting for the slowest source, so the UI
         * renders first results while stragglers are still fetching.
         * Same failure semantics as [searchAll]; batch order is arrival
         * order, not source order.
         */
        fun searchStream(
            sources: List<BookSource>,
            key: String,
            page: Int = 1,
        ): Flow<SearchStreamEvent> =
            searchStreamWith(sources) { src -> WebBook(src).search(key, page) }

        /** Stream core with injectable fetch, so unit tests run offline. */
        internal fun searchStreamWith(
            sources: List<BookSource>,
            fetch: suspend (BookSource) -> List<LegadoBook>,
        ): Flow<SearchStreamEvent> = channelFlow {
            val failures = LinkedHashMap<String, String>()
            val jobs = sources.map { src ->
                launch {
                    try {
                        val books = fetch(src)
                        send(SearchStreamEvent.Batch(src.bookSourceName, src.sourceKey, books))
                    } catch (e: Exception) {
                        val msg = e.message ?: e.javaClass.simpleName
                        synchronized(failures) { failures[src.bookSourceName] = msg }
                        LOG.d("WebBook", "search failed ${src.bookSourceName}: $msg")
                        send(SearchStreamEvent.Failed(src.bookSourceName, src.sourceKey, msg))
                    }
                }
            }
            jobs.joinAll()
            send(SearchStreamEvent.Done(failures))
        }
    }
}
