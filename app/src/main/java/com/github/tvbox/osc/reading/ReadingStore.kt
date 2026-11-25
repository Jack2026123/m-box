package com.github.tvbox.osc.reading

import com.github.tvbox.osc.ui.page.SubscribeSource
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.MD5
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage for the reading feature, all in the app KV (MMKV) so it stays
 * fully independent from the vod/live config chain:
 *  - subscription list ("name\turl" entries, same shape as vod subscribes;
 *    local imports use a `local://` pseudo URL)
 *  - pack payload cache per subscription (the raw source JSON text)
 *  - the active reading config (non-empty = reading mode)
 *  - bookshelf (books + reading progress)
 *  - per-source enabled overrides and `source.put` variables (in-memory)
 */
object ReadingStore {

    private const val PACK_PREFIX = "reading_pack_"
    private const val SHELF_KEY = "reading_shelf"
    private const val DISABLED_KEY = "reading_disabled_sources"
    private const val SEARCH_HISTORY_KEY = "reading_search_history"
    private const val SEARCH_SINGLE_SOURCE_KEY = "reading_search_single_source"
    private const val SEARCH_HISTORY_MAX = 8

    val subscriptions = MutableStateFlow(loadSubscriptions())
    val activeUrl: StateFlow<String> get() = activeUrlFlow
    private val activeUrlFlow = MutableStateFlow(KV.get(HawkConfig.READING_ACTIVE_URL, ""))

    private val sourceVars = HashMap<String, HashMap<String, String>>()

    // ------------------------------------------------------------ subscriptions

    private fun loadSubscriptions(): List<SubscribeSource> =
        KV.get(HawkConfig.READING_SUBSCRIBE_LIST, ArrayList<String>()).map { parseEntry(it) }

    private fun parseEntry(value: String): SubscribeSource {
        val index = value.indexOf('\t')
        return if (index < 0) SubscribeSource(value.trim(), value.trim())
        else SubscribeSource(value.substring(0, index).trim(), value.substring(index + 1))
    }

    private fun persistSubscriptions() {
        KV.put(
            HawkConfig.READING_SUBSCRIBE_LIST,
            ArrayList(subscriptions.value.map { it.name + "\t" + it.url }),
        )
    }

    fun refresh() {
        subscriptions.value = loadSubscriptions()
    }

    fun addSubscription(name: String, url: String): SubscribeSource {
        val entry = SubscribeSource(name.trim().ifBlank { url }, url.trim())
        val list = subscriptions.value.filterNot { it.url == entry.url } + entry
        subscriptions.value = list
        persistSubscriptions()
        return entry
    }

    fun editSubscription(target: SubscribeSource, name: String, url: String) {
        val list = subscriptions.value.map {
            if (it.url == target.url) SubscribeSource(name.trim().ifBlank { it.name }, url.trim()) else it
        }
        subscriptions.value = list
        persistSubscriptions()
        if (activeUrlFlow.value == target.url && url != target.url) setActive("")
    }

    fun deleteSubscription(entry: SubscribeSource) {
        subscriptions.value = subscriptions.value.filterNot { it.url == entry.url }
        persistSubscriptions()
        KV.delete(packKey(entry.url))
        if (activeUrlFlow.value == entry.url) setActive("")
    }

    fun importLocal(name: String, text: String): SubscribeSource {
        val url = "local://" + System.currentTimeMillis()
        val entry = addSubscription(name, url)
        savePack(url, text)
        return entry
    }

