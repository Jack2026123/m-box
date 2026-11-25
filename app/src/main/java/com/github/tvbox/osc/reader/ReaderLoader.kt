package com.github.tvbox.osc.reader

import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import com.github.tvbox.osc.net.OkGoHelper
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.FloatLog
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class ReaderChapterException(message: String) : Exception(message)

/**
 * Loads one chapter/volume for a reader session: replays the chapter's
 * episode id through the same spider playerContent pipeline the detail
 * page uses (SourceViewModel.getPlay + PlayLoader normalization), then
 * classifies the result and, for novels, resolves the body text.
 */
object ReaderLoader {

    private const val PLAY_RESULT_TIMEOUT_MS = 25_000L
    private const val CHAPTER_TOTAL_TIMEOUT_MS = 30_000L
    private const val TEXT_CACHE_MAX_BYTES = 32L * 1024 * 1024

    private val novelTextCache = ConcurrentHashMap<String, String>()
    private val comicCache = ConcurrentHashMap<String, ReaderContent.Comic>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { OkGoHelper.getDefaultClient()?.dns?.let { dns(it) } }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun loadChapter(session: ReaderSession, index: Int): ReaderContent =
        loadChapter(session, index, foreground = true)

    /**
     * One-chapter load with a hard total deadline: whatever stage a
     * chapter hangs in (play-result wait, body fetch, parsing), the
     * reader surfaces the timeout error state with its retry button
     * instead of spinning forever.
     */
    private suspend fun loadChapter(
        session: ReaderSession,
        index: Int,
        foreground: Boolean,
    ): ReaderContent {
        FloatLog.append("app", null, "loadChapter START bookKey=${session.bookKey} index=$index")
        val content = try {
            withTimeout(CHAPTER_TOTAL_TIMEOUT_MS) { loadChapterInternal(session, index) }
        } catch (e: TimeoutCancellationException) {
            FloatLog.append("app", null, "loadChapter FAILED bookKey=${session.bookKey} index=$index reason=timeout")
            throw ReaderChapterException("章节加载超时，请重试") // i18n: keep
        } catch (th: Throwable) {
            if (th is CancellationException) throw th
            FloatLog.append("app", null, "loadChapter FAILED bookKey=${session.bookKey} index=$index reason=${th.message}")
            throw th
        }
        FloatLog.append("app", null, "loadChapter SUCCESS bookKey=${session.bookKey} index=$index")
        if (foreground) {
            if (content is ReaderContent.Novel) onNovelAnchor(session, index)
            else prefetchAround(session, index, content)
        }
        return content
    }

    private suspend fun loadChapterInternal(session: ReaderSession, index: Int): ReaderContent {
        // Legado reading-mode books: chapters are served by the book
        // engine; the spider pipeline below stays untouched.
        session.legadoBookKey?.let { key ->
            if (index == session.startIndex) return session.firstContent
            // Legado chapters used to re-run the rule engine on every
            // load (no cache at all): every chapter crossing paid a
            // full fetch, which is the spinner at the chapter edge.
            // Same memory + disk cache shape as the spider path
            // below, so prefetched bodies persist and a crossing
            // lands on a cached body instead of the network.
            val chapterKey = cacheKey(session, index)
            novelTextCache[chapterKey]?.let {
                return ReaderContent.Novel(it, null, emptyMap())
            }
            readDiskText(chapterKey)?.let {
                novelTextCache[chapterKey] = it
                return ReaderContent.Novel(it, null, emptyMap())
            }
            val loaded = com.github.tvbox.osc.reading.LegadoChapterProvider
                .loadChapterContent(key, index)
                ?: throw ReaderChapterException("chapter load failed")
            if (loaded is ReaderContent.Novel) {
                val text = loaded.inlineText
                if (!text.isNullOrBlank()) {
                    novelTextCache[chapterKey] = text
                    writeDiskText(chapterKey, text)
                }
            }
            return loaded
        }
        if (index == session.startIndex && !comicCache.containsKey(cacheKey(session, index))) {
            val first = session.firstContent
            if (first is ReaderContent.Comic) comicCache[cacheKey(session, index)] = first
            return resolveTextIfNeeded(session, index, first)
        }
        val cachedComic = comicCache[cacheKey(session, index)]
        if (cachedComic != null) return cachedComic
        val episode = session.episodes.getOrNull(index)
            ?: throw ReaderChapterException("chapter out of range")
        val result = requestPlayResult(session, episode.url)
            ?: throw ReaderChapterException("empty player result")
        val msg = result.optString("msg", "")
        if (msg.isNotEmpty()) throw ReaderChapterException(msg)
        var content = ReaderContentClassifier.classify(result)
        if (content == null && ReaderContentClassifier.shouldProbe(result)) {
            // Same content sniff the detail-page diversion uses, so
            // chapter 2..N of a probed source (fanqie-style APIs, local
            // proxy endpoints) resolve exactly like the first chapter.
            content = ReaderContentClassifier.probe(result)
            if (content != null) LOG.i("echo-reader chapter divert by probe at index " + index)
        }
        if (content == null) {
            // Some text sources hand back a bare non-video link: try it
            // as a chapter page before giving up. The fetch validates —
            // a body with no readable text fails this chapter in the
            // reader instead of spinning in the video player.
            val url = result.optString("url", "")
            val parse = result.optString("parse", "1")
            val jx = result.optString("jx", "0")
            if (parse != "1" && jx != "1" && url.startsWith("http") &&
                !ReaderContentClassifier.isVideoLike(url)
            ) {
                content = ReaderContent.Novel(null, url, extractHeaders(result))
            }
        }
        if (content == null) throw ReaderChapterException("unsupported chapter format")
        if (content is ReaderContent.Comic) {
            if (content.images.isEmpty()) throw ReaderChapterException("empty image list")
            comicCache[cacheKey(session, index)] = content
        }
        return resolveTextIfNeeded(session, index, content)
    }

