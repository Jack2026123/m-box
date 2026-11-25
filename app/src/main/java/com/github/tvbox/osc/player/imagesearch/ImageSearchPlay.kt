package com.github.tvbox.osc.player.imagesearch

import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.net.SearchSettings
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 识图结果点播：拿 avscan 回的编号去找片播。
 * 先当前源（正在看的那个源最可能有），再 isQuickSearch() 的源先，其余按源列表
 * 原序；搜到第一条就回，哪个源都没有回 null。
 * 整段跑在新起的后台线程，回调也在后台线程，调用方自己回主线程。
 * 每个源单独一个 SourceViewModel：它的 searchResult 是 replay=1 的 SharedFlow，
 * 复用一个实例会吃到上个源的结果。
 * 3.69：改成正常搜索模型（照 SearchViewModel.search 那套）——Semaphore 限流
 * （permits 取 KV 的 SEARCH_THREADS，默认 32），全部可搜源一次起 async 抢
 * permit，命中随到随出；之前是 8 个一批、每批 runBlocking 等最慢的源，911 个
 * 源全扫最慢近一小时。单源超时取本站 timeout（秒）优先，否则全局超时。
 * 取消：每轮 sweep 一个 token，旧 token 的回调一律丢弃；cancelSearch() 让
 * token 自增并取消在跑的 sweep Job，searchAll / playFirst 入口先取消上一轮。
 */
object ImageSearchPlay {

    private val searchToken = java.util.concurrent.atomic.AtomicInteger(0)
    private val sweepLock = Any()
    private var sweepJob: Job? = null

    /**
     * 3.67：截图（播放页 🔍）和分享识图共用——点编号找源之前，先保证源列表已加载。
     * 播放页里配置一般已加载，这步只是 isNotEmpty() 一下；冷启动/配置还没装进来
     * 时才走 loadConfig(true)（读缓存，快），等它装完再搜；30 秒还没装完就按
     * 空列表算。searchFirst/searchAll 都跑在后台线程，这里等锁不会卡界面。
     */
    private fun ensureSourcesLoadedSync(): Boolean {
        try {
            if (ApiConfig.get().getSourceBeanList().isNotEmpty()) return true
        } catch (e: Exception) {
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            ApiConfig.get().loadConfig(true, object : ApiConfig.LoadConfigCallback {
                override fun success() {
                    ready.set(try {
                        ApiConfig.get().getSourceBeanList().isNotEmpty()
                    } catch (e: Exception) {
                        false
                    })
                    latch.countDown()
                }

                override fun error(msg: String?) {
                    latch.countDown()
                }

                override fun notice(msg: String?) {
                }
            }, null)
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
        }
        return ready.get()
    }

    private fun searchableSources(): List<SourceBean> {
        if (!ensureSourcesLoadedSync()) return emptyList()
        return try {
            ApiConfig.get().getSourceBeanList().filter { it.isSearchable() }
        } catch (e: Exception) {
            LOG.i("[avscan] play list fail " + e.javaClass.simpleName)
            emptyList()
        }
    }

    /**
     * 取消在跑的一轮 sweep：token 自增（旧轮的 onHit / onProgress / onDone
     * 之后一律丢弃），并取消已登记的 sweep Job。findSourceName 不登记 Job，
     * 它只靠 token 丢弃回调，见 findSourceName 注释。
     */
    fun cancelSearch() {
        val job: Job?
        synchronized(sweepLock) {
            searchToken.incrementAndGet()
            job = sweepJob
            sweepJob = null
        }
        job?.cancel()
    }

    private fun beginSweep(): Int {
        cancelSearch()
        return searchToken.get()
    }

    private fun registerSweepJob(job: Job, myToken: Int) {
        synchronized(sweepLock) {
            if (myToken == searchToken.get()) sweepJob = job
        }
    }

    private fun unregisterSweepJob(job: Job) {
        synchronized(sweepLock) {
            if (sweepJob === job) sweepJob = null
        }
    }

    /**
     * 排序：当前源先，再 isQuickSearch() 先，其余保持原序（sortedWith 是稳定
     * 排序，同级不换位）。跟 SearchViewModel.search 的 home 先、quick 先同款。
     */
    private fun orderSources(all: List<SourceBean>, currentSourceKey: String?): List<SourceBean> {
        return all.sortedWith(
            compareBy<SourceBean> { it.key != currentSourceKey }
                .thenByDescending { it.isQuickSearch() }
        )
    }

