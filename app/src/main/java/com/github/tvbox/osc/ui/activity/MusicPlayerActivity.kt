package com.github.tvbox.osc.ui.activity
import com.github.tvbox.osc.music.FloatLyric
import com.github.tvbox.osc.music.MusicLyricOffset
import com.github.tvbox.osc.music.MusicRepository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.ControlEditHost
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.data.EpisodeTotals
import com.github.tvbox.osc.data.HistoryWriter
import com.github.tvbox.osc.data.WatchProgressStore
import com.github.tvbox.osc.dlna.CastVideo
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.KernelDecision
import com.github.tvbox.osc.player.KernelReusePolicy
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PlaybackController
import com.github.tvbox.osc.player.PlaybackEngine
import com.github.tvbox.osc.player.PlaybackHostApi
import com.github.tvbox.osc.player.PlaybackPage
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.PlaybackViewBridge
import com.github.tvbox.osc.player.PlayerHelper
import com.github.tvbox.osc.player.state.CastSheetState
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.ui.music.MusicLrc
import com.github.tvbox.osc.ui.music.MusicPlayMode
import com.github.tvbox.osc.ui.music.MusicPlayerScreen
import com.github.tvbox.osc.ui.music.MusicPlayerState
import com.github.tvbox.osc.ui.player.PlayerTipBridge
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.tvbox.osc.player.host.EngineTextureRenderViewFactory

private const val POSITION_TICK_MS = 400L

/** 单首歌拉歌词最多试几次（三源都空才算一次失败） */
private const val LYRIC_MAX_TRIES = 2

class MusicPlayerActivity : BaseActivity(), PlaybackPage {

    companion object {
        private const val EXTRA_HISTORY_SOURCE_KEY = "historySourceKey"

        fun start(context: Context, historySourceKey: String? = null) {
            context.startActivity(
                Intent(context, MusicPlayerActivity::class.java)
                    .putExtra(EXTRA_HISTORY_SOURCE_KEY, historySourceKey),
            )
        }
    }

    private lateinit var engine: PlaybackEngine
    private lateinit var controller: PlaybackController
    private lateinit var player: MyVideoView
    private lateinit var renderSlot: FrameLayout
    private lateinit var bridge: MusicPageBridge
    private lateinit var host: MusicHost

    private val ui = MusicPlayerState()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var vod: VodInfo
    private var sourceKey = ""
    private var historySourceKey = ""
    private var ready = false
    private var lifecyclePaused = false
    private var lyricSource: String? = null
    private var lyricJob: Job? = null

    /**
     * 每首歌尝试拉歌词的次数。
     *
     * 三源串行失败（网络抖动/歌词库没收录）时不能永久放弃，
     * 但也不能每个 position tick 都重打一遍，所以限 2 次。
     */
    private val lyricTries = HashMap<String, Int>()

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {}

