package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.setContent
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.ui.components.ControlEditHost
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.components.WallpaperBackdrop
import com.github.tvbox.osc.player.PageHost
import com.github.tvbox.osc.player.PlaybackController
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.ui.player.PlayContainer
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.receiver.PipActions
import com.github.tvbox.osc.receiver.PipSession
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.FloatLog
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.Pip
import com.github.tvbox.osc.util.SpiderStorageNotice
import kotlinx.coroutines.launch

private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L
private const val VIDEO_SIZE_WATCH_TIMEOUT_MS = 10000L
private const val CAST_URL_POLL_MS = 250L
private const val CAST_URL_WAIT_ATTEMPTS = 20

class DetailActivity : BaseActivity(), PageHost {

    private val vm: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }

    var playContainer: PlayContainer? by mutableStateOf<PlayContainer?>(null)
        private set

    // 画中画（对标 webhtv VideoActivity 的 mPiP 接法）：inPipMode 供
    // DetailScreen 组合层只留播放器画面；pipModeActive 是「曾真正进入
    // PiP」的闩锁，onStop 时仍为真 = 小窗被 X 关/被系统回收，据此收尾。
    private val pip = Pip()

    var inPipMode by mutableStateOf(false)
        private set

    private var pipModeActive = false

    private val pipActionHandler: (String) -> Unit = { action -> handlePipAction(action) }

    private var fullScreen = false
    private var pendingEpisodeSync = false
    private var videoSizeWatchArmed = false
    private var castWaitAttempts = 0

    private val videoSizeTimeoutRunnable = Runnable {
        if (!videoSizeWatchArmed) return@Runnable
        LOG.i("echo-player detail size timeout, keep portrait placeholder")
        videoSizeWatchArmed = false
        playContainer?.setVideoSizeReadyListener(null)
    }

    private val castWaitRunnable = object : Runnable {
        override fun run() {
            val container = playContainer ?: return
            if (container.hasCastUrl()) {
                container.endCastPrepare()
                container.showCast()
                return
            }
            if (castWaitAttempts >= CAST_URL_WAIT_ATTEMPTS) {
                container.endCastPrepare()
                container.showCast()
                return
            }
            castWaitAttempts += 1
            window.decorView.postDelayed(this, CAST_URL_POLL_MS)
        }
    }

    private val readerRouter = object : com.github.tvbox.osc.reader.ReaderRouter {
        override fun openReader(
            vod: VodInfo,
            content: com.github.tvbox.osc.reader.ReaderContent,
            playResult: org.json.JSONObject?,
        ): Boolean {
            if (isFinishing || isDestroyed) return false
            val session = com.github.tvbox.osc.reader.ReaderSession.fromVod(vod, content, playResult)
            val token = com.github.tvbox.osc.reader.ReaderSessions.put(session)
            playContainer?.stopForContentSwitch()
            when (content) {
                is com.github.tvbox.osc.reader.ReaderContent.Comic ->
                    if (com.github.tvbox.osc.reader.ReaderStore.useWebReader()) {
                        com.github.tvbox.osc.reader.WebComicReaderActivity.start(this@DetailActivity, token)
                    } else {
                        com.github.tvbox.osc.reader.ComicReaderActivity.start(this@DetailActivity, token)
                    }
                is com.github.tvbox.osc.reader.ReaderContent.Novel ->
                    if (com.github.tvbox.osc.reader.ReaderStore.useWebReader()) {
                        com.github.tvbox.osc.reader.WebNovelReaderActivity.start(this@DetailActivity, token)
                    } else {
                        com.github.tvbox.osc.reader.NovelReaderActivity.start(this@DetailActivity, token)
                    }
            }
            // 定稿：打开阅读器时详情页同时关闭，返回直接回浏览页。
            // 不止关自己：栈里可能还压着早前另一部剧的详情页实例
            // (singleTop 只在栈顶去重，经搜索/收藏等路径再进详情会
            // 多压一层)，只 finish 当前实例的话阅读器返回会落回那
            // 个旧详情页。所有详情页实例一并关掉，返回只落浏览页。
            com.github.tvbox.osc.util.AppManager.getInstance().finishActivities(DetailActivity::class.java)
            return true
        }
    }

    /**
     * The playback engine is created lazily (prewarm / first use), so a
     * single registration in init() can miss it when peek() is still null.
     * Re-register at every lifecycle point that precedes playback; the
     * registration is idempotent and clearReaderRouter only clears our own
     * instance.
     */
    private fun registerReaderRouter() {
        PlaybackService.peek()?.controller()?.setReaderRouter(readerRouter)
    }

    private val localSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) playContainer?.onLocalSubtitlePicked(uri)
    }

    override fun launchLocalSubtitlePicker() {
        try {
            SpiderStorageNotice.suppress()
            localSubtitlePicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_file_picker_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        if (fullScreen) super.hideSysBar()
    }

    override fun keepStatusBarHidden(): Boolean = true

    private var statusBarIconsLight = false

    fun setStatusBarIconsLight(light: Boolean) {
        if (statusBarIconsLight == light) return
        statusBarIconsLight = light
        applyStatusBarAppearance()
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = statusBarIconsLight
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun init() {
        registerReaderRouter()
        PipSession.handler = pipActionHandler
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                FloatLog.append("app", null, "tap back fullScreen=$fullScreen entering=${vm.enteringFullscreen.value} exiting=${vm.exitingFullscreen.value}")
                val container = playContainer
                if (vm.enteringFullscreen.value) {
                    vm.cancelEntrySlide()
                    container?.stopForExitFullscreen()
                    return
                }
                if (vm.exitingFullscreen.value) return
                if (fullScreen) {
                    if (container != null && container.onBackPressed()) return
                    if (playbackFacts().landscape) {
                        rotatePlayerToPortrait()
                    } else {
                        if (DetailPlaybackPolicy.plan(DetailPlaybackEntry.ExitFullscreen).stopPlayback) {
                            container?.stopForExitFullscreen()
                        }
                        vm.onFullScreenToggleRequested(false, playbackFacts())
                    }
                } else {
                    if (vm.backToPreviousTarget()) {
                        pendingEpisodeSync = false
                        return
                    }
                    container?.setPlayTitle(false)
                    container?.setExitingPreview(true)
                    finish()
                }
            }
        })
        vm.initFromIntent(intent)
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                SheetHostScaffold {
                    WallpaperBackdrop()
                    DetailScreen(activity = this, vm = vm)
                    // 3.07 第二阶段 C-edit：长按编辑弹窗宿主（详情/播放页根部）。
                    ControlEditHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingEpisodeSync = false
        vm.onFullScreenToggleRequested(false, playbackFacts())
        vm.pushTargetFromIntent(intent)
    }

    fun ensurePlayContainer(): PlayContainer {
        registerReaderRouter()
        if (playContainer == null) {
            playContainer = PlayContainer(this).also {
                it.setPageHost(this)
                it.setPreviewMode(true)
                it.setOnQualitySelectedListener(vm::onQualitySelectionAccepted)
                it.onPipRequested = { requestPip() }
                it.onCloseRequested = { closePlayer() }
                it.onFullscreenRequested = { toggleFullscreenFromPlayer() }
            }
        }
        return requireNotNull(playContainer) { "play container not created" }
    }

    // ---- 画中画（对标 webhtv VideoActivity 的 preparePiP/requestPiP/enterPiP） ----

    private fun pipPlaybackReady(): Boolean {
        val container = playContainer ?: return false
        if (isFinishing || isDestroyed) return false
        if (container.mVideoView?.mediaPlayer == null) return false
        // 纯音频内容走音乐页形态，不进 PiP（对应 webhtv 的 audioMode 门控）
        if (PlaybackService.peek()?.controller()?.isConfirmedAudioOnly() == true) return false
        return container.pipIsPlaying() || container.mVideoView?.playState == PlayState.PLAYING ||
            container.mVideoView?.playState == PlayState.BUFFERING ||
            container.mVideoView?.playState == PlayState.PREPARED
    }

    private fun preparePip(): Boolean {
        if (!pipPlaybackReady()) return false
        val container = playContainer ?: return false
        // Refresh the sourceRectHint from the actual player view bounds
        // right before entry so the transition grows out of the video
        // surface instead of jumping (the layout listener keeps it
        // warm, this pins the exact rect at entry time).
        pip.update(this, container)
        val size = container.pipVideoSize()
        pip.update(this, size?.get(0) ?: 16, size?.get(1) ?: 9, container.pipScale())
        pip.updateActions(this, container.pipIsPlaying(), container.pipHasNext())
        container.setPipHold(true)
        return true
    }

    private fun requestPip(): Boolean = false

    /**
     * Top-bar X in the player: stop playback and close the player page
     * entirely, back to the browsing page (distinct from back, which
     * only exits fullscreen / steps back inside the page first).
     */
    fun closePlayer() {
        playContainer?.stopForExitFullscreen()
        finish()
    }

    /** Fullscreen toggle for the bottom-bar button and double-tap.
     * Portrait-player model: the button rotates between landscape
     * fullscreen and the portrait player page (playback keeps
     * going, no stop, no return to detail); detail is reached only
     * via system back from the portrait player page. */
    fun toggleFullscreenFromPlayer() {
        if (vm.enteringFullscreen.value || vm.exitingFullscreen.value) vm.resetSlideTransition()
        if (fullScreen || vm.fullScreen.value) {
            if (playbackFacts().landscape) {
                rotatePlayerToPortrait()
            } else {
                rotatePlayerToLandscape()
            }
        } else {
            vm.onFullScreenToggleRequested(true, playbackFacts())
        }
    }

    /** Portrait player page: player stays on screen, orientation
     * forced portrait, playback uninterrupted. The slide state is
     * untouched (full stays true, rotating stays false) so the
     * player box stays visible instead of sliding out to detail. */
    private fun rotatePlayerToPortrait() {
        if (!fullScreen) return
        disarmVideoSizeWatch()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    /** Landscape fullscreen from the portrait player page: same
     * no-stop, no-slide-state-change contract as [rotatePlayerToPortrait]. */
    private fun rotatePlayerToLandscape() {
        if (!fullScreen) return
        disarmVideoSizeWatch()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** Manual orientation wins over the entry-time video-size
     * watch, so a later size callback cannot override the user's
     * explicit rotate. Mirrors the watch cleanup in applyFullscreen(false). */
    private fun disarmVideoSizeWatch() {
        videoSizeWatchArmed = false
        playContainer?.setVideoSizeReadyListener(null)
        window.decorView.removeCallbacks(videoSizeTimeoutRunnable)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureRequested(): Boolean = false

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            pipModeActive = true
            playContainer?.hideControlsForPip()
        } else {
            pipModeActive = false
            playContainer?.setPipHold(false)
            applyOrientationPolicy()
        }
    }

    private fun handlePipAction(action: String) {
        if (!isInPictureInPictureMode) return
        val container = playContainer ?: return
        val view = container.mVideoView ?: return
        when (action) {
            PipActions.PLAY -> {
                view.start()
                pip.updateActions(this, true, container.pipHasNext())
            }
            PipActions.PAUSE -> {
                FloatLog.append("app", null, "pause activity pip pauseFromUser")
                view.pauseFromUser()
                pip.updateActions(this, false, container.pipHasNext())
            }
            PipActions.NEXT -> {
                container.playNext(false)
                window.decorView.postDelayed({
                    if (isInPictureInPictureMode) {
                        pip.updateActions(this, container.pipIsPlaying(), container.pipHasNext())
                    }
                }, 600)
            }
        }
    }

    fun playbackFacts(): DetailPlaybackFacts = DetailPlaybackFacts(
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )

    /**
     * 音乐模式起播：把音乐队列塞进播放容器。
     *
     * 走的是和影视一模一样的链路（PlayContainer.setData → PlaybackStarter），
     * 区别只在 series.url 是 music://占位符，由 PlaybackStarter 换成真实地址。
     * 这样队列 / 上一首 / 下一首 / 播放模式 / 歌词 / 历史全部直接复用。
     */
    fun playMusicQueue(vod: com.github.tvbox.osc.bean.VodInfo) {
        val container = ensurePlayContainer()
        cancelCastWait()
        container.endCastPrepare()
        container.clearCastAbort()
        container.setData(
            com.github.tvbox.osc.player.PlaybackSession(
                vod,
                com.github.tvbox.osc.music.MusicSession.MUSIC_SOURCE_KEY,
                false,
            ),
        )
        runCatching { container.ensurePlaybackActive() }
        LOG.i("echo-music playMusicQueue [" + vod.name + "] index=" + vod.playIndex)
    }


    private fun releasePlayContainer() {
        playContainer?.hostDestroy()
        playContainer = null
    }

    override fun context(): Context = this

    override fun isPageAlive(): Boolean = !isFinishing && !isDestroyed

    override fun runOnUi(action: Runnable) {
        if (isPageAlive()) runOnUiThread(action)
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun requestNotificationPermission() {
        PermissionHelper.requestNotificationIfNeeded(this)
    }

    override fun onPlaybackLinesExhausted(): Boolean = startDetailFallbackAfterLinesExhausted()

    override fun showEpisodeSheet() {
        if (vm.vodInfo != null) vm.showEpisodeSheet()
    }

    fun playCurrent() {
        FloatLog.append("app", null, "playCurrent")
        val container = ensurePlayContainer()
        cancelCastWait()
        container.endCastPrepare()
        container.clearCastAbort()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.clearSourceSwitchTip()
            // 以前静默 return，表现为「点了播放什么都没有」
            Toast.makeText(this, getString(R.string.detail_no_playable_content), Toast.LENGTH_SHORT).show()
            LOG.i("echo-detail playCurrent session=null")
            FloatLog.append("app", null, "playCurrent session=null")
            return
        }
        container.setData(session)
        // 进页后立刻确保内核开播意图（与 FongmiKernelPlayer userWantsPlay 配合）
        runCatching { container.ensurePlaybackActive() }
    }

    fun ensurePlaying(): PlayContainer {
        val container = ensurePlayContainer()
        if (!engineOwnsDetailContent()) {
            playCurrent()
        } else {
            container.ensurePlaybackActive()
        }
        return container
    }

    /**
     * 引擎里已经装着内容、但装的是别的影片时（典型场景：在影片 A 的音乐页返回首页，
     * 再打开全新影片 B），不能直接复用，否则音乐页拿到的是 A 的海报与内容。
     */
    private fun engineOwnsDetailContent(): Boolean {
        val playing = PlaybackService.peek()?.controller()?.vod() ?: return false
        val info = vm.vodInfo ?: return false
        return contentMatches(playing, info)
    }

    private fun contentMatches(playing: VodInfo, info: VodInfo): Boolean {
        if (playing.id != info.id) return false
        // 首页复用同一播放器实例时 controller 的 sourceKey 可能取自上次会话，
        // 因此只在本页确实用过该源时才要求源一致，避免把同一影片误判成新内容。
        val expectedSource = vm.firstsourceKey
        return expectedSource.isEmpty()
            || playing.sourceKey == expectedSource
            || vm.sourceKey == expectedSource
    }

    fun openCast() {
        val container = ensurePlayContainer()
        cancelCastWait()
        container.beginCastPrepare()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.endCastPrepare()
            Toast.makeText(this, getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        container.setData(session)
        window.decorView.postDelayed(castWaitRunnable, CAST_URL_POLL_MS)
    }

    private fun cancelCastWait() {
        castWaitAttempts = 0
        window.decorView.removeCallbacks(castWaitRunnable)
    }

    fun musicPlaybackDetected(): Boolean {
        if (!MusicSettings.autoOpenPage()) return false
        val container = playContainer ?: return false
        val engine = PlaybackService.peek() ?: return false
        if (engine.isReleased() || engine.attachedPage() !== container) return false
        val state = engine.player().playState
        val accepted = state == PlayState.PREPARING ||
            state == PlayState.PREPARED ||
            state == PlayState.BUFFERING ||
            state == PlayState.BUFFERED ||
            state == PlayState.PLAYING
        return accepted && isAudioContent()
    }

    fun isAudioContent(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        val url = controller.webPlayUrl() ?: return false
        return PlaybackController.looksLikeAudioUrl(url) || controller.isAudioOnlyContent()
    }

    fun openMusicPlayer() {
        if (vm.vodInfo == null) {
            Toast.makeText(this, getString(R.string.detail_content_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        // 先按纯音频启动，避免详情页这一帧就把视频解码器建起来（交接后无法回收）。
        prepareAudioOnlyForMusicPage()
        ensurePlaying()
        if (!handOffToMusicPlayer()) {
            Toast.makeText(this, getString(R.string.detail_no_playable_content), Toast.LENGTH_SHORT).show()
        }
    }

    private fun prepareAudioOnlyForMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(true)
    }

    private fun restoreVideoAfterMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(false)
    }

    fun handOffToMusicPlayer(): Boolean {
        val container = playContainer ?: return false
        val playing = PlaybackService.peek()?.controller()?.vod()
        if (playing == null) {
            restoreVideoAfterMusicPage()
            return false
        }
        // 引擎里若还装着别的影片，先切到本页内容，避免音乐页显示上一部影片。
        val info = vm.vodInfo
        if (info != null && !contentMatches(playing, info)) {
            LOG.i("echo-music handoff switch content: " + playing.id + " -> " + info.id)
            restoreVideoAfterMusicPage()
            playCurrent()
        }
        prepareAudioOnlyForMusicPage()
        val keepDetailPage = !isAudioContent()
        container.setExitingPreview(true)
        container.handOverToNextPage()
        MusicPlayerActivity.start(this, vm.firstsourceKey)
        if (keepDetailPage) pendingEpisodeSync = true else finish()
        return true
    }

    private fun syncEpisodeAfterMusicPage() {
        if (!pendingEpisodeSync) return
        pendingEpisodeSync = false
        val playing = PlaybackService.peek()?.controller()?.vod() ?: return
        val info = vm.vodInfo ?: return
        if (playing.id != info.id) return
        if (playing.playFlag == info.playFlag && playing.playIndex == info.playIndex) return
        info.playFlag = playing.playFlag
        info.playIndex = playing.playIndex
        vm.bumpRevision()
    }

    fun applyFullscreen(full: Boolean) {
        if (fullScreen == full) return
        fullScreen = full
        requestedOrientation = orientationPolicyValue()
        if (full) {
            // Drive the orientation NOW, synchronously with fullscreen
            // entry (the way LivePlayActivity does): logtest35 deferred
            // the landscape request entirely to the one-shot video-size
            // watch below, so when that single-fire chain did not
            // deliver, nothing ever requested landscape and playback
            // stayed in the portrait letterbox until the user tapped
            // the manual rotate control. A locked policy has already
            // been applied above and keeps winning; under follow-system
            // a known size decides immediately, and an unknown size
            // defaults to landscape (video content), with the watch
            // correcting portrait videos when their size lands.
            val container = playContainer
            if (container != null && container.hasVideoSize()) {
                applyVideoOrientation(container.isPortraitVideo())
            } else if (orientationPolicyValue() == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            armVideoSizeWatch()
            hideSysBar()
        } else {
            videoSizeWatchArmed = false
            playContainer?.setVideoSizeReadyListener(null)
            window.decorView.removeCallbacks(videoSizeTimeoutRunnable)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
            applyHideStatusBarPref()
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
        syncFullBoxSideEffects()
    }

    private fun armVideoSizeWatch() {
        val container = playContainer ?: return
        if (container.hasVideoSize()) {
            applyVideoOrientation(container.isPortraitVideo())
            return
        }
        if (videoSizeWatchArmed) return
        videoSizeWatchArmed = true
        container.setVideoSizeReadyListener { portraitVideo -> onVideoSizeReady(portraitVideo) }
        window.decorView.postDelayed(videoSizeTimeoutRunnable, VIDEO_SIZE_WATCH_TIMEOUT_MS)
    }

    private fun onVideoSizeReady(portraitVideo: Boolean) {
        if (!videoSizeWatchArmed) return
        videoSizeWatchArmed = false
        window.decorView.removeCallbacks(videoSizeTimeoutRunnable)
        playContainer?.setVideoSizeReadyListener(null)
        applyVideoOrientation(portraitVideo)
    }

    private fun applyVideoOrientation(portraitVideo: Boolean) {
        if (!fullScreen) return
        requestedOrientation = if (portraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        settleRotationAfterExit()
    }

    fun settleRotationAfterExit() {
        if (!vm.rotating.value) return
        vm.rotating.value = false
        syncFullBoxSideEffects()
    }

    fun setPlayerTouchBlocked(blocked: Boolean) {
        playContainer?.setTouchBlocked(blocked)
    }

    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return DetailFullScreenFrame.playerFullScreen(vm.rotating.value, fullScreen, landNow)
    }

    private fun syncFullBoxSideEffects() {
        playContainer?.setPreviewMode(!isFullBox())
    }

    fun startDetailFallbackAfterLinesExhausted(): Boolean = vm.startFallbackAfterLinesExhausted()

    override fun onResume() {
        super.onResume()
        FloatLog.append("app", null, "lifecycle onResume pip=$isInPictureInPictureMode pipActive=$pipModeActive")
        registerReaderRouter()
        applyStatusBarAppearance()
        if (!isInPictureInPictureMode && !pipModeActive) playContainer?.setPipHold(false)
        playContainer?.hostResume()
        syncEpisodeAfterMusicPage()
        val container = playContainer ?: return
        vm.applyPlaybackEntry(
            DetailPlaybackEntry.MusicReturn,
            resumable = container.hasClaimedPlayback() && !container.ownsEngineContent(),
        )
    }

    override fun onPause() {
        FloatLog.append("app", null, "lifecycle onPause pip=$isInPictureInPictureMode pipActive=$pipModeActive")
        playContainer?.hostPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        FloatLog.append("app", null, "lifecycle onStop pipActive=$pipModeActive")
        // PiP 小窗被 X 关掉/被系统回收时 Activity 走 onStop 而 PiP 闩锁
        // 还在（回全屏走的是 onModeChanged(false) 先清闩锁，不会走到这）：
        // 停播收尾并关页，对标 webhtv 退出 PiP 时的 isStop() → finish()。
        if (pipModeActive) {
            pipModeActive = false
            playContainer?.setPipHold(false)
            playContainer?.stopForExitFullscreen()
            finish()
        }
    }

    override fun onDestroy() {
        if (PipSession.handler === pipActionHandler) PipSession.handler = null
        PlaybackService.peek()?.controller()?.clearReaderRouter(readerRouter)
        window.decorView.removeCallbacks(videoSizeTimeoutRunnable)
        window.decorView.removeCallbacks(castWaitRunnable)
        releasePlayContainer()
        vm.destroyEngine()
        super.onDestroy()
    }
}