    private fun extractHeaders(result: JSONObject): Map<String, String> =
        com.github.tvbox.osc.sourcedata.SourceHelper.extractPlayHeaders(result) ?: emptyMap()

    private fun cacheKey(session: ReaderSession, index: Int): String =
        session.bookKey + "#" + index

    private suspend fun resolveTextIfNeeded(
        session: ReaderSession,
        index: Int,
        content: ReaderContent,
    ): ReaderContent {
        if (content !is ReaderContent.Novel) return content
        val key = cacheKey(session, index)
        novelTextCache[key]?.let {
            LOG.i("echo-reader text cache hit (memory) index=" + index)
            return ReaderContent.Novel(it, null, content.headers)
        }
        readDiskText(key)?.let {
            novelTextCache[key] = it
            LOG.i("echo-reader text cache hit (disk) index=" + index)
            return ReaderContent.Novel(it, null, content.headers)
        }
        LOG.i("echo-reader text cache miss index=" + index)
        val text = fetchNovelText(content)
        novelTextCache[key] = text
        writeDiskText(key, text)
        return ReaderContent.Novel(text, null, content.headers)
    }

    // ---- Novel chapter text on disk --------------------------------
    // Chapter bodies persist under the app cache dir, keyed by the
    // same book#chapter cache key hashed into a file name. Living in
    // cacheDir means the settings "clear cache" action and AppReset
    // (both wipe cacheDir children) take the reading cache with
    // everything else; a size cap with oldest-first eviction keeps
    // it from growing without bound.

    private fun textCacheDir(): File? = runCatching {
        val context = AppContextHolder.context() ?: return@runCatching null
        File(context.cacheDir, "reader_chapters").apply { mkdirs() }
    }.getOrNull()