    override fun init() {
        engine = PlaybackService.engine(this)
        controller = engine.controller()
        player = engine.player()
        vod = controller.vod() ?: run {
            finish()
            return
        }
        sourceKey = controller.sourceKey().orEmpty()
        historySourceKey = intent?.getStringExtra(EXTRA_HISTORY_SOURCE_KEY)
            ?.takeIf { it.isNotEmpty() }
            ?: sourceKey
        enableTransparentEdgeToEdge()
        renderSlot = FrameLayout(this)
        addContentView(renderSlot, ViewGroup.LayoutParams(1, 1))
        bridge = MusicPageBridge(this, engine.headlessBridge())
        host = MusicHost()
        engine.attach(this)
        scope.launch(Dispatchers.Main.immediate) {
            player.playStateFlow.collect { playState -> onPlayStateChanged(playState) }
        }
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    MusicPlayerScreen(
                        state = ui,
                        onBack = { finish() },
                        onTogglePlay = { togglePlay() },
                        onPrevious = { host.playPrevious() },
                        onNext = { host.playNext(false) },
                        onSeek = { seekTo(it) },
                        onSelectQueue = { playAt(it, false) },
                        onCyclePlayMode = { cyclePlayMode() },
                        onToggleCollect = { toggleCollect() },
                        onCast = { showCast() },
                        onSelectQuality = { quality -> switchQuality(quality) },
                        onReloadLyric = { reloadLyric() },
                    )
                    // 3.07 第二阶段 C-edit：长按编辑弹窗宿主（音乐页根部）。
                    ControlEditHost()
                }
            }
        }
        ui.playMode = MusicPlayMode.of(MusicSettings.playMode())
        scope.launch {
            val isCollected = try {
                withContext(Dispatchers.IO) { AppGraph.collectRepository.isVodCollect(sourceKey, vod.id) }
            } catch (th: Throwable) {
                LOG.e("MusicPlayerActivity", "collect state read failed: " + th, th)
                false
            }
            ui.collected = isCollected
        }
        refreshMeta()
        syncLyric()
        controller.setMusicAudioOnly(true)
        ready = true
        main.post(positionTick)
    }

    override fun renderSlot(): ViewGroup = renderSlot

    override fun viewBridge(): PlaybackViewBridge = bridge

    override fun isAudioOnlyPage(): Boolean = true

    override fun onServiceStopped() {
        main.removeCallbacksAndMessages(null)
        PlayerTipBridge.hide()
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        LOG.i("echo-music page onResume lifecyclePaused=$lifecyclePaused playing=${player.isPlaying} audioOnly=${controller.isConfirmedAudioOnly()}")
        controller.setMusicAudioOnly(true)
        host.hostResume()
        main.removeCallbacks(positionTick)
        main.post(positionTick)
    }

    override fun onPause() {
        if (ready) {
            LOG.i("echo-music page onPause lifecyclePaused=$lifecyclePaused playing=${player.isPlaying} audioOnly=${controller.isConfirmedAudioOnly()}")
            host.hostPause()
            main.removeCallbacks(positionTick)
        }
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        lyricJob = null
        if (ready) {
            PlayerTipBridge.hide()
            syncHistory()
            controller.setMusicAudioOnly(false)
            engine.detach(this)
        }
        super.onDestroy()
    }

    private val positionTick = object : Runnable {
        override fun run() {
            ui.positionMs = player.currentPosition.coerceAtLeast(0L)
            ui.durationMs = player.duration.coerceAtLeast(0L)
            val state = player.playState
            ui.buffering = state == PlayState.PREPARING || state == PlayState.BUFFERING
            ui.playing = player.isPlaying
            main.postDelayed(this, POSITION_TICK_MS)
        }
    }

    private fun onPlayStateChanged(playState: PlayState) {
        when (playState) {
            PlayState.PREPARING, PlayState.BUFFERING -> ui.buffering = true
            PlayState.PREPARED, PlayState.BUFFERED -> ui.buffering = false
            PlayState.PLAYING -> {
                ui.buffering = false
                ui.playing = true
            }
            PlayState.PAUSED -> {
                ui.buffering = false
                ui.playing = false
            }
            PlayState.COMPLETED -> {
                ui.buffering = false
                ui.playing = false
                onSongCompleted()
            }
            PlayState.ERROR -> {
                ui.buffering = false
                ui.playing = false
            }
            PlayState.IDLE, PlayState.START_ABORT -> {}
        }
        ui.durationMs = player.duration.coerceAtLeast(0L)
        if (playState == PlayState.PREPARING
            || playState == PlayState.PREPARED
            || playState == PlayState.PLAYING
        ) {
            refreshMeta()
            syncLyric()
        }
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.start()
        ui.playing = player.isPlaying
        controller.updateMusicSession()
    }

    private fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        player.saveCurrentProgress()
        ui.positionMs = positionMs.coerceAtLeast(0L)
        controller.updateMusicSession()
    }

    private fun queueList(): List<VodInfo.VodSeries> =
        vod.seriesMap?.get(vod.playFlag).orEmpty()

    private fun playAt(index: Int, removeProgress: Boolean) {
        val list = queueList()
        if (index < 0 || index >= list.size || index == vod.playIndex) return
        controller.beginSwitchPlayback()
        if (removeProgress) {
            controller.progressKey()?.let { WatchProgressStore.clear(controller.progressOwner(), it, discard = true) }
        }
        vod.playIndex = index
        list.forEachIndexed { i, series -> series.selected = i == index }
        controller.clearTriedLines()
        controller.setReusePlayerOnSwitch(true)
        engine.play(false)
        ui.queueIndex = index
        refreshMeta()
        syncHistory()
    }

    private fun syncHistory() {
        vod.playNote = queueList().getOrNull(vod.playIndex)?.name.orEmpty()
        EpisodeTotals.putFromVod(vod)
        HistoryWriter.write(historySourceKey, vod)
    }

    private fun onSongCompleted() {
        when (ui.playMode) {
            MusicPlayMode.SINGLE -> replayCurrent()
            MusicPlayMode.LIST -> {
                val list = queueList()
                if (list.isEmpty()) return
                val next = (vod.playIndex + 1) % list.size
                if (next == vod.playIndex) replayCurrent() else playAt(next, true)
            }
            MusicPlayMode.ORDER -> playAt(vod.playIndex + 1, true)
        }
    }

    private fun replayCurrent() {
        controller.beginSwitchPlayback()
        controller.progressKey()?.let { WatchProgressStore.clear(controller.progressOwner(), it, discard = true) }
        controller.clearTriedLines()
        controller.setReusePlayerOnSwitch(true)
        engine.play(true)
    }

    private fun cyclePlayMode() {
        val mode = ui.playMode.toggled()
        ui.playMode = mode
        MusicSettings.setPlayMode(mode.name)
    }

    /**
     * 切音质：记档位 → 重播当前这首。
     *
     * replayCurrent 会重新走 engine.play → PlaybackStarter，
     * 那里读到 music:// 占位符就会带着新档位重新取链，位置也保留。
     */
    private fun switchQuality(quality: String) {
        val song = musicSongForLyric() ?: return
        if (quality == ui.quality) return
        com.github.tvbox.osc.music.MusicSession.setQualityOverride(song, quality)
        ui.quality = quality
        LOG.i("echo-music switch quality -> " + quality)
        replayCurrent()
    }

    private fun toggleCollect() {
        // 音乐模式：收藏写本地「我喜欢」，不写影视收藏库
        val song = musicSongForLyric()
        if (song != null) {
            val nowLiked = com.github.tvbox.osc.music.MusicSession.toggleLiked(song)
            ui.collected = nowLiked
            Toast.makeText(
                this,
                if (nowLiked) "已收藏到我喜欢" else "已取消收藏",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val key = sourceKey
        if (ui.collected) {
            scope.launch(Dispatchers.IO) {
                try {
                    AppGraph.collectRepository.deleteVodCollect(key, vod)
                } catch (th: Throwable) {
                    LOG.e("MusicPlayerActivity", "collect delete failed: " + th, th)
                }
            }
            ui.collected = false
        } else {
            scope.launch(Dispatchers.IO) {
                try {
                    AppGraph.collectRepository.insertVodCollect(key, vod)
                } catch (th: Throwable) {
                    LOG.e("MusicPlayerActivity", "collect insert failed: " + th, th)
                }
            }
            ui.collected = true
        }
    }

    private fun showCast() {
        val url = controller.webPlayUrl()
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        val title = listOf(ui.title, ui.subtitle)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "TVBox" }
        val headers = controller.webHeaderMap()?.let { HashMap(it) }
        ui.castSheet = CastSheetState(
            CastVideo(
                controller.getCastUrl(url) ?: url,
                title,
                headers,
                player.currentPosition.coerceAtLeast(0L),
            ),
            onCastSuccess = {
                player.pause()
                controller.updateMusicSession()
            },
        )
    }

    private fun refreshMeta() {
        val series = vod.seriesMap?.get(vod.playFlag)?.getOrNull(vod.playIndex)
        val title = vod.name.orEmpty().ifBlank { getString(R.string.music_default_name) }
        val sourceName = ApiConfig.get().getSource(sourceKey)?.name.orEmpty()
        ui.title = title
        ui.subtitle = series?.name?.takeIf { it.isNotBlank() }.orEmpty()
        ui.sourceName = sourceName
        ui.artwork = listOfNotNull(
            controller.currentArtwork()?.takeIf { it.isNotBlank() },
            controller.playArtwork()?.takeIf { it.isNotBlank() },
            vod.pic?.takeIf { it.isNotBlank() },
        ).firstOrNull().orEmpty()
        ui.queue = queueList().map { it.name.orEmpty() }
        ui.queueIndex = vod.playIndex
        ui.waveSeed = (vod.id.orEmpty() + "#" + vod.playIndex).hashCode()

        // 音乐模式：字幕/来源换成「歌手 · 音质」，收藏状态换成本地「我喜欢」
        val song = musicSongForLyric()
        ui.musicMode = song != null
        if (song != null) {
            ui.title = song.title
            ui.subtitle = song.artist
            ui.sourceName = song.sourceName.ifBlank { song.platform.uppercase() }
            ui.artwork = song.artwork.ifBlank { vod.pic.orEmpty() }
            ui.qualityOptions = com.github.tvbox.osc.music.MusicQuality
                .preferOrder(song.qualities, song.platform)
            ui.collected = com.github.tvbox.osc.music.MusicSession.isLiked(song)
            // 初始音质：用户覆盖 > 上次实际取到 > 最高档
            ui.quality = com.github.tvbox.osc.music.MusicSession.qualityFor(song)
                ?: com.github.tvbox.osc.music.MusicSession.resolvedQuality.value
                ?: ui.qualityOptions.firstOrNull().orEmpty()
            LOG.i(
                "echo-music meta [" + song.title + "] qualities=" +
                    ui.qualityOptions.joinToString("/") + " liked=" + ui.collected,
            )
        }
        // 2.92 歌词按歌校准：平台+id 进 state，主界面与悬浮窗用同一把 key；py 的歌也算上
        val lyricSong = musicSongForLyric() ?: pySongForLyric()
        ui.musicPlatform = lyricSong?.platform.orEmpty()
        ui.musicId = lyricSong?.id.orEmpty()
        // 2.97 搜歌词预填用解析后的歌名/歌手——py 歌的 ui.title/ui.subtitle 是 vod.name/series.name（歌手/歌名-专辑），直接当歌名歌手会填错
        ui.lyricSearchTitle = lyricSong?.title ?: ui.title
        ui.lyricSearchArtist = lyricSong?.artist ?: ui.subtitle
    }

    // 2.87 歌词浮窗：歌词到手就推给 FloatLyric（系统悬浮窗），标题用当前歌名。
    // 2.92：带上按歌 key，悬浮窗与主界面共用同一套按歌校准映射。
    private fun pushFloatLyric(lines: List<com.github.tvbox.osc.ui.music.LyricLine>) {
        try {
            val song = musicSongForLyric() ?: pySongForLyric()
            val key = if (song != null) {
                MusicLyricOffset.songKey(song)
            } else {
                MusicLyricOffset.songKey(null, null, ui.title, ui.subtitle)
            }
            FloatLyric.setLyrics(lines, ui.title, key)
        } catch (ignored: Throwable) {
        }
    }

    private fun syncLyric() {
        val source = controller.playLyric()
        // 音乐模式：洛雪音源不提供歌词，走 MusicRepository 的歌词接口。
        // 靠 vod.sourceKey 判断是不是音乐内容（合成 VodInfo 用的是 __music__）。
        // 2.82：py 音乐源的歌 sourceKey 不是 __music__，也拼一首 MusicSong 走同一接口（网易云优先）；
        // py 自己带的 SSA 字幕只当回落，不再是显示歌词的主路。
        val musicSong = musicSongForLyric() ?: pySongForLyric()
        if (musicSong != null) {
            val key = "music-lyric:" + musicSong.platform + ":" + musicSong.id
            if (key == lyricSource) return
            // 失败过的 key 允许在后续 tick 再试一次（最多 2 次），
            // 否则初次网络抖动会让这首歌永远没有歌词。
            val tries = lyricTries[key] ?: 0
            if (tries >= LYRIC_MAX_TRIES) return
            lyricTries[key] = tries + 1
            lyricSource = key
            lyricJob?.cancel()
            // 2.95：新歌开始拉词时先清掉上一首的空态，旧歌词等新词到了再换
            ui.lyricEmpty = com.github.tvbox.osc.ui.music.LyricEmptyState.NONE
            lyricJob = scope.launch {
                val result = try {
                    MusicRepository.lyricResult(musicSong)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.i("echo-music lyric fetch failed: $e")
                    null
                }
                // 纯音乐：显示“纯音乐，请欣赏”，不再反复拉取
                if (result?.status == MusicRepository.LyricStatus.INSTRUMENTAL) {
                    if (key == lyricSource) {
                        ui.lyrics = emptyList()
                        ui.lyricEmpty = com.github.tvbox.osc.ui.music.LyricEmptyState.INSTRUMENTAL
                        pushFloatLyric(emptyList())
                    }
                    return@launch
                }
                val text = result?.text
                if (text.isNullOrBlank()) {
                    LOG.i("echo-music lyric empty for " + musicSong.title)
                    if (key == lyricSource) {
                        // App 接口没拿到（多半是 py 的歌），回落到 py 自己带的 SSA 字幕，别直接空着；SSA 也空才放开标记让下次 tick 重试
                        val ssaLines = try { MusicLrc.load(source) } catch (e: Exception) { emptyList() }
                        if (key == lyricSource) {
                            // 2.92：live 标题粗对齐（只一次、手动校准过不覆盖）
                            MusicLyricOffset.maybeAutoCalibrate(MusicLyricOffset.songKey(musicSong), ui.title, ui.subtitle, ssaLines)
                            ui.lyrics = ssaLines
                            pushFloatLyric(ssaLines)
                            if (ssaLines.isEmpty()) {
                                // 2.95：没找到歌词（SSA 也空），播放页显示“暂无歌词”+搜歌词/导入入口
                                ui.lyricEmpty = com.github.tvbox.osc.ui.music.LyricEmptyState.NOT_FOUND
                                lyricSource = null
                            } else {
                                ui.lyricEmpty = com.github.tvbox.osc.ui.music.LyricEmptyState.NONE
                                lyricSource = source
                            }
                        }
                    }
                    return@launch
                }
                val lines = try {
                    MusicLrc.parse(text)
                } catch (e: Exception) {
                    LOG.i("echo-music lyric parse failed: $e")
                    emptyList()
                }
                LOG.i("echo-music lyric parsed: " + lines.size + " lines")
                if (key == lyricSource) {
                    // 2.92：live 标题粗对齐（只一次、手动校准过不覆盖）
                    MusicLyricOffset.maybeAutoCalibrate(MusicLyricOffset.songKey(musicSong), ui.title, ui.subtitle, lines)
                    ui.lyrics = lines
                    pushFloatLyric(lines)
                    if (lines.isEmpty()) {
                        ui.lyricEmpty = com.github.tvbox.osc.ui.music.LyricEmptyState.NOT_FOUND
                        lyricSource = null
                    } else {
                        ui.lyricEmpty = com.github.tvbox.osc.ui.music.LyricEmptyState.NONE
                    }
                }
            }
            return
        }
        if (source.isNullOrBlank()) {
            if (lyricSource != null) {
                lyricSource = null
                ui.lyrics = emptyList()
                ui.lyricEmpty = com.github.tvbox.osc.ui.music.LyricEmptyState.NONE
                pushFloatLyric(emptyList())
            }
            return
        }
        if (source == lyricSource) return
        lyricSource = source
        lyricJob?.cancel()
        lyricJob = scope.launch {
            val lines = try {
                MusicLrc.load(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.i("echo-music lyric parse failed: $e")
                emptyList()
            }
            LOG.i("echo-music lyric parsed: ${lines.size} lines")
            if (source == lyricSource) {
                ui.lyrics = lines
                pushFloatLyric(lines)
            }
        }
    }

    /** 2.95：搜歌词面板应用/导入/清除锁定后，强制重拉当前歌的歌词。 */
    private fun reloadLyric() {
        val musicSong = musicSongForLyric() ?: pySongForLyric()
        if (musicSong != null) {
            val key = "music-lyric:" + musicSong.platform + ":" + musicSong.id
            lyricTries.remove(key)
            lyricSource = null
            ui.lyrics = emptyList()
            ui.lyricEmpty = com.github.tvbox.osc.ui.music.LyricEmptyState.NONE
            syncLyric()
        }
    }

    /** 当前播的是不是音乐内容；是就返回对应歌曲 */
    private fun musicSongForLyric(): com.github.tvbox.osc.music.MusicSong? {
        val v = controller.vod() ?: return null
        if (v.sourceKey != com.github.tvbox.osc.music.MusicSession.MUSIC_SOURCE_KEY) return null
        val list = v.seriesMap?.get(v.playFlag) ?: return null
        val series = list.getOrNull(v.playIndex) ?: return null
        return com.github.tvbox.osc.music.MusicSession.songFromPlaceholder(series.url)
    }

    /** py 音乐源的歌（2.82）：sourceKey 不是 __music__，走不了 musicSongForLyric；从当前 vod/集数拼一首 MusicSong，让它也走 App 的歌词接口（网易云优先）。只认酷我 py，其他源不动。 */
    private fun pySongForLyric(): com.github.tvbox.osc.music.MusicSong? {
        val v = controller.vod() ?: return null
        if (v.sourceKey == com.github.tvbox.osc.music.MusicSession.MUSIC_SOURCE_KEY) return null
        val flag = v.playFlag ?: return null
        if (v.sourceKey?.contains("酷我") != true && !flag.contains("酷我")) return null
        val series = v.seriesMap?.get(flag)?.getOrNull(v.playIndex) ?: return null
        val rawName = series.name?.trim().orEmpty()
        if (rawName.isEmpty()) return null
        val title = rawName.substringBefore(" - ").trim()
        if (title.isEmpty()) return null
        val rid = series.url?.trim().orEmpty()
        if (rid.isEmpty()) return null
        val artist = v.name?.trim().orEmpty()
        val extra = org.json.JSONObject().apply { put("rid", rid) }
        return com.github.tvbox.osc.music.MusicSong(
            id = rid,
            title = title,
            artist = artist,
            platform = "kw",
            sourceId = v.sourceKey.orEmpty(),
            sourceName = v.sourceKey.orEmpty(),
            extra = extra,
        )
    }

    private fun applyPlayerConfig(forceKernel: Int) {
        val cfg = controller.playerCfg() ?: return
        if (forceKernel > 0) {
            PlayerHelper.updateCfg(player, cfg, forceKernel)
        } else {
            PlayerHelper.updateCfg(player, cfg)
        }
    }

    private fun startPlayback(url: String, headers: HashMap<String, String>?, forceExoPlayer: Boolean) {
        PlayerTipBridge.hide()
        if (player.mediaPlayer != null
            && !controller.isConfirmedAudioOnly()
            && player.needsRenderRebuild(player.factoryRenderType())
        ) {
            player.requireKernelRebuild()
            LOG.i("echo-render-changed: rebuild kernel on next start")
        }
        if (player.isKernelErrored()) {
            player.requireKernelRebuild()
            LOG.i("echo-kernel-error: rebuild errored kernel on start")
        }
        val rebuildKernel = player.consumeKernelRebuildRequired()
        val kernelPresent = player.mediaPlayer != null
        val reusePlayer = KernelReusePolicy.decide(kernelPresent, rebuildKernel, forceExoPlayer, true) == KernelDecision.REUSE
        val sameContent = reusePlayer && controller.isSameStartedContent()
        if (!reusePlayer && kernelPresent) engine.releasePlayer()
        if (sameContent) player.saveCurrentProgress()
        player.setProgressKey(controller.progressKey())
        player.setTrackMemoryKey("")
        controller.markContentStarted()
        if (headers != null) player.setUrl(url, headers) else player.setUrl(url)
        controller.startSwitchLinePlayTimeout()
        if (reusePlayer) {
            val base = controller.playTimeoutBasePosition()
            player.skipPositionWhenPlay((if (sameContent) player.resumePositionForReplay(base) else base).toInt())
            player.replay(false)
        } else {
            player.start()
        }
    }

    private inner class MusicHost : PlaybackHostApi {

        override fun setData(session: PlaybackSession) {
            engine.setData(session)
        }

        override fun play(reset: Boolean) {
            engine.play(reset)
        }

        override fun playNext(rmProgress: Boolean) {
            playAt(vod.playIndex + 1, rmProgress)
        }

        override fun playPrevious() {
            playAt(vod.playIndex - 1, false)
        }

        override fun selectQuality(position: Int): Boolean = controller.selectQuality(position)

        override fun setAutoSwitchLineEnabled(enabled: Boolean) {
            controller.setAutoSwitchLineEnabled(enabled)
        }

        override fun setPreviewMode(previewMode: Boolean) {}

        override fun toggleControllerControls() {}

        override fun onBackPressed(): Boolean = false

        override fun setExitingPreview(exitingPreview: Boolean) {}

        override fun setPlayTitle(show: Boolean) {}

        override fun stopForSourceSwitch(tip: String) {
            controller.markStoppedForSourceSwitch()
            controller.stopMusicSessionForFailedPlayback()
        }

        override fun clearSourceSwitchTip() {}

        override fun showCast() {}

        override fun onLocalSubtitlePicked(uri: Uri) {}

        override fun hostResume() {
            engine.consumeServiceLostKeep(true)
            if (lifecyclePaused) {
                lifecyclePaused = false
                player.resume()
            }
        }

        override fun hostPause() {
            if (!controller.isConfirmedAudioOnly()) {
                lifecyclePaused = player.isPlaying
                LOG.i("echo-music hostPause -> pause player (lifecyclePaused=$lifecyclePaused)")
                player.pause()
            }
        }

        override fun hostDestroy() {}

        override fun resumeFromMediaSession() {
            player.start()
            controller.updateMusicSession()
        }

        override fun pauseFromMediaSession() {
            player.pause()
            controller.updateMusicSession()
        }

        override fun stopFromMediaSession() {
            player.pause()
            controller.stopMusicSession()
        }

        override fun seekFromMediaSession(position: Long) {
            player.seekTo(position)
            controller.updateMusicSession()
        }
    }

    private class MusicPageBridge(
        private val activity: MusicPlayerActivity,
        private val base: PlaybackViewBridge,
    ) : PlaybackViewBridge by base {

        private val main = Handler(Looper.getMainLooper())

        override fun isPageAlive(): Boolean = !activity.isFinishing && !activity.isDestroyed

        override fun runOnUi(action: Runnable) {
            main.post { if (isPageAlive()) action.run() }
        }

        override fun context(): Context = activity

        override fun playbackHost(): PlaybackHostApi = activity.host

        override fun toast(text: CharSequence) {
            if (isPageAlive()) Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }

        override fun showTip(msg: String, loading: Boolean, error: Boolean) {
            PlayerTipBridge.setTip(msg.orEmpty(), loading, error)
        }

        override fun hideTipOnUiThread() {
            main.post { PlayerTipBridge.hide() }
        }

        override fun showErrorWithRetry(err: String, finish: Boolean) {
            PlayerTipBridge.setTip(err.orEmpty(), false, true)
            toast(err)
        }

        override fun requestNotificationPermission() {
            PermissionHelper.requestNotificationIfNeeded(activity)
        }

        override fun setArtwork(url: String) {
            activity.ui.artwork = url.orEmpty()
        }

        override fun clearArtwork() {
            activity.ui.artwork = ""
        }

        override fun clearLyric() {
            activity.lyricSource = null
            activity.ui.lyrics = emptyList()
        }

        override fun onNewPlayStarted(sameContent: Boolean) {
            activity.refreshMeta()
        }

        override fun applyPlayerConfigToView(forceKernel: Int) {
            activity.applyPlayerConfig(forceKernel)
        }

        override fun startVideoPlayback(
            url: String,
            headers: HashMap<String, String>?,
            forceExoPlayer: Boolean,
        ) {
            activity.startPlayback(url, headers, forceExoPlayer)
        }

        override fun useTextureRenderForAudio() {
            activity.player.setRenderViewFactory(EngineTextureRenderViewFactory.create())
        }

        override fun playExternalPlayer(
            playerType: Int,
            url: String,
            title: String,
            subtitle: String?,
            headers: HashMap<String, String>?,
            progress: Long,
        ): Boolean {
            if (!isPageAlive()) return false
            return PlayerHelper.runExternalPlayer(playerType, activity, url, title, subtitle.orEmpty(), headers, progress)
        }
    }
}
