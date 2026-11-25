package com.github.tvbox.osc.music

import com.github.catvod.crawler.DebugLogStore
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 音乐总仓库。UI 只认它，不碰 LxRuntime / LxSearch。
 *
 * 三段：搜歌 → 取链 → 交给播放器。
 *
 * 关键设计：
 *  1. 搜歌并行跑所有启用的源，先到的先显示（不串行等最慢的那个）
 *  2. 取链失败时**按歌名+歌手在其它源重搜**再取，而不是拿别家的 extra 硬解
 *     （2.68 之前的坑：MF 歌的 extra 里没有洛雪的 songmid/hash，硬解必失败）
 *  3. 所有耗时操作都可以在 IO 线程调
 */
object MusicRepository {

    /** 搜索态：让 UI 能显示"正在搜 3 个源"而不是干转圈 */
    private val searchingFlow = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = searchingFlow

    /** 上一次搜索的失败摘要（只给一行小灰字，不倒堆栈） */
    private val lastErrorFlow = MutableStateFlow("")
    val lastError: StateFlow<String> = lastErrorFlow

    /** 搜索历史 */
    private val historyFlow = MutableStateFlow(loadHistory())
    val history: StateFlow<List<String>> = historyFlow

    // ============================================================ 搜歌

    /**
     * 搜歌。所有启用的源并行。
     *
     * @param sourceId 传 null 表示搜全部源
     */
    suspend fun search(
        keyword: String,
        sourceId: String? = null,
        page: Int = 1,
        platform: String? = null,
    ): List<MusicSong> = withContext(Dispatchers.IO) {
        val query = keyword.trim()
        if (query.isEmpty()) return@withContext emptyList()

        // 按健康度排序：好用的源先跑，坏源排后面不拖累主线
        val candidates = MusicStore.searchableRanked().let { list ->
            if (sourceId != null) list.filter { it.id == sourceId } else list
        }.filter {
            // MusicFree 引擎没开时，直接跳过插件源（不影响洛雪）
            it.kind != MusicSourceKind.MUSIC_FREE || MusicStore.musicFreeEnabled.value
        }
        if (candidates.isEmpty()) {
            lastErrorFlow.value = "没有可用音源"
            return@withContext emptyList()
        }

        searchingFlow.value = true
        lastErrorFlow.value = ""
        rememberHistory(query)

        val collected = java.util.Collections.synchronizedList(ArrayList<MusicSong>())
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        val failures = java.util.Collections.synchronizedList(ArrayList<String>())

        try {
            // 多个源并行：一个源挂了不影响别的
            val threads = candidates.map { source ->
                Thread {
                    try {
                        // 按源类型分流：洛雪走事件协议，MusicFree 走 module 协议
                        val got = when (source.kind) {
                            MusicSourceKind.LX -> searchByLx(source, query, page, collected, seen, failures, platform)
                            MusicSourceKind.MUSIC_FREE ->
                                searchByMusicFree(source, query, page, collected, seen, failures)
                        }
                        MusicStore.noteHealth(source.id, got > 0)
                    } catch (th: Throwable) {
                        LOG.e("music-search-source-failed " + source.name + " : " + th.message)
                        failures.add(source.name)
                        MusicStore.noteHealth(source.id, false)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(20_000) }

            val songs = ArrayList(collected)
            if (songs.isEmpty() && failures.isNotEmpty()) {
                lastErrorFlow.value = failures.size.toString() + " 个源暂不可用"
            }
            LOG.i("music-search [" + query + "] -> " + songs.size + " 首, 失败源 " + failures.size)
            songs
        } finally {
            searchingFlow.value = false
        }
    }

    // ============================================================ 取链

    /** 洛雪源的搜索 */
    private fun searchByLx(
        source: MusicSource,
        query: String,
        page: Int,
        collected: MutableList<MusicSong>,
        seen: MutableSet<String>,
        failures: MutableList<String>,
        platform: String? = null,
    ): Int {
        val runtime = LxRuntime.obtain(source)
        if (runtime == null) {
            failures.add(source.name)
            return 0
        }
        if (runtime.lastError.isNotBlank() && !runtime.isReady) {
            failures.add(source.name + "(" + runtime.lastError + ")")
            return 0
        }
        // 一个源可能支持多个平台，全都搜；指定了平台就只搜那一个
        val platforms = if (!platform.isNullOrBlank()) {
            setOf(platform)
        } else {
            runtime.sourcePlatforms.ifEmpty { setOf("kg") }
        }
        var got = 0
        for (platform in platforms) {
            val pageResult = LxSearch.search(
                runtime = runtime,
                platform = platform,
                keyword = query,
                page = page,
                sourceId = source.id,
                sourceName = source.name,
            )
            for (song in pageResult.songs) {
                // 跨源去重：同平台同 id 只留第一份
                if (seen.add(song.dedupeKey)) collected.add(song)
            }
            got += pageResult.songs.size
        }
        return got
    }

    /** MusicFree 插件的搜索 */
    private fun searchByMusicFree(
        source: MusicSource,
        query: String,
        page: Int,
        collected: MutableList<MusicSong>,
        seen: MutableSet<String>,
        failures: MutableList<String>,
    ): Int {
        val host = MusicFreeHost.obtain(source)
        if (!host.isReady) {
            failures.add(source.name + "(" + host.lastError.ifBlank { "未就绪" } + ")")
            return 0
        }
        val pageResult = host.search(query, page)
        var got = 0
        for (raw in pageResult.songs) {
            // MusicFree 的 song 没有 sourceId（mapper 里留空），这里补上
            val song = raw.copy(sourceId = source.id, sourceName = source.name)
            if (seen.add(song.dedupeKey)) collected.add(song)
            got++
        }
        return got
    }

    /**
     * 取播放地址。
     *
     * 先用自己的源；失败则按「歌名 + 歌手」在其它源重搜一首同款，
     * 拿它的洛雪原生 extra 再取——这条兜底是 2.68 之前的经验，别省。
     */
    suspend fun media(song: MusicSong, quality: String? = null): MusicMedia? =
        withContext(Dispatchers.IO) {
            // 1. 自身源取链
            var result = tryOwnSource(song, quality)
            if (result != null) return@withContext result

            // 2. 兜底：重搜
            LOG.i("music-media fallback for [" + song.title + " - " + song.artist + "]")
            val others = MusicStore.searchable().filter { it.id != song.sourceId }
            for (other in others) {
                val runtime = runCatching { LxRuntime.obtain(other) }.getOrNull() ?: continue
                if (!runtime.isReady) continue
                val keyword = (song.title + " " + song.artist).trim()
                val platforms = runtime.sourcePlatforms.ifEmpty { setOf("kg") }
                for (platform in platforms) {
                    val page = runCatching {
                        LxSearch.search(runtime, platform, keyword, 1, other.id, other.name)
                    }.getOrNull() ?: continue
                    // 找同名同歌手的最接近的一首
                    val match = page.songs.firstOrNull { it.title == song.title } ?: continue
                    val media = runCatching { tryOwnSource(match, quality) }.getOrNull()
                    if (media != null) {
                        LOG.i("music-media fallback hit via " + other.name)
                        return@withContext media
                    }
                }
            }
            lastErrorFlow.value = "取不到播放地址"
            null
        }

    private fun tryOwnSource(song: MusicSong, quality: String?): MusicMedia? {
        val source = MusicStore.find(song.sourceId) ?: return null

        // ------------------------------------------------------------------
        // extra 完整性校验 + 自愈
        //
        // 取链时脚本只认 musicInfo 里的平台标识字段（kg 要 hash、tx 要 songmid、
        // kw 要 DC_TARGETID...）。如果歌曲来自「我喜欢」「历史队列」等持久化数据，
        // 早期版本存的 extra 可能没有这些字段，脚本就会抛：
        //   kg song hash missing / cannot read property 'songmid' of undefined
        // 表现就是「能搜到、点了播不出」。
        //
        // 这里先检查，缺了就在同平台重搜一首同名歌，拿它完整的 extra 覆盖过来，
        // 源本身没问题时这条路一定能救回来。
        // ------------------------------------------------------------------
        val usable = if (hasPlatformId(song)) song else refillExtra(song) ?: song

        val levels = buildList {
            // 用户手动选过的档位优先
            MusicSession.qualityFor(usable)?.let { add(it) }
            if (!quality.isNullOrBlank()) add(quality)
            if (source.kind == MusicSourceKind.MUSIC_FREE) {
                // MusicFree 用本体档位名
                addAll(MusicFreeMapper.QUALITIES)
            } else {
                addAll(MusicQuality.preferOrder(usable.qualities, usable.platform))
            }
        }

        if (source.kind == MusicSourceKind.MUSIC_FREE) {
            // 引擎没开就不尝试，避免摸到未验证的 QuickJS Promise 桥
            if (!MusicStore.musicFreeEnabled.value) {
                LOG.i("mf-media skipped: engine disabled")
                return null
            }
            return tryMusicFreeSource(source, usable, levels)
        }

        val runtime = runCatching { LxRuntime.obtain(source) }.getOrNull() ?: return null
        if (!runtime.isReady) return null

        for (level in levels.distinct()) {
            val url = runCatching { runtime.getMusicUrl(usable.extra, level) }.getOrNull()
            if (!url.isNullOrBlank()) {
                MusicStore.noteHealth(usable.sourceId, true)
                return MusicMedia(url = url, headers = headersFor(usable.platform), quality = level)
            }
        }
        MusicStore.noteHealth(usable.sourceId, false)
        LOG.i(
            "music-media own-source-miss [" + usable.title + "] q=" + levels.joinToString("/") +
                " err=" + runtime.lastError +
                " extraKeys=" + usable.extra.keys().asSequence().joinToString(","),
        )
        return null
    }

    /** 判断 extra 里有没有脚本认的平台标识字段 */
    private fun hasPlatformId(song: MusicSong): Boolean {
        val e = song.extra
        return when (song.platform) {
            "kg" -> e.has("hash") || e.has("types")
            "tx" -> e.has("songmid") || e.has("songId") || e.has("mid")
            "kw" -> e.has("DC_TARGETID") || e.has("rid") || e.has("musicrid")
            "mg" -> e.has("copyrightId")
            "wy" -> e.has("id")
            else -> e.length() > 0
        }
    }

    /**
     * 在同平台重搜，拿一首同名歌的完整 extra。
     *
     * 这是「老数据播不出」的兜底：源和脚本都没坏，只是队列里存的那份 extra 不全。
     */
    private fun refillExtra(song: MusicSong): MusicSong? {
        val source = MusicStore.find(song.sourceId) ?: return null
        if (source.kind != MusicSourceKind.LX) return null
        val runtime = runCatching { LxRuntime.obtain(source) }.getOrNull() ?: return null
        if (!runtime.isReady) return null
        val keyword = (song.title + " " + song.artist).trim()
        if (keyword.isEmpty()) return null
        val page = runCatching {
            LxSearch.search(runtime, song.platform, keyword, 1, source.id, source.name)
        }.getOrNull() ?: return null
        val match = page.songs.firstOrNull { it.title == song.title && it.artist == song.artist }
            ?: page.songs.firstOrNull { it.title == song.title }
            ?: return null
        if (!hasPlatformId(match)) return null
        LOG.i("music-media extra refilled [" + song.title + "] keys=" +
            match.extra.keys().asSequence().joinToString(","))
        return song.copy(extra = match.extra, qualities = match.qualities)
    }

    /**
     * MusicFree 源的取链。
     *
     * 只调一次 getMediaSource，把整个 extra 原样回传——
     * 插件靠里面的自定义字段认歌，我们不该按档位循环重试
     * （每次重试都是完整一次插件执行，很贵）。
     * 档位只作为提示传进去，插件不支持就自己降级。
     */
    private fun tryMusicFreeSource(
        source: MusicSource,
        song: MusicSong,
        levels: List<String>,
    ): MusicMedia? {
        val host = runCatching { MusicFreeHost.obtain(source) }.getOrNull() ?: return null
        if (!host.isReady) {
            LOG.i("mf-media not-ready " + source.name + " : " + host.lastError)
            return null
        }
        val primary = levels.firstOrNull() ?: "standard"
        val itemJson = song.extra.toString()
        val media = runCatching { host.getMediaSource(itemJson, primary) }.getOrNull()

        if (media == null || media.url.isBlank()) {
            MusicStore.noteHealth(source.id, false)
            LOG.i(
                "mf-media miss [" + song.title + "] quality=" + primary +
                    " plugin=" + source.name,
            )
            return null
        }

        MusicStore.noteHealth(source.id, true)
        LOG.i("mf-media ok [" + song.title + "] quality=" + primary + " plugin=" + source.name)
        return media.copy(
            headers = if (media.headers.isEmpty()) headersFor(song.platform) else media.headers,
            quality = media.quality.ifBlank { primary },
        )
    }

    /** 各平台取到的直链普遍需要 Referer，否则 403 */
    private fun headersFor(platform: String): Map<String, String> = when (platform) {
        "kg" -> mapOf("User-Agent" to UA_PC)
        "kw" -> mapOf("User-Agent" to UA_PC, "Referer" to "http://www.kuwo.cn/")
        "tx" -> mapOf("User-Agent" to UA_PC, "Referer" to "https://y.qq.com/")
        "wy" -> mapOf("User-Agent" to UA_PC, "Referer" to "https://music.163.com/")
        "mg" -> mapOf("User-Agent" to UA_ANDROID)
        else -> mapOf("User-Agent" to UA_PC)
    }

    // ============================================================ 歌词

    /** 拉歌词。洛雪脚本不负责歌词，走公开的歌词接口。 */
    suspend fun lyric(song: MusicSong): String? = lyricResult(song).text

    enum class LyricStatus { FOUND, INSTRUMENTAL, NOT_FOUND }

    data class LyricResult(val status: LyricStatus, val text: String?)

    /**
     * 拉歌词（带空态，2.95）：锁定/导入 > 纯音乐标记 > MusicFree 插件 >
     * 平台链（网易→QQ→酷狗）> LRCLIB 兜底。
     */
    suspend fun lyricResult(song: MusicSong): LyricResult = withContext(Dispatchers.IO) {
        val key = MusicLyricOffset.songKey(song)
        // 手动锁定/导入的歌词优先，锁定后自动拉取不再覆盖
        val locked = MusicLyricSearch.Lock.getLocked(key)
        if (!locked.isNullOrBlank()) {
            DebugLogStore.add(
                "music",
                "歌词来源=锁定（" + MusicLyricSearch.Lock.lockedSource(key) + "） [" +
                    song.title + "] 长度=" + locked.length,
            )
            return@withContext LyricResult(LyricStatus.FOUND, locked)
        }
        // 标记过纯音乐：不再反复拉取
        if (MusicLyricSearch.Lock.isInstrumental(key)) {
            return@withContext LyricResult(LyricStatus.INSTRUMENTAL, null)
        }
        // MusicFree 插件如果实现了 getLyric，优先用它（前提是引擎开着）
        val src = MusicStore.find(song.sourceId)
        if (src != null && src.kind == MusicSourceKind.MUSIC_FREE &&
            MusicStore.musicFreeEnabled.value
        ) {
            val byPlugin = runCatching {
                MusicFreeHost.obtain(src).getLyric(song.extra.toString())
            }.getOrNull()
            if (!byPlugin.isNullOrBlank()) return@withContext LyricResult(LyricStatus.FOUND, byPlugin)
        }

        // 取词顺序（2.82，2026-11-12 实查：网易云那份最准——勇气/唯一与酷狗 hash 版时间一致，
        // 制作信息行都压在 0:00-0:15；QQ 那份把制作信息行拉长到 0:25、酷狗关键词搜曾拿到错版词。
        // 用户定：统一走网易云优先。py 的歌（酷我）也没有 hash，同样走网易云优先）：
        //   有 hash：酷狗 hash 精确查（带逐字时间）→ 网易云 → QQ
        //   没 hash：网易云 → QQ → 酷狗关键词搜
        lastLyricWordTimed = false
        val hash = song.extra.optString("hash")
        val attempts = if (hash.isNotBlank()) {
            listOf(
                "kg" to { fetchKugouLyricByHash(song) },
                "wy" to { fetchNeteaseLyric(song) },
                "tx" to { fetchQqLyric(song) },
            )
        } else {
            listOf(
                "wy" to { fetchNeteaseLyric(song) },
                "tx" to { fetchQqLyric(song) },
                "kg" to { fetchKugouLyricByKeyword(song) },
            )
        }
        for ((name, fetcher) in attempts) {
            val lrc = runCatching { fetcher() }.getOrElse { th ->
                LOG.i("echo-music lyric source failed [$name] " + th.message)
                null
            }
            if (!lrc.isNullOrBlank()) {
                LOG.i("echo-music lyric ok [" + song.title + "] from=" + name + " len=" + lrc.length)
                DebugLogStore.add(
                    "music",
                    "歌词来源=" + name + (if (lastLyricWordTimed) "·逐字" else "") +
                        " [" + song.title + "] 首行=" + firstLyricTag(lrc) + " 长度=" + lrc.length,
                )
                return@withContext LyricResult(LyricStatus.FOUND, lrc)
            }
        }
        // LRCLIB 兜底（2.95）：平台链都没拿到时再试；纯音乐会记标记，下次不再拉
        val lrclib = runCatching { MusicLyricSearch.fetchLrclibLyric(song) }.getOrNull()
        if (lrclib != null) {
            if (lrclib.instrumental) {
                MusicLyricSearch.Lock.setInstrumental(key, true)
                return@withContext LyricResult(LyricStatus.INSTRUMENTAL, null)
            }
            if (!lrclib.text.isNullOrBlank()) {
                LOG.i("echo-music lyric ok [" + song.title + "] from=lrclib len=" + lrclib.text.length)
                DebugLogStore.add(
                    "music",
                    "歌词来源=lrclib" + (if (lrclib.synced) "" else "·纯文本") +
                        " [" + song.title + "] 首行=" + firstLyricTag(lrclib.text) +
                        " 长度=" + lrclib.text.length,
                )
                return@withContext LyricResult(LyricStatus.FOUND, lrclib.text)
            }
        }
        LOG.i("echo-music lyric miss [" + song.title + "] all sources empty")
        LyricResult(LyricStatus.NOT_FOUND, null)
    }

    /**
     * 搜歌词面板的候选（2.95）：按用户改过的“歌名 歌手”查，平台链（网易→QQ→酷狗）
     * + LRCLIB 兜底；逐词优先，其次按时长接近度。只返回能拿到 LRC 文本的候选。
     */
    suspend fun searchLyricCandidates(
        song: MusicSong,
        queryTitle: String,
        queryArtist: String,
    ): List<MusicLyricSearch.LyricCandidate> = withContext(Dispatchers.IO) {
        val title = queryTitle.trim().ifBlank { song.title }
        val artist = queryArtist.trim().ifBlank { song.artist }
        val out = ArrayList<MusicLyricSearch.LyricCandidate>()
        val seen = HashSet<String>()
        fun addCandidate(candidate: MusicLyricSearch.LyricCandidate) {
            val dedupKey = candidate.title + "|" + candidate.artist + "|" + candidate.source
            if (candidate.lrc.isBlank() || !seen.add(dedupKey)) return
            out.add(candidate)
        }
        // 网易云：搜歌名取前 3 个 id，各拉一次歌词
        runCatching {
            val keyword = java.net.URLEncoder.encode(title + " " + artist, "UTF-8")
            val body = httpGetWithHeaders(
                "https://music.163.com/api/search/get?s=$keyword&type=1&limit=3&offset=0",
                mapOf("Referer" to "https://music.163.com/"),
            )
            val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
                ?: return@runCatching
            for (i in 0 until songs.length()) {
                val item = songs.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                if (id <= 0L) continue
                val lrc = neteaseLyricById(id.toString()) ?: continue
                val singer = item.optJSONArray("ar")?.let { ar ->
                    buildList {
                        for (s in 0 until ar.length()) {
                            ar.optJSONObject(s)?.optString("name")
                                ?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }.joinToString("、")
                }.orEmpty()
                addCandidate(
                    MusicLyricSearch.LyricCandidate(
                        title = item.optString("name"),
                        artist = singer,
                        source = "网易云",
                        durationSec = item.optInt("dt", 0) / 1000,
                        wordTimed = MusicLyricSearch.isWordTimed(lrc),
                        preview = MusicLyricSearch.previewOf(lrc),
                        lrc = lrc,
                    ),
                )
            }
        }
        // QQ：搜歌名取前 3 个 mid，各拉一次歌词
        runCatching {
            val keyword = java.net.URLEncoder.encode(title + " " + artist, "UTF-8")
            val body = httpGetWithHeaders(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=3&w=$keyword&format=json",
                mapOf("Referer" to "https://y.qq.com/"),
            )
            val list = JSONObject(body).optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("list") ?: return@runCatching
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val mid = item.optString("songmid")
                if (mid.isBlank()) continue
                val lrc = qqLyricByMid(mid) ?: continue
                val singer = item.optJSONArray("singer")?.let { singers ->
                    buildList {
                        for (s in 0 until singers.length()) {
                            singers.optJSONObject(s)?.optString("name")
                                ?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }.joinToString("、")
                }.orEmpty()
                addCandidate(
                    MusicLyricSearch.LyricCandidate(
                        title = item.optString("songname"),
                        artist = singer,
                        source = "QQ",
                        durationSec = item.optInt("interval", 0),
                        wordTimed = MusicLyricSearch.isWordTimed(lrc),
                        preview = MusicLyricSearch.previewOf(lrc),
                        lrc = lrc,
                    ),
                )
            }
        }
        // 酷狗：关键词搜歌词，取前 3 条（KRC 优先，带逐词时间）
        runCatching {
            val keyword = java.net.URLEncoder.encode(title + " " + artist, "UTF-8")
            val body = httpGet(
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$keyword&duration=&hash=",
            )
            val candidates = JSONObject(body).optJSONArray("candidates") ?: return@runCatching
            for (i in 0 until minOf(candidates.length(), 3)) {
                val c = candidates.optJSONObject(i) ?: continue
                val id = c.optString("id")
                val accessKey = c.optString("accesskey")
                if (id.isBlank() || accessKey.isBlank()) continue
                val lrc = downloadKugouKrc(id, accessKey)
                    ?: downloadKugouLrc(id, accessKey)
                    ?: continue
                addCandidate(
                    MusicLyricSearch.LyricCandidate(
                        title = c.optString("song"),
                        artist = c.optString("singer"),
                        source = "酷狗",
                        durationSec = (c.optLong("duration") / 1000L).toInt(),
                        wordTimed = MusicLyricSearch.isWordTimed(lrc),
                        preview = MusicLyricSearch.previewOf(lrc),
                        lrc = lrc,
                    ),
                )
            }
        }
        // LRCLIB 兜底
        runCatching {
            out.addAll(MusicLyricSearch.fetchLrclibCandidates(song, title, artist))
        }
        // 逐词优先，其次按时长接近度（与当前歌的时长差）
        out.sortWith(
            compareByDescending<MusicLyricSearch.LyricCandidate> { it.wordTimed }
                .thenBy { Math.abs(it.durationSec - song.durationSec) },
        )
        out
    }

    private var lastLyricWordTimed = false

    private fun firstLyricTag(lrc: String): String {
        val m = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]").find(lrc) ?: return "-"
        return m.value
    }

    // ------------------------------------------------------------ 歌词：酷狗

    /**
     * 酷狗歌词。
     *
     * 注意域名是 lyrics.kugou.com 而不是老的 krcs.kugou.com——
     * 后者现在恒返回空 candidates，是这次"没有歌词"的直接原因。
     */
    private fun fetchKugouLyricByHash(song: MusicSong): String? {
        val hash = song.extra.optString("hash")
        if (hash.isBlank()) return null
        return fetchKugouLyricBySearch(
            song,
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=&duration=&hash=" + hash,
            preferKrc = true,
        )
    }

    private fun fetchKugouLyricByKeyword(song: MusicSong): String? {
        val keyword = java.net.URLEncoder.encode(song.title + " " + song.artist, "UTF-8")
        return fetchKugouLyricBySearch(
            song,
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$keyword&duration=&hash=",
            preferKrc = false,
        )
    }

    private fun fetchKugouLyricBySearch(song: MusicSong, searchUrl: String, preferKrc: Boolean): String? {
        val body = httpGet(searchUrl)
        if (body.isBlank()) return null
        val candidates = JSONObject(body).optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        // 取时长最接近的那条，避免拿到同名不同版本
        val picked = pickKugouCandidate(candidates, song)
        val id = picked?.optString("id").orEmpty()
        val accessKey = picked?.optString("accesskey").orEmpty()
        if (id.isEmpty() || accessKey.isEmpty()) return null
        if (preferKrc) {
            val krc = downloadKugouKrc(id, accessKey)
            if (!krc.isNullOrBlank()) {
                lastLyricWordTimed = true
                return krc
            }
        }
        return downloadKugouLrc(id, accessKey)
    }

    private fun downloadKugouLrc(id: String, accessKey: String): String? {
        val content = runCatching {
            JSONObject(
                httpGet(
                    "https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8" +
                        "&id=" + id + "&accesskey=" + accessKey,
                ),
            ).optString("content")
        }.getOrNull().orEmpty()
        if (content.isEmpty()) return null
        val text = runCatching {
            String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull().orEmpty()
        if (text.isBlank()) return null
        return text.replace("\r\n", "\n")
    }

    private fun downloadKugouKrc(id: String, accessKey: String): String? {
        val content = runCatching {
            JSONObject(
                httpGet(
                    "https://lyrics.kugou.com/download?ver=1&client=pc&fmt=krc&charset=utf8" +
                        "&id=" + id + "&accesskey=" + accessKey,
                ),
            ).optString("content")
        }.getOrNull().orEmpty()
        if (content.isEmpty()) return null
        val bytes = runCatching {
            android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        return krcBytesToLrc(bytes)
    }

    // KRC 转带逐字标签的 LRC 文本。KRC 格式：4 字节 "krc1" 头 + 异或加密的 zlib 数据；
    // 解开后每行是 [行起毫秒,行长毫秒]词[词偏毫秒,词长毫秒]词…，词偏相对行起。
    // 转出的词时间写成绝对毫秒（行起 + 词偏），由现有 parseLrc 的 <> 标签路径直接认。
    private fun krcBytesToLrc(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        if (bytes[0] != 'k'.code.toByte() || bytes[1] != 'r'.code.toByte() ||
            bytes[2] != 'c'.code.toByte() || bytes[3] != '1'.code.toByte()
        ) return null
        val key = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, -50, -46, 110, 105,
        )
        val xored = ByteArray(bytes.size - 4)
        for (i in xored.indices) {
            xored[i] = (bytes[i + 4].toInt() xor key[i % key.size].toInt()).toByte()
        }
        val inflated = runCatching {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(xored)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray()
        }.getOrNull() ?: return null
        val text = runCatching { String(inflated, Charsets.UTF_8) }.getOrNull() ?: return null
        val tag = Regex("\\[(\\d+),(\\d+)\\]")
        val outLines = ArrayList<String>()
        for (rawLine in text.lineSequence()) {
            val matches = tag.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val lineStart = matches[0].groupValues[1].toLongOrNull() ?: continue
            val sb = StringBuilder()
            sb.append("[").append(formatLrcTime(lineStart)).append("]")
            if (matches.size == 1) {
                val body = rawLine.substring(matches[0].range.last + 1).trim()
                if (body.isEmpty()) continue
                sb.append(body)
            } else {
                for (i in 1 until matches.size) {
                    val m = matches[i]
                    val offset = m.groupValues[1].toLongOrNull() ?: continue
                    val end = if (i + 1 < matches.size) matches[i + 1].range.first else rawLine.length
                    val word = rawLine.substring(m.range.last + 1, end)
                    if (word.isEmpty()) continue
                    sb.append("<").append(formatLrcTime(lineStart + offset)).append(">").append(word)
                }
            }
            outLines.add(sb.toString())
        }
        if (outLines.isEmpty()) return null
        return outLines.joinToString("\n")
    }

    private fun formatLrcTime(ms: Long): String {
        val totalCs = ms / 10
        val mm = totalCs / 6000
        val ss = (totalCs % 6000) / 100
        val cs = totalCs % 100
        return String.format("%02d:%02d.%02d", mm, ss, cs)
    }

    private fun pickKugouCandidate(
        candidates: org.json.JSONArray,
        song: MusicSong,
    ): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until candidates.length()) {
            val c = candidates.optJSONObject(i) ?: continue
            if (c.optString("accesskey").isBlank()) continue
            val titleScore = if (sameSongName(c.optString("song"), song.title)) 0L else 1_000_000L
            val durScore = runCatching {
                Math.abs(c.optLong("duration") / 1000L - song.durationSec.toLong())
            }.getOrDefault(0L) * 10L
            val score = titleScore + durScore
            if (score < bestScore) {
                bestScore = score
                best = c
            }
        }
        return best ?: candidates.optJSONObject(0)
    }

    private fun sameSongName(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        fun norm(s: String) = s.lowercase().replace(Regex("[\\s\\-_()（）\\[\\]【】]"), "")
        val x = norm(a)
        val y = norm(b)
        return x == y || x.contains(y) || y.contains(x)
    }

    // ------------------------------------------------------------ 歌词：QQ

    private fun fetchQqLyric(song: MusicSong): String? {
        // 有 songmid 就直取，省一次搜索
        val direct = song.extra.optString("songmid").ifBlank { song.extra.optString("mid") }
        if (direct.isNotBlank()) {
            val lrc = qqLyricByMid(direct)
            if (!lrc.isNullOrBlank()) return lrc
        }
        val keyword = java.net.URLEncoder.encode(song.title + " " + song.artist, "UTF-8")
        val body = httpGetWithHeaders(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&w=$keyword&format=json",
            mapOf("Referer" to "https://y.qq.com/"),
        )
        if (body.isBlank()) return null
        val list = JSONObject(body).optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list")
            ?: return null
        for (i in 0 until list.length()) {
            val mid = list.optJSONObject(i)?.optString("songmid").orEmpty()
            if (mid.isBlank()) continue
            val lrc = qqLyricByMid(mid)
            if (!lrc.isNullOrBlank()) return lrc
        }
        return null
    }

    private fun qqLyricByMid(mid: String): String? {
        val raw = httpGetWithHeaders(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid" +
                "&format=json&nobase64=0&g_tk=5381",
            mapOf("Referer" to "https://y.qq.com/portal/player.html"),
        )
        if (raw.isBlank()) return null
        // 这个接口会带 callback(...) 或前后杂字符，从第一个 { 开始截
        val start = raw.indexOf('{')
        if (start < 0) return null
        val json = runCatching { JSONObject(raw.substring(start)) }.getOrNull() ?: return null
        val encoded = json.optString("lyric")
        if (encoded.isBlank()) return null
        val text = runCatching {
            String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull().orEmpty()
        return text.replace("\r\n", "\n").ifBlank { null }
    }

    // ------------------------------------------------------------ 歌词：网易云

    private fun fetchNeteaseLyric(song: MusicSong): String? {
        val direct = song.extra.optString("id").ifBlank { song.extra.optString("songId") }
        if (direct.isNotBlank() && direct.all { it.isDigit() && it.code < 128 }) {
            val lrc = neteaseLyricById(direct)
            if (!lrc.isNullOrBlank()) return lrc
        }
        val keyword = java.net.URLEncoder.encode(song.title + " " + song.artist, "UTF-8")
        val body = httpGetWithHeaders(
            "https://music.163.com/api/search/get?s=$keyword&type=1&limit=5&offset=0",
            mapOf("Referer" to "https://music.163.com/"),
        )
        if (body.isBlank()) return null
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val id = songs.optJSONObject(i)?.optLong("id") ?: 0L
            if (id <= 0L) continue
            val lrc = neteaseLyricById(id.toString())
            if (!lrc.isNullOrBlank()) return lrc
        }
        return null
    }

    private fun neteaseLyricById(id: String): String? {
        val body = httpGetWithHeaders(
            "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1",
            mapOf("Referer" to "https://music.163.com/"),
        )
        if (body.isBlank()) return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val text = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        return text.replace("\r\n", "\n").ifBlank { null }
    }

    // ------------------------------------------------------------ 歌词：HTTP

    private fun httpGet(url: String): String = httpGetWithHeaders(url, emptyMap())

    private fun httpGetWithHeaders(url: String, headers: Map<String, String>): String {
        val client = com.github.tvbox.osc.net.OkGoHelper.getDefaultClient() ?: return ""
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA_PC)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runCatching {
            client.newCall(builder.build()).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")
    }

    // ============================================================ 搜索历史

    private const val HISTORY_KEY = "music_search_history"
    private const val HISTORY_MAX = 10

    private fun loadHistory(): List<String> =
        com.github.tvbox.osc.util.KV.get(
            HISTORY_KEY,
            ArrayList<String>(),
        )

    private fun rememberHistory(keyword: String) {
        val current = loadHistory().toMutableList()
        current.remove(keyword)
        current.add(0, keyword)
        while (current.size > HISTORY_MAX) current.removeAt(current.size - 1)
        com.github.tvbox.osc.util.KV.put(HISTORY_KEY, ArrayList(current))
        historyFlow.value = current
    }

    fun clearHistory() {
        com.github.tvbox.osc.util.KV.put(HISTORY_KEY, ArrayList<String>())
        historyFlow.value = emptyList()
    }

    // ============================================================ 工具

    /** 给歌曲一个稳定的 key，用于「我喜欢」和队列去重 */
    fun songKey(song: MusicSong): String =
        MD5.string2MD5(song.platform + "|" + song.id + "|" + song.sourceId).orEmpty()

    private const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private const val UA_ANDROID =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}

/** 音质排序：先用源支持的、再按用户偏好 */
object MusicQuality {
    private val ORDER = listOf("flac24bit", "flac", "320k", "128k")

    fun preferOrder(supported: List<String>, platform: String): List<String> {
        val base = if (supported.isEmpty()) ORDER else
            ORDER.filter { supported.contains(it) }.ifEmpty { ORDER }
        return base
    }

    fun label(quality: String): String = when (quality) {
        "flac24bit" -> "24bit"
        "flac" -> "SQ"
        "320k" -> "HQ"
        "128k" -> "标准"
        else -> quality
    }
}