    private fun textCacheFile(dir: File, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) } + ".txt"
        return File(dir, name)
    }

    private suspend fun readDiskText(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = textCacheDir() ?: return@runCatching null
            val file = textCacheFile(dir, key)
            if (!file.isFile) return@runCatching null
            file.setLastModified(System.currentTimeMillis())
            file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun writeDiskText(key: String, text: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = textCacheDir() ?: return@runCatching
                textCacheFile(dir, key).writeText(text, Charsets.UTF_8)
                trimTextCache(dir)
            }
        }
    }

    private fun trimTextCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= TEXT_CACHE_MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= TEXT_CACHE_MAX_BYTES) break
            val len = file.length()
            if (file.delete()) total -= len
        }
    }

    // ---- Neighbour prefetch, anchored at the reader ----------------
    // The anchor is always the chapter the user is on right now.
    // Every foreground chapter load re-anchors here, so a jump —
    // catalog pick, chapter-slider drag, fast page flipping across a
    // chapter boundary — restarts prefetching from the landing
    // chapter, nearest chapters first, and work queued for the old
    // position is cancelled (the load job, plus any comic image
    // downloads it had enqueued) instead of competing for bandwidth:
    // the same rule as a video cache re-anchoring after a seek.
    // Prefetch never blocks reading, never cascades (its own loads
    // are not foreground loads), and swallows every failure.

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchGeneration = AtomicInteger(0)
    private val prefetchJobs = ConcurrentHashMap<String, Job>()
    private val prefetchImageDisposables = ConcurrentHashMap<String, MutableSet<Disposable>>()
    private val prefetchImagesDone = ConcurrentHashMap.newKeySet<String>()

    private fun prefetchAround(session: ReaderSession, index: Int, content: ReaderContent) {
        val generation = prefetchGeneration.incrementAndGet()
        // Supersede the old anchor: cancel its job and dispose the
        // comic image downloads it had already enqueued.
        prefetchJobs.remove(session.bookKey)?.cancel()
        val staleKeys = prefetchImageDisposables.keys.filter { it.startsWith(session.bookKey + "#") }
        for (staleKey in staleKeys) {
            prefetchImageDisposables.remove(staleKey)?.forEach { runCatching { it.dispose() } }
            prefetchImagesDone.remove(staleKey)
        }
        val targets = when (content) {
            is ReaderContent.Novel -> listOf(index + 1, index + 2)
            is ReaderContent.Comic -> listOf(index + 1)
        }.filter { it in session.episodes.indices }
        if (targets.isEmpty()) return
        val job = prefetchScope.launch {
            for (target in targets) {
                if (generation != prefetchGeneration.get()) return@launch
                try {
                    val loaded = loadChapter(session, target, foreground = false)
                    if (loaded is ReaderContent.Comic) {
                        val key = cacheKey(session, target)
                        if (prefetchImagesDone.add(key)) enqueueComicImages(key, loaded)
                    }
                    LOG.i("echo-reader prefetch done index=" + target)
                } catch (th: Throwable) {
                    // Best-effort only: failures stay silent, and a
                    // superseded anchor cancels this job anyway.
                    if (th is CancellationException) throw th
                }
            }
        }
        prefetchJobs[session.bookKey] = job
    }

    // ---- Novel window prefetch: 100 chapters, rolling --------------
    // Opening a book prefetches the next 100 chapters in the
    // background, nearest first, sequential and low-priority; when
    // the reader reaches 10 chapters short of the window end (10%
    // of a 100-chapter batch) the window extends by another 100,
    // rolling to the end of the book. Bodies persist through the
    // same memory + disk chapter cache the foreground load uses, so
    // a chapter crossing lands on a cached body: no spinner, no
    // flash, no main-thread work, and the prefetch itself never
    // shows anything. A jump (catalog pick, slider drag) outside
    // the window starts a fresh window from the landing chapter;
    // sequential moves inside the window only fast-forward the
    // worker past read chapters and extend on the refill trigger.
    // Failures stay silent and never stall the window: a chapter
    // that fails to prefetch is simply fetched on demand when the
    // reader reaches it. One batch at a time, never the whole book.

    private const val NOVEL_PREFETCH_BATCH = 100
    private const val NOVEL_PREFETCH_REFILL_REMAINING = 10

    private class NovelPrefetchWindow(
        val batchStart: Int,
        val batchEnd: AtomicInteger,
        val next: AtomicInteger,
    )

    private val novelPrefetchWindows = ConcurrentHashMap<String, NovelPrefetchWindow>()

    private fun onNovelAnchor(session: ReaderSession, index: Int) {
        val total = session.episodes.size
        val window = novelPrefetchWindows[session.bookKey]
        if (window == null || index > window.batchEnd.get() || index < window.batchStart) {
            startNovelWindow(session, index)
            return
        }
        if (window.next.get() <= index) window.next.set(index + 1)
        val end = window.batchEnd.get()
        if (index >= end - NOVEL_PREFETCH_REFILL_REMAINING && end < total - 1) {
            window.batchEnd.set(minOf(end + NOVEL_PREFETCH_BATCH, total - 1))
        }
        if (prefetchJobs[session.bookKey]?.isActive != true) {
            launchNovelWorker(session, window)
        }
    }

    private fun startNovelWindow(session: ReaderSession, anchor: Int) {
        val total = session.episodes.size
        val window = NovelPrefetchWindow(
            batchStart = anchor + 1,
            batchEnd = AtomicInteger(minOf(anchor + NOVEL_PREFETCH_BATCH, total - 1)),
            next = AtomicInteger(anchor + 1),
        )
        novelPrefetchWindows[session.bookKey] = window
        prefetchJobs.remove(session.bookKey)?.cancel()
        val staleKeys = prefetchImageDisposables.keys.filter { it.startsWith(session.bookKey + "#") }
        for (staleKey in staleKeys) {
            prefetchImageDisposables.remove(staleKey)?.forEach { runCatching { it.dispose() } }
            prefetchImagesDone.remove(staleKey)
        }
        launchNovelWorker(session, window)
    }

    private fun launchNovelWorker(session: ReaderSession, window: NovelPrefetchWindow) {
        val generation = prefetchGeneration.incrementAndGet()
        val job = prefetchScope.launch {
            while (true) {
                if (generation != prefetchGeneration.get()) return@launch
                val target = window.next.get()
                if (target > window.batchEnd.get()) return@launch
                try {
                    loadChapter(session, target, foreground = false)
                } catch (th: Throwable) {
                    if (th is CancellationException) throw th
                }
                window.next.compareAndSet(target, target + 1)
            }
        }
        prefetchJobs[session.bookKey] = job
    }

    private fun enqueueComicImages(targetKey: String, comic: ReaderContent.Comic) {
        runCatching {
            val context = AppContextHolder.context() ?: return@runCatching
            val loader = ReaderImageLoader.get(context, comic.headers)
            val set = prefetchImageDisposables.computeIfAbsent(targetKey) {
                ConcurrentHashMap.newKeySet()
            }
            for (url in comic.images) {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build()
                set.add(loader.enqueue(request))
            }
        }
    }

    /** Resolves the chapter body: inline text > data: URI > fetched body. */
    suspend fun fetchNovelText(novel: ReaderContent.Novel): String {
        novel.inlineText?.takeIf { it.isNotBlank() }?.let { inline ->
            val paragraphs = ReaderText.extractParagraphs(inline)
            return if (paragraphs.isEmpty()) inline.trim() else ReaderText.joinParagraphs(paragraphs)
        }
        val url = novel.textUrl?.trim().orEmpty()
        if (url.isEmpty()) throw ReaderChapterException("empty chapter text")
        if (url.startsWith("data:", ignoreCase = true)) {
            val decoded = ReaderText.decodeDataUri(url)
                ?: throw ReaderChapterException("chapter decode failed")
            val paragraphs = ReaderText.extractParagraphs(ReaderText.extractJsonContent(decoded) ?: decoded)
            if (paragraphs.isEmpty()) throw ReaderChapterException("empty chapter text")
            return ReaderText.joinParagraphs(paragraphs)
        }
        if (url.startsWith("pics://")) throw ReaderChapterException("not a text chapter")
        val body = httpGet(url, novel.headers)
        // JSON chapter APIs (fanqie-style) wrap the prose in a long
        // string field; pull it out before paragraph extraction so the
        // reader never shows raw JSON scaffolding as body text.
        val jsonText = ReaderText.extractJsonContent(body)
        if (jsonText != null) LOG.i("echo-reader chapter body: JSON text field extracted")
        val paragraphs = ReaderText.extractParagraphs(jsonText ?: body)
        if (paragraphs.isEmpty()) throw ReaderChapterException("empty chapter text")
        return ReaderText.joinParagraphs(paragraphs)
    }

    private suspend fun httpGet(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            for ((k, v) in headers) {
                runCatching { builder.header(k, v) }
            }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
                )
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw ReaderChapterException("HTTP " + response.code)
                val bytes = response.body?.bytes() ?: throw ReaderChapterException("empty body")
                decodeBytes(bytes, response.header("Content-Type"))
            }
        }

    private fun decodeBytes(bytes: ByteArray, contentType: String?): String {
        val fromHeader = contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()
        if (!fromHeader.isNullOrEmpty()) {
            runCatching { return String(bytes, charset(fromHeader)) }
        }
        // BOM sniff, then UTF-8 with GBK fallback for legacy novel sites.
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        val replacementCount = utf8.count { it == '\uFFFD' }
        if (replacementCount > 0 && replacementCount > utf8.length / 200 + 2) {
            runCatching {
                val gbk = String(bytes, charset("GBK"))
                if (gbk.count { it == '\uFFFD' } < replacementCount) return gbk
            }
        }
        return utf8
    }

    private suspend fun requestPlayResult(session: ReaderSession, episodeUrl: String): JSONObject? {
        val vm = SourceViewModel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            return withTimeout(PLAY_RESULT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    // The view model (and with it the playResult channel)
                    // is created fresh for this one request, so the
                    // channel starts EMPTY: the first value it ever
                    // emits is this request's own result. PlayLoader
                    // posts exactly one value per getPlay call. The old
                    // code skipped that first value as "stale" and then
                    // waited for a second emission that could never
                    // come — every in-reader chapter load burned the
                    // full timeout and died. Take the first value,
                    // verbatim (a posted null is a real, failed result
                    // and surfaces as the caller's error state).
                    val collectJob = scope.launch {
                        vm.playResult.flow.collect { value ->
                            if (cont.isActive) cont.resume(value)
                        }
                    }
                    cont.invokeOnCancellation {
                        collectJob.cancel()
                        vm.cancelPlayRequest()
                    }
                    vm.getPlay(
                        session.sourceKey,
                        session.playFlag,
                        session.bookKey + "#" + episodeUrl.hashCode(),
                        episodeUrl,
                        null,
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            // withTimeout's own deadline hit: surface a timeout error
            // state, fast, instead of an endless spinner. A parent
            // cancellation arrives as a plain CancellationException
            // (different type) and propagates untouched.
            throw ReaderChapterException("章节加载超时，请重试") // i18n: keep
        } finally {
            scope.cancel()
        }
    }
}
