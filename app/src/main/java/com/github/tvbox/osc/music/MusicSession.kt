package com.github.tvbox.osc.music

import android.content.Context
import android.content.Intent
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 音乐播放会话。
 *
 * 这一层的唯一职责：把 MusicSong 列表翻译成播放器认识的 VodInfo，
 * 这样队列、上一首/下一首、播放模式、歌词、历史、收藏全部白送，
 * 一行播放器代码都不用改。
 *
 * 原理：
 *   vod.seriesMap["AUDIO"] = 每首歌一个 VodSeries，url 先放占位符
 *   vod.playIndex = 点的那首
 *   MusicPlayerActivity 的 queueList()/playAt()/onSongCompleted() 直接生效
 *
 * 真实地址在取流阶段由 songFromPlaceholder() 换回来。
 */
object MusicSession {

    /** 给播放器用的合成 sourceKey，不会和影视源冲突 */
    const val MUSIC_SOURCE_KEY = "__music__"
    private const val FLAG = "AUDIO"
    private const val LIKED_KEY = "music_liked"
    private const val LIKED_SONGS_KEY = "music_liked_songs"
    private const val QUEUE_KEY = "music_queue"

    private val queueFlow = MutableStateFlow<List<MusicSong>>(emptyList())
    val queue: StateFlow<List<MusicSong>> = queueFlow

    private val indexFlow = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = indexFlow

    private val currentFlow = MutableStateFlow<MusicSong?>(null)
    val current: StateFlow<MusicSong?> = currentFlow

    private val likedFlow = MutableStateFlow(
        com.github.tvbox.osc.util.KV.get(LIKED_KEY, ArrayList<String>()).toSet(),
    )
    val liked: StateFlow<Set<String>> = likedFlow

    private var likedSongs: List<MusicSong> = loadLikedSongs()

    /**
     * 用户手动选的音质档位（按 dedupeKey 记）。
     *
     * 切音质 = 记下档位 + 重播当前这首，PlaybackStarter 会带着这个档位重新取链。
     * 不用改播放器一行代码。
     */
    private val qualityOverride = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun setQualityOverride(song: MusicSong, quality: String) {
        qualityOverride[song.dedupeKey] = quality
        LOG.i("music-quality override " + song.title + " -> " + quality)
    }

    fun qualityFor(song: MusicSong): String? = qualityOverride[song.dedupeKey]

    /** 实际取到的音质（取链成功后由 PlaybackStarter 回写，供 UI 显示） */
    private val resolvedQualityFlow = MutableStateFlow("")
    val resolvedQuality: StateFlow<String> = resolvedQualityFlow

    fun noteResolvedQuality(quality: String) {
        if (quality.isNotBlank()) resolvedQualityFlow.value = quality
    }

    // ============================================================ 起播

    /**
     * 把一批歌塞进播放器并从 index 开始播。
     *
     * @return 可以直接交给 PlayContainer.setData 的 VodInfo
     */
    fun buildPlaybackVod(songs: List<MusicSong>, index: Int): VodInfo? {
        if (songs.isEmpty()) return null
        val safeIndex = index.coerceIn(0, songs.size - 1)

        queueFlow.value = songs
        indexFlow.value = safeIndex
        currentFlow.value = songs.getOrNull(safeIndex)
        persistQueue()

        val first = songs[safeIndex]

        val series = songs.mapIndexed { i, song ->
            VodInfo.VodSeries(
                song.title + (if (song.artist.isNotBlank()) " - " + song.artist else ""),
                // 占位：真实地址在取流阶段换掉
                "music://" + song.platform + "/" + song.id + "/" + i,
            ).also { it.selected = i == safeIndex }
        }

        val vod = VodInfo().apply {
            id = "music:" + first.sourceId + ":" + first.platform
            name = first.title
            pic = first.artwork
            type = "音乐"
            playFlag = FLAG
            playIndex = safeIndex
            playNote = series[safeIndex].name.orEmpty()
            sourceKey = MUSIC_SOURCE_KEY
            seriesFlags = arrayListOf(VodInfo.VodSeriesFlag(FLAG))
            seriesMap = linkedMapOf(FLAG to series.toMutableList())
        }
        LOG.i(
            "music-session build [" + first.title + "] index=" + safeIndex +
                "/" + songs.size + " platform=" + first.platform,
        )
        return vod
    }