    fun playFirst(code: String, currentSourceKey: String?, callback: (Movie.Video?, Boolean) -> Unit) {
        Thread({
            val myToken = beginSweep()
            try {
                runBlocking {
                    // runBlocking 自带 Job，coroutineContext[Job] 在这里恒非空
                    val myJob = coroutineContext[Job]!!
                    registerSweepJob(myJob, myToken)
                    try {
                        val (video, sourcesEmpty) = searchFirstInternal(code, currentSourceKey, myToken)
                        if (myToken == searchToken.get()) callback(video, sourcesEmpty)
                    } finally {
                        unregisterSweepJob(myJob)
                    }
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                LOG.i("[avscan] play sweep fail " + e.javaClass.simpleName)
            }
        }, "avscan-play").start()
    }

    /**
     * 找源名：拿编号去找片（跟点编号播放同一套搜法），只回源的显示名（如“金牌”），
     * 哪个源都没有回 null。整段跑在新起的后台线程，回调也在后台线程，调用方
     * 自己 post 回主线程。结果卡上的“属于哪个源”就靠它。
     * 注意：播放页结果列表会给每个结果各调一次 findSourceName（循环里连发），
     * 所以这里不走 beginSweep、不取消上一轮——只拿当前 token 跑，token 变了
     * （searchAll / playFirst 新开一轮或 cancelSearch）回调才丢弃；多路并行
     * 找源名是现行行为，保持不动。
     */
    fun findSourceName(code: String, currentSourceKey: String?, callback: (String?) -> Unit) {
        Thread({
            val myToken = searchToken.get()
            try {
                runBlocking {
                    val video = searchFirst(code, currentSourceKey, myToken)
                    if (myToken != searchToken.get()) return@runBlocking
                    val key = video?.sourceKey.orEmpty()
                    val name = if (key.isEmpty()) null else try {
                        ApiConfig.get().getSourceBeanList().firstOrNull { it.key == key }?.name
                    } catch (e: Exception) {
                        null
                    }
                    LOG.i("[avscan] source for " + code + " = " + (name ?: "none"))
                    callback(name)
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                LOG.i("[avscan] play sweep fail " + e.javaClass.simpleName)
            }
        }, "avscan-source").start()
    }

    private suspend fun searchFirst(code: String, currentSourceKey: String?, myToken: Int): Movie.Video? {
        return searchFirstInternal(code, currentSourceKey, myToken).first
    }

    private suspend fun searchFirstInternal(code: String, currentSourceKey: String?, myToken: Int): Pair<Movie.Video?, Boolean> {
        val all = searchableSources()
        if (all.isEmpty()) return Pair(null, true)
        if (myToken != searchToken.get()) return Pair(null, false)
        val ordered = orderSources(all, currentSourceKey)
        val hit = runSweep(
            code = code,
            ordered = ordered,
            myToken = myToken,
            firstOnly = true,
            onHit = null,
            onProgress = null,
        )
        return Pair(hit, false)
    }

    /**
     * 找全部源：拿编号去所有可搜源里找，边找边回（3.66 分享识图“已出 N 源”页用）。
     * 全部可搜源一次起 async 抢 Semaphore permit，命中立刻回 onHit（随到随出，
     * 不等批），每源落定（命中 / 空 / 超时）都回 onProgress（已搜 N / 共 M），
     * 全部落定回 onDone。onStart 先回可搜源总数（页头“共 M 源”用）。
     * 入口先取消上一轮（点另一个编号时旧 sweep 自动停）。
     * 整段跑在新起的后台线程，回调也在后台线程，调用方自己 post 回主线程。
     */
    fun searchAll(
        code: String,
        currentSourceKey: String?,
        onStart: (Int) -> Unit,
        onHit: (Movie.Video) -> Unit,
        onDone: () -> Unit,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        Thread({
            val myToken = beginSweep()
            try {
                runBlocking {
                    // runBlocking 自带 Job，coroutineContext[Job] 在这里恒非空
                    val myJob = coroutineContext[Job]!!
                    registerSweepJob(myJob, myToken)
                    try {
                        // 3.67：跟 searchFirst 共用 searchableSources，先保证源列表已加载再搜。
                        val all = searchableSources()
                        if (myToken != searchToken.get()) return@runBlocking
                        if (all.isEmpty()) {
                            onStart(0)
                            onDone()
                            return@runBlocking
                        }
                        val ordered = orderSources(all, currentSourceKey)
                        onStart(ordered.size)
                        runSweep(
                            code = code,
                            ordered = ordered,
                            myToken = myToken,
                            firstOnly = false,
                            onHit = onHit,
                            onProgress = onProgress,
                        )
                        if (myToken == searchToken.get()) onDone()
                    } finally {
                        unregisterSweepJob(myJob)
                    }
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                LOG.i("[avscan] play sweep fail " + e.javaClass.simpleName)
            }
        }, "avscan-searchall").start()
    }

    /**
     * 共享 sweep 引擎：全部源一次起 async，只在拿到 permit 后才建
     * SourceViewModel、才发请求（911 个 async 一次起可行，permit 外不做重活）。
     * firstOnly = true：第一个命中就赢，其余取消（searchFirst / playFirst /
     * findSourceName 用）；false：收齐全部命中（searchAll 用）。
     * 每源落定都计进度；token 不一致时 onHit / onProgress 一律不调。
     */
    private suspend fun runSweep(
        code: String,
        ordered: List<SourceBean>,
        myToken: Int,
        firstOnly: Boolean,
        onHit: ((Movie.Video) -> Unit)?,
        onProgress: ((Int, Int) -> Unit)?,
    ): Movie.Video? = coroutineScope {
        val permits = try {
            KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
        } catch (e: Exception) {
            HawkConfig.SEARCH_THREADS_DEFAULT
        }
        val semaphore = Semaphore(if (permits < 1) 1 else permits)
        val total = ordered.size
        val searched = java.util.concurrent.atomic.AtomicInteger(0)
        val firstHit = CompletableDeferred<Movie.Video>()
        val deferreds = ordered.map { bean ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val video = searchOne(bean, code)
                    val settled = searched.incrementAndGet()
                    if (myToken == searchToken.get()) {
                        if (video != null) {
                            if (firstOnly) {
                                firstHit.complete(video)
                            } else {
                                onHit?.invoke(video)
                            }
                        }
                        onProgress?.invoke(settled, total)
                    }
                    video
                }
            }
        }
        if (!firstOnly) {
            deferreds.awaitAll()
            null
        } else {
            val allDone = async { deferreds.awaitAll() }
            val hit = select<Movie.Video?> {
                firstHit.onAwait { it }
                allDone.onAwait { null }
            }
            if (hit != null) {
                deferreds.forEach { it.cancel() }
                allDone.cancel()
            }
            hit
        }
    }

    /**
     * 单源搜一次：独立 SourceViewModel + getSearch，withTimeoutOrNull 等
     * flow.first()。超时取本站 timeout（秒）优先，否则全局超时。
     * 超时 / 空结果回 null；异常回 null 并记日志。取消（CancellationException）
     * 必须原样抛出，不能当普通异常吞掉，否则 sweep 停不下来。
     */
    private suspend fun searchOne(bean: SourceBean, code: String): Movie.Video? {
        val key = bean.key.orEmpty()
        val timeoutMs = if (bean.timeout > 0) {
            bean.timeout * 1000L
        } else {
            try {
                SearchSettings.searchTimeoutMs()
            } catch (e: Exception) {
                15000L
            }
        }
        try {
            val svm = SourceViewModel()
            svm.getSearch(key, code, "avscan-" + System.currentTimeMillis())
            val absXml = withTimeoutOrNull(timeoutMs) { svm.searchResult.flow.first() }
            if (absXml == null) {
                LOG.i("[avscan] play search " + key + " timeout")
                return null
            }
            val video = absXml.movie?.videoList?.firstOrNull { !it.id.isNullOrEmpty() }
            if (video != null) {
                if (video.sourceKey.isNullOrEmpty()) video.sourceKey = key
                LOG.i("[avscan] play hit " + key + " " + (video.name ?: ""))
                return video
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.i("[avscan] play search " + key + " fail " + e.javaClass.simpleName)
        }
        return null
    }
}