    /** Blocking fetch of a remote subscription body (call off the main thread). */
    fun fetchPackText(url: String): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
            )
            .build()
        return LegadoFetcher.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code)
            response.body?.string() ?: throw IllegalStateException("empty body")
        }
    }

    /** Rewrite a stored pack without one source (book-source management). */
    fun removeSourceFromPack(packUrl: String, sourceKey: String) {
        val text = packText(packUrl) ?: return
        val kept = BookSource.parsePack(text).filterNot { it.sourceKey == sourceKey }
        val arr = JSONArray()
        for (source in kept) arr.put(source.raw)
        savePack(packUrl, arr.toString())
    }

    // ------------------------------------------------------------ packs

    private fun packKey(url: String): String = PACK_PREFIX + MD5.encode(url)

    fun savePack(url: String, text: String) {
        KV.put(packKey(url), text)
    }

    fun packText(url: String): String? = KV.get(packKey(url), "")

    fun packSources(url: String): List<BookSource> {
        val text = packText(url) ?: return emptyList()
        return BookSource.parsePack(text)
    }

    /** Sources of the active config that are enabled (pack flag + user override). */
    fun activeSources(): List<BookSource> {
        val url = activeUrlFlow.value
        if (url.isEmpty()) return emptyList()
        val disabled = disabledSources()
        return packSources(url).filter { it.enabled && it.sourceKey !in disabled }
    }

    fun allActivePackSources(): List<BookSource> {
        val url = activeUrlFlow.value
        if (url.isEmpty()) return emptyList()
        return packSources(url)
    }

    /** Recent search keywords, most recent first, capped. */
    fun searchHistory(): List<String> =
        KV.get(SEARCH_HISTORY_KEY, ArrayList<String>()).filter { it.isNotBlank() }

    fun pushSearchHistory(keyword: String) {
        val key = keyword.trim()
        if (key.isEmpty()) return
        val list = (listOf(key) + searchHistory().filterNot { it == key })
            .take(SEARCH_HISTORY_MAX)
        KV.put(SEARCH_HISTORY_KEY, ArrayList(list))
    }

    /** Persisted single-source pick (sourceKey); empty = none picked yet. */
    fun singleSourceKey(): String = KV.get(SEARCH_SINGLE_SOURCE_KEY, "")

    fun setSingleSourceKey(key: String) {
        KV.put(SEARCH_SINGLE_SOURCE_KEY, key)
    }

    fun isSourceEnabled(sourceKey: String): Boolean {
        val packDisabled = allActivePackSources()
            .firstOrNull { it.sourceKey == sourceKey }?.enabled == false
        return !packDisabled && sourceKey !in disabledSources()
    }

    fun setSourceEnabled(sourceKey: String, enabled: Boolean) {
        val set = disabledSources().toMutableSet()
        if (enabled) set.remove(sourceKey) else set.add(sourceKey)
        KV.put(DISABLED_KEY, ArrayList(set))
    }

    private fun disabledSources(): Set<String> =
        KV.get(DISABLED_KEY, ArrayList<String>()).toSet()

    // ------------------------------------------------------------ mode

    fun setActive(url: String) {
        KV.put(HawkConfig.READING_ACTIVE_URL, url)
        activeUrlFlow.value = url
    }

    fun clearActive() {
        if (activeUrlFlow.value.isNotEmpty()) setActive("")
    }

    val isReadingMode: Boolean get() = activeUrlFlow.value.isNotEmpty()

    // ------------------------------------------------------------ source vars

    @Synchronized
    fun sourceVar(sourceKey: String, key: String): String? =
        sourceVars[sourceKey]?.get(key)

    @Synchronized
    fun putSourceVar(sourceKey: String, key: String, value: String) {
        sourceVars.getOrPut(sourceKey) { HashMap() }[key] = value
    }

    // ------------------------------------------------------------ book vars

    private val bookVars = HashMap<String, HashMap<String, String>>()

    @Synchronized
    fun bookVar(bookKey: String, key: String): String? =
        bookVars[bookKey]?.get(key)

    @Synchronized
    fun putBookVar(bookKey: String, key: String, value: String) {
        bookVars.getOrPut(bookKey) { HashMap() }[key] = value
    }

    // ------------------------------------------------------------ bookshelf

    data class ShelfEntry(
        val book: LegadoBook,
        var chapterIndex: Int = 0,
        var chapterName: String = "",
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private fun loadShelf(): MutableList<ShelfEntry> {
        val text = KV.get(SHELF_KEY, "")
        if (text.isBlank()) return ArrayList()
        val out = ArrayList<ShelfEntry>()
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = LegadoBook(
                    sourceKey = o.optString("sourceKey"),
                    sourceName = o.optString("sourceName"),
                    sourceType = o.optInt("sourceType"),
                    bookUrl = o.optString("bookUrl"),
                    name = o.optString("name"),
                    author = o.optString("author"),
                    intro = o.optString("intro"),
                    coverUrl = o.optString("coverUrl"),
                    kind = o.optString("kind"),
                    lastChapter = o.optString("lastChapter"),
                    tocUrl = o.optString("tocUrl"),
                )
                out.add(
                    ShelfEntry(
                        book = b,
                        chapterIndex = o.optInt("chapterIndex"),
                        chapterName = o.optString("chapterName"),
                        updatedAt = o.optLong("updatedAt"),
                    ),
                )
            }
        }
        return out
    }

    private fun persistShelf(list: List<ShelfEntry>) {
        val arr = JSONArray()
        for (e in list) {
            val b = e.book
            arr.put(
                JSONObject()
                    .put("sourceKey", b.sourceKey)
                    .put("sourceName", b.sourceName)
                    .put("sourceType", b.sourceType)
                    .put("bookUrl", b.bookUrl)
                    .put("name", b.name)
                    .put("author", b.author)
                    .put("intro", b.intro)
                    .put("coverUrl", b.coverUrl)
                    .put("kind", b.kind)
                    .put("lastChapter", b.lastChapter)
                    .put("tocUrl", b.tocUrl)
                    .put("chapterIndex", e.chapterIndex)
                    .put("chapterName", e.chapterName)
                    .put("updatedAt", e.updatedAt),
            )
        }
        KV.put(SHELF_KEY, arr.toString())
    }

    val shelf = MutableStateFlow(loadShelf().sortedByDescending { it.updatedAt })

    fun shelfEntry(bookKey: String): ShelfEntry? = shelf.value.firstOrNull { it.book.bookKey == bookKey }

    fun addToShelf(book: LegadoBook) {
        val list = loadShelf()
        if (list.none { it.book.bookKey == book.bookKey }) {
            list.add(0, ShelfEntry(book))
            persistShelf(list)
            shelf.value = list.sortedByDescending { it.updatedAt }
        }
    }

    fun removeFromShelf(bookKey: String) {
        val list = loadShelf().filterNot { it.book.bookKey == bookKey }
        persistShelf(list)
        shelf.value = list.sortedByDescending { it.updatedAt }
    }

    fun updateProgress(bookKey: String, chapterIndex: Int, chapterName: String) {
        val list = loadShelf()
        val entry = list.firstOrNull { it.book.bookKey == bookKey } ?: return
        entry.chapterIndex = chapterIndex
        entry.chapterName = chapterName
        entry.updatedAt = System.currentTimeMillis()
        persistShelf(list)
        shelf.value = list.sortedByDescending { it.updatedAt }
    }
}