    /** 从播放器的 vod 里还原当前是第几首 */
    fun syncIndexFrom(vod: VodInfo?) {
        if (vod == null) return
        if (vod.sourceKey != MUSIC_SOURCE_KEY) return
        val list = vod.seriesMap?.get(vod.playFlag) ?: return
        if (vod.playIndex !in list.indices) return
        indexFlow.value = vod.playIndex
        queueFlow.value.getOrNull(vod.playIndex)?.let { currentFlow.value = it }
    }

    fun songAt(index: Int): MusicSong? = queueFlow.value.getOrNull(index)

    /**
     * 从占位 url 反推是哪一首。
     *
     * 播放器只认 vod.series[playIndex].url，取流时拿到的是 music://...，
     * 必须在这里换回真实地址。
     */
    fun songFromPlaceholder(url: String?): MusicSong? {
        if (url == null || !url.startsWith("music://")) return null
        val parts = url.removePrefix("music://").split('/')
        if (parts.size < 3) return null
        val idx = parts.last().toIntOrNull() ?: return null
        return queueFlow.value.getOrNull(idx)
    }

    fun isPlaceholder(url: String?): Boolean = url?.startsWith("music://") == true

    // ============================================================ 我喜欢

    fun isLiked(song: MusicSong): Boolean =
        likedFlow.value.contains(MusicRepository.songKey(song))

    fun toggleLiked(song: MusicSong): Boolean {
        val key = MusicRepository.songKey(song)
        val next = likedFlow.value.toMutableSet()
        val nowLiked = if (next.contains(key)) {
            next.remove(key)
            false
        } else {
            next.add(key)
            true
        }
        likedFlow.value = next
        com.github.tvbox.osc.util.KV.put(LIKED_KEY, ArrayList(next))

        likedSongs = if (nowLiked) {
            (listOf(song) + likedSongs).distinctBy { MusicRepository.songKey(it) }
        } else {
            likedSongs.filterNot { MusicRepository.songKey(it) == key }
        }
        com.github.tvbox.osc.util.KV.put(
            LIKED_SONGS_KEY,
            ArrayList(likedSongs.map { MusicSongCodec.encode(it) }),
        )
        return nowLiked
    }

    fun likedList(): List<MusicSong> = likedSongs

    private fun loadLikedSongs(): List<MusicSong> =
        com.github.tvbox.osc.util.KV.get(LIKED_SONGS_KEY, ArrayList<String>())
            .mapNotNull { MusicSongCodec.decode(it) }

    // ============================================================ 队列持久化

    private fun persistQueue() {
        runCatching {
            com.github.tvbox.osc.util.KV.put(
                QUEUE_KEY,
                ArrayList(queueFlow.value.map { MusicSongCodec.encode(it) }),
            )
        }
    }

    /** App 冷启动时恢复上次队列（不自动播放） */
    fun restoreQueue() {
        if (queueFlow.value.isNotEmpty()) return
        val songs = com.github.tvbox.osc.util.KV.get(QUEUE_KEY, ArrayList<String>())
            .mapNotNull { MusicSongCodec.decode(it) }
        if (songs.isNotEmpty()) {
            queueFlow.value = songs
            LOG.i("music-session restored " + songs.size + " songs")
        }
    }

    // ============================================================ 入口

    fun openPlayer(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, com.github.tvbox.osc.ui.activity.MusicPlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** MusicSong 的 JSON 编解码：存队列和「我喜欢」用，extra 必须带上 */
object MusicSongCodec {

    fun encode(song: MusicSong): String = JSONObject().apply {
        put("id", song.id)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.album)
        put("artwork", song.artwork)
        put("duration", song.durationSec)
        put("platform", song.platform)
        put("sourceId", song.sourceId)
        put("sourceName", song.sourceName)
        put("qualities", JSONArray(song.qualities))
        put("extra", song.extra)
    }.toString()

    fun decode(text: String): MusicSong? = runCatching {
        val o = JSONObject(text)
        val qualities = ArrayList<String>()
        o.optJSONArray("qualities")?.let {
            for (i in 0 until it.length()) qualities.add(it.optString(i))
        }
        MusicSong(
            id = o.optString("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            artwork = o.optString("artwork"),
            durationSec = o.optInt("duration"),
            platform = o.optString("platform"),
            sourceId = o.optString("sourceId"),
            sourceName = o.optString("sourceName"),
            qualities = qualities,
            extra = o.optJSONObject("extra") ?: JSONObject(),
        )
    }.getOrNull()
}