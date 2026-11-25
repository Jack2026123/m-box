package com.github.tvbox.osc.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.net.SearchHelper
import com.github.tvbox.osc.net.SearchSettings
import com.github.tvbox.osc.reading.T2S
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.UA
import com.github.tvbox.osc.util.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SearchViewModel : ViewModel() {

    enum class ResultState { Pending, Done }

    data class SourceResult(
        val sourceKey: String,
        val sourceName: String,
        val state: ResultState,
        val videos: List<Movie.Video>,
        val arrivedAt: Int = Int.MAX_VALUE,
        val page: Int = 1,
        val firstPageSize: Int = 0,
        val loadingNext: Boolean = false,
        val hasMore: Boolean = false,
    )

    val results = MutableStateFlow<List<SourceResult>>(emptyList())
    val running = MutableStateFlow(false)
    val searchedTitle = MutableStateFlow("")
    val exactMatch = MutableStateFlow(false)
    val sitesEmpty = MutableStateFlow(false)

    val hotSearch = MutableStateFlow<List<String>>(emptyList())

    val suggest = MutableStateFlow<List<String>>(emptyList())

    private var suggestJob: Job? = null

    private var token = 0
    private var arriveSeq = 0
    private var semaphorePermits = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
    private var semaphore = Semaphore(semaphorePermits)
    private val pendingSources = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val nextPagePending = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val scope = viewModelScope

    companion object {
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        // 单站超时见 SearchSettings.searchTimeoutMs()（对齐昏黄 TIMEOUT_SEARCH 默认 30s）

        private const val DOUBAN_HOT_URL =
            "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range="

        private const val HOT_SEARCH_LIMIT = 20

        private const val SUGGEST_URL = "https://suggest.video.iqiyi.com/?if=mobile&key="

        private const val SUGGEST_LIMIT = 20

        @Volatile
        var checkedSources: HashMap<String, String>? = null
            private set

        @Volatile
        private var checkedSourcesApiUrl: String? = null

        @JvmStatic
        fun clearCheckedSources() {
            checkedSources = null
            checkedSourcesApiUrl = null
        }

        @JvmStatic
        fun loadCheckedSources() {
            val selection = SearchSettings.currentSelection()
            if (selection != null) {
                checkedSources = HashMap<String, String>().apply { selection.forEach { put(it, "1") } }
                checkedSourcesApiUrl = KV.get(HawkConfig.API_URL, "")
                return
            }
            val all = SearchHelper.getSources()
            if (all.isEmpty()) {
                checkedSources = null
                checkedSourcesApiUrl = null
                return
            }
            checkedSources = all
            checkedSourcesApiUrl = KV.get(HawkConfig.API_URL, "")
        }

        @JvmStatic
        fun isCheckedSourcesStale(): Boolean {
            if (checkedSources == null) return true
            if (checkedSourcesApiUrl != KV.get(HawkConfig.API_URL, "")) return true
            return SearchHelper.isSelectionStale(checkedSources)
        }
    }

    init {
        org.greenrobot.eventbus.EventBus.getDefault().register(this)
        fetchHotSearch()
    }

    override fun onCleared() {
        org.greenrobot.eventbus.EventBus.getDefault().unregister(this)
        try {
            searchCaller.cancelSearch()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel search requests failed")
        }
    }

    private fun fetchHotSearch() {
        scope.launch(Dispatchers.IO) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(java.util.Date())
            val cached = KV.get(HawkConfig.HOME_HOT, "")
            if (KV.get(HawkConfig.HOME_HOT_DAY, "") == today && cached.isNotEmpty()) {
                hotSearch.value = parseHotTitles(cached)
                return@launch
            }
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            try {
                val body = Http.get(DOUBAN_HOT_URL + year + "," + year) {
                    headers("User-Agent", UA.random())
                }
                if (body.isNotEmpty()) {
                    KV.put(HawkConfig.HOME_HOT, body)
                    KV.put(HawkConfig.HOME_HOT_DAY, today)
                }
                hotSearch.value = parseHotTitles(body)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                hotSearch.value = parseHotTitles(KV.get(HawkConfig.HOME_HOT, ""))
            }
        }
    }

    private fun parseHotTitles(json: String): List<String> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), HOT_SEARCH_LIMIT))
            .mapNotNull { arr.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotEmpty() } }
    } catch (_: Throwable) {
        emptyList()
    }

    fun fetchSuggest(text: String) {
        suggestJob?.cancel()
        suggestJob = scope.launch {
            try {
                val body = Http.get(SUGGEST_URL + java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20"))
                suggest.value = withContext(Dispatchers.IO) { parseSuggest(body) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun clearSuggest() {
        suggestJob?.cancel()
        suggest.value = emptyList()
    }

    private fun parseSuggest(json: String): List<String> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), SUGGEST_LIMIT)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            val title = o.optString("title")
            when {
                name.isNotEmpty() -> name
                title.isNotEmpty() -> title
                else -> null
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun search(title: String) {
        var t = title.trim()
        if (t.isEmpty()) return
        // 对齐昏黄 Trans.t2s：繁体关键词转简再搜
        if (SearchSettings.isKeywordT2sEnabled()) {
            t = T2S.convert(t)
        }
        val configured = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
        if (configured != semaphorePermits) {
            semaphorePermits = configured
            semaphore = Semaphore(semaphorePermits)
        }
        token += 1
        val myToken = token
        val tokenStr = myToken.toString()
        searchedTitle.value = t
        exactMatch.value = SearchSettings.isExactMatchEnabled()
        HistoryHelper.setSearchHistory(title.trim())
        clearSuggest()
        val quickOnly = SearchSettings.isQuickOnlyEnabled()
        val defaultTimeoutMs = SearchSettings.searchTimeoutMs()
        try {
            JsLoader.stopAll()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "JsLoader.stopAll failed, continue new search")
        }
        try {
            searchCaller.cancelSearch()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel previous search requests failed")
        }
        for (entry in pendingSources) {
            entry.value.complete(Unit)
        }
        pendingSources.clear()
        for (entry in nextPagePending) {
            entry.value.complete(Unit)
        }
        nextPagePending.clear()
        val home = ApiConfig.get().getHomeSourceBean()
        val checked = checkedSources
        // 可搜 + 勾选范围；快速模式跳过非 quickSearch 站（对齐昏黄 SearchTask）
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && (checked == null || checked.containsKey(it.key)) }
            .filter { !quickOnly || it.isQuickSearch() }
            .sortedWith(
                compareBy<com.github.tvbox.osc.bean.SourceBean> { it.key != home.key }
                    .thenByDescending { it.isQuickSearch() },
            )
        arriveSeq = 0
        results.value = sources.map { SourceResult(it.key.orEmpty(), it.name.orEmpty(), ResultState.Pending, emptyList()) }
        sitesEmpty.value = sources.isEmpty()
        if (sources.isEmpty()) {
            running.value = false
            return
        }
        running.value = true
        scope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            if (myToken != token) return@async
                            val key = bean.key.orEmpty()
                            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                            pendingSources[key] = done
                            // 站点自带 timeout（秒）优先，否则用全局（对齐昏黄单站时限）
                            val timeoutMs = when {
                                bean.timeout > 0 -> bean.timeout * 1000L
                                else -> defaultTimeoutMs
                            }
                            try {
                                val finished = withTimeoutOrNull(timeoutMs) {
                                    searchCaller.getSearch(key, t, tokenStr)
                                    done.await()
                                    true
                                }
                                // 超时或未回调：标记 Done，避免一直转圈
                                if (finished == null && myToken == token) {
                                    pendingSources.remove(key, done)
                                    markSourceDoneEmpty(key)
                                }
                            } finally {
                                pendingSources.remove(key, done)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (myToken == token) running.value = false
        }
    }

    private fun markSourceDoneEmpty(sourceKey: String) {
        val current = results.value
        if (current.none { it.sourceKey == sourceKey }) return
        results.value = current.map {
            if (it.sourceKey == sourceKey && it.state == ResultState.Pending) {
                it.copy(state = ResultState.Done, videos = emptyList(), arrivedAt = arriveSeq++)
            } else {
                it
            }
        }
    }

@org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    fun onSearchResultEvent(event: com.github.tvbox.osc.event.RefreshEvent) {
        if (event.type != com.github.tvbox.osc.event.RefreshEvent.TYPE_SEARCH_RESULT) return
        val data = event.obj as? AbsXml ?: return
        val myToken = token
        val page = parsePageToken(data.searchToken, myToken)
        if (page == null && data.searchToken != myToken.toString()) return
        val sourceKey = data.sourceKey ?: return
        if (results.value.none { it.sourceKey == sourceKey }) return
        val videos = data.movie?.videoList.orEmpty()
            .filter { !exactMatch.value || SearchSettings.isExactMatch(it.name, searchedTitle.value) }
            .sortedByDescending { it.name?.trim() == searchedTitle.value }
        if (page != null) {
            updateResultPage(sourceKey, page, videos)
            nextPagePending.remove(sourceKey)?.complete(Unit)
        } else {
            pendingSources.remove(sourceKey)?.complete(Unit)
            updateResult(sourceKey, videos)
        }
    }

    private fun updateResult(sourceKey: String, videos: List<Movie.Video>) {
        results.value = results.value.map {
            if (it.sourceKey == sourceKey) {
                SourceResult(sourceKey, it.sourceName, ResultState.Done, videos, ++arriveSeq, 1, videos.size, false, videos.isNotEmpty())
            } else {
                it
            }
        }
    }

    private fun parsePageToken(searchToken: String?, myToken: Int): Int? {
        if (searchToken == null) return null
        val parts = searchToken.split(":")
        if (parts.size != 3) return null
        if (parts[0] != myToken.toString()) return null
        if (parts[1] != "page") return null
        val page = parts[2].toIntOrNull() ?: return null
        if (page < 2) return null
        return page
    }

    private fun pageToken(myToken: Int, page: Int): String {
        return "$myToken:page:$page"
    }

    private fun videoKey(sourceKey: String, video: Movie.Video): String {
        val id = video.id?.trim()
        if (!id.isNullOrEmpty()) return sourceKey + "|" + id
        return sourceKey + "|" + (video.name?.trim().orEmpty()) + "|" + (video.pic?.trim().orEmpty())
    }

    fun loadNextPages(selectedSource: String?) {
        if (selectedSource == null) {
            return
        }
        loadNextPage(selectedSource)
    }

    fun loadNextPage(sourceKey: String) {
        val current = results.value.firstOrNull { it.sourceKey == sourceKey } ?: return
        if (current.state != ResultState.Done) return
        if (!current.hasMore || current.loadingNext) return
        val title = searchedTitle.value
        if (title.isEmpty()) return
        val myToken = token
        val nextPage = current.page + 1
        results.value = results.value.map {
            if (it.sourceKey == sourceKey) it.copy(loadingNext = true) else it
        }
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        nextPagePending[sourceKey] = done
        scope.launch {
            try {
                withTimeoutOrNull(SearchSettings.searchTimeoutMs()) {
                    searchCaller.getSearchPage(sourceKey, title, pageToken(myToken, nextPage), nextPage)
                    done.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                nextPagePending.remove(sourceKey, done)
            }
            if (myToken != token) return@launch
            val now = results.value.firstOrNull { it.sourceKey == sourceKey } ?: return@launch
            if (now.loadingNext && now.page == current.page) {
                results.value = results.value.map {
                    if (it.sourceKey == sourceKey) it.copy(loadingNext = false) else it
                }
            }
        }
    }

    private fun updateResultPage(sourceKey: String, page: Int, videos: List<Movie.Video>) {
        val current = results.value.firstOrNull { it.sourceKey == sourceKey } ?: return
        if (current.state != ResultState.Done) return
        if (page != current.page + 1) return
        if (videos.isEmpty()) {
            results.value = results.value.map {
                if (it.sourceKey == sourceKey) it.copy(loadingNext = false, hasMore = false) else it
            }
            return
        }
        val existingKeys = current.videos.map { videoKey(sourceKey, it) }.toHashSet()
        val fresh = videos.filter { videoKey(sourceKey, it) !in existingKeys }
        if (fresh.isEmpty()) {
            results.value = results.value.map {
                if (it.sourceKey == sourceKey) it.copy(loadingNext = false, hasMore = false) else it
            }
            return
        }
        val shortPage = current.firstPageSize >= 2 && videos.size < current.firstPageSize / 2
        results.value = results.value.map {
            if (it.sourceKey == sourceKey) {
                it.copy(videos = it.videos + fresh, page = page, loadingNext = false, hasMore = !shortPage)
            } else {
                it
            }
        }
    }

    private val searchCaller = SourceViewModel()
}
