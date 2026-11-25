package com.github.tvbox.osc.ui.player

import com.github.tvbox.osc.player.FongmiKernelPlayer
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.dlna.CastVideo
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PageHost
import com.github.tvbox.osc.player.PlaybackController
import com.github.tvbox.osc.player.PlaybackEngine
import com.github.tvbox.osc.player.PlaybackHostApi
import com.github.tvbox.osc.player.PlaybackPage
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.PlaybackViewBridge
import com.github.tvbox.osc.player.PlayerHelper
import com.github.tvbox.osc.player.PreloadCoordinator
import com.github.tvbox.osc.player.VideoOrientation
import com.github.tvbox.osc.player.controller.VideoPlayerController
import com.github.tvbox.osc.player.controller.PlayerControlApi
import com.github.tvbox.osc.player.danmu.DanmuLoadController
import com.github.tvbox.osc.player.state.CastSheetState
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.FloatLog
import me.jessyan.autosize.AutoSize
import me.jessyan.autosize.internal.CustomAdapt
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.HashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlayContainer(activity: Activity) : FrameLayout(activity), CustomAdapt, PlaybackHostApi, PlaybackPage {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val trackSelector: TrackSelectorDelegate = TrackSelectorDelegate(object : TrackSelectorDelegate.Host {
        override fun player(): MyVideoView? = mVideoView

        override fun context(): Context = mContext

        override fun uiState(): PlayerUiState = mController.getUiState()
    })

    lateinit var scheduler: PlaybackController
    private lateinit var surfaceSlot: FrameLayout
    private var engine: PlaybackEngine? = null
    var mPageHost: PageHost? = null
    var mActivity: Activity? = activity

    private val mContext: Context = activity

    private val tipStateListener: TipStateListener = TipStateListener { overlays.onTipStateChanged(it) }

    private val viewBridge: PlaybackViewBridge = PlayContainerViewBridge(this)

    private val controlListener: PlayContainerControlListener = PlayContainerControlListener(this)

    private val overlays: PlayContainerOverlays = PlayContainerOverlays(this)

    private val subtitles: PlayContainerSubtitles = PlayContainerSubtitles(this)

    private var qualitySelectedListener: OnQualitySelectedListener? = null

    private var videoSizeReadyListener: ((Boolean) -> Unit)? = null

    private var touchBlocked = false

    /** 画中画手动钮回调（宿主页注册）；为真时 hostPause 不掐播放（进 PiP 续播）。 */
    var onPipRequested: (() -> Unit)? = null

    /** Top-bar X callback (host page registers): stop playback and close the player page. */
    var onCloseRequested: (() -> Unit)? = null

    /** Fullscreen toggle callback (host page registers): bottom-bar button and double-tap. */
    var onFullscreenRequested: (() -> Unit)? = null

    private var pipHold: Boolean = false

    private var lifecyclePaused: Boolean = false
    private var ownedPlaybackKey: String? = null
    private var handedOver: Boolean = false
    private var castPrepareOnly: Boolean = false
    private var hostSuspended: Boolean = false

    var mVideoView: MyVideoView? = null
    lateinit var mController: PlayerControlApi
    internal var mHandler: Handler? = null
    var mExitingPreview: Boolean = false
    internal var previewMode: Boolean = false
    var danmuLoadController: DanmuLoadController? = null
    private val videoDuration: Long = -1

    init {
        val e = PlaybackService.engine(activity)
        engine = e
        scheduler = e.controller()
        AutoSize.autoConvertDensity(activity, getSizeInDp(), isBaseOnWidth())
        LayoutInflater.from(activity).inflate(R.layout.view_play_container, this, true)
        PlayerTipBridge.hide()
        init()
        PlayerTipBridge.setTipStateListener(tipStateListener)
        scheduler.setViewBridge(viewBridge)
        engine?.attach(this)
    }

    override fun viewBridge(): PlaybackViewBridge {
        return viewBridge
    }

    override fun renderSlot(): ViewGroup {
        return surfaceSlot
    }

    override fun onServiceStopped() {
        mVideoView = null
        engine = null
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    fun isAttached(): Boolean {
        val host = mPageHost
        if (host != null) return host.isPageAlive()
        return mActivity?.isFinishing == false
    }

    fun setPageHost(host: PageHost) {
        mPageHost = host
    }

    fun setEpisodeSheetOpen(open: Boolean) {
        if (mController != null) mController.getUiState().episodeSheetOpen = open
    }

    fun interface OnQualitySelectedListener {
        fun onQualitySelected(position: Int)
    }

    fun setOnQualitySelectedListener(listener: OnQualitySelectedListener) {
        qualitySelectedListener = listener
    }

    fun setVideoSizeReadyListener(listener: ((Boolean) -> Unit)?) {
        videoSizeReadyListener = listener
    }

    fun setTouchBlocked(blocked: Boolean) {
        touchBlocked = blocked
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        touchBlocked || super.onInterceptTouchEvent(ev)

    private fun notifyVideoSizeReady(portraitVideo: Boolean) {
        val deliver = Runnable {
            val listener = videoSizeReadyListener
            videoSizeReadyListener = null
            listener?.invoke(portraitVideo)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run() else post(deliver)
    }

    override fun hostResume() {
        mExitingPreview = false
        if (mController != null) mController.setLifecyclePaused(false)
        LOG.i(
            "echo-p2 hostResume claimed=" + hasClaimedPlayback()
                + " owns=" + ownsEngineContent()
                + " playing=" + (mVideoView?.isPlaying ?: false)
                + " lifecyclePaused=" + lifecyclePaused
                + " suspended=" + hostSuspended
                + " handedOver=" + handedOver,
        )
        FloatLog.append("app", null, "hostResume claimed=" + hasClaimedPlayback() + " playing=" + (mVideoView?.isPlaying ?: false))
        val resumedFromBackground = hostSuspended && !handedOver
        hostSuspended = false
        reattachIfOwnedByOther()
        if (resumedFromBackground) rebuildRenderViewAfterBackground()
        engine?.consumeServiceLostKeep(ownsEngineContent())
        val view = mVideoView
        if (view != null && lifecyclePaused) {
            lifecyclePaused = false
            if (ownsEngineContent()) {
                view.resume()
            }
        }
    }

    private fun rebuildRenderViewAfterBackground() {
        val view = mVideoView ?: return
        if (view.mediaPlayer == null) return
        if (engine?.attachedPage() !== this) return
        if (TextUtils.isEmpty(scheduler.startedPlaybackKey())) return
        val cfg = scheduler.playerCfg() ?: return
        if (cfg.optInt("pr", 1) != 1) return
        view.rebuildRenderView(1)
        LOG.i("echo-p2 rebuild render view after background")
    }

    private fun reattachIfOwnedByOther() {
        handedOver = false
        if (surfaceSlot == null) return
        val e = engine ?: return
        if (e.attachedPage() === this) return
        if (e.isReleased()) return
        if (e.isLiveMode()) e.exitLive()
        e.attach(this)
        val view = mVideoView
        if (!ownsEngineContent() && view != null) {
            view.saveCurrentProgress()
        }
        if (view != null && mController != null) {
            mController.setKernelProvider(view)
            val state = view.playState
            if (view.mediaPlayer != null
                && state != PlayState.IDLE && state != PlayState.ERROR
                && ownsEngineContent()
            ) {
                rebindPlaybackOverlay()
            }
        }
        if (ownsEngineContent()) {
            syncSessionVod()
        }
        LOG.i("echo-p4 re-attach after live/other page")
    }

    fun setPipHold(hold: Boolean) {
        pipHold = hold
    }

    fun pipIsPlaying(): Boolean = mVideoView?.isPlaying == true

    fun pipVideoSize(): IntArray? {
        val size = mVideoView?.videoSize ?: return null
        return if (size.size >= 2 && size[0] > 0 && size[1] > 0) size else null
    }

    /** 与 webhtv getScale() 同语义：播放配置 sc（0 默认 / 1=16:9 / 2=4:3 / …）。 */
    fun pipScale(): Int = try {
        scheduler.playerCfg()?.optInt("sc", 0) ?: 0
    } catch (e: Exception) {
        0
    }

    fun pipHasNext(): Boolean {
        val vod = scheduler.vod() ?: return false
        val episodes = vod.seriesMap?.get(vod.playFlag) ?: return false
        return vod.playIndex + 1 < episodes.size
    }

    fun hideControlsForPip() {
        if (mController != null) mController.getUiState().controlsVisible = false
    }

    override fun hostPause() {
        LOG.i(
            "echo-p2 hostPause playing=" + (mVideoView?.isPlaying ?: false)
                + " exiting=" + mExitingPreview + " pipHold=" + pipHold,
        )
        FloatLog.append("app", null, "hostPause playing=" + (mVideoView?.isPlaying ?: false) + " exiting=" + mExitingPreview + " pipHold=" + pipHold)
        if (pipHold) return
        hostSuspended = true
        val view = mVideoView
        if (view != null && !mExitingPreview && !scheduler.isConfirmedAudioOnly()) {
            lifecyclePaused = view.isPlaying
            if (mController != null) mController.setLifecyclePaused(lifecyclePaused)
            view.pauseForShell()
        }
    }

    fun ensurePlaybackActive() {
        FloatLog.append("app", null, "ensurePlaybackActive playing=" + (mVideoView?.isPlaying ?: false) + " state=" + mVideoView?.playState)
        clearCastAbort()
        val view = mVideoView ?: return
        if (view.isPlaying) return
        when (view.playState) {
            PlayState.IDLE, PlayState.COMPLETED, PlayState.ERROR -> play(true)
            PlayState.PREPARING, PlayState.START_ABORT -> Unit
            else -> view.resume()
        }
    }

    fun handOverToNextPage() {
        val e = engine ?: return
        handedOver = true
        e.detachForHandover(this)
    }

    override fun hostDestroy() {
        LOG.i("echo-music destroy: hostDestroy enter")
        endCastPrepare()
        PlayerTipBridge.clearTipStateListener(tipStateListener)
        qualitySelectedListener = null
        videoSizeReadyListener = null
        onPipRequested = null
        onCloseRequested = null
        onFullscreenRequested = null
        pipHold = false
        if (!handedOver) engine?.detach(this)
        overlays.cancelPreloadToast()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        danmuLoadController?.destroy()
        danmuLoadController = null
        mVideoView = null
        if (mController != null) mController.stopOther()
        mActivity = null
        LOG.i("echo-music destroy: hostDestroy done")
    }

    override fun getSizeInDp(): Float {
        return if (mActivity is CustomAdapt) (mActivity as CustomAdapt).getSizeInDp() else 0f
    }

    override fun isBaseOnWidth(): Boolean {
        return mActivity !is CustomAdapt || (mActivity as CustomAdapt).isBaseOnWidth()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            subtitles.applySubtitleTextSize()
        }
        if (event.type == RefreshEvent.TYPE_SET_DANMU_SETTINGS) {
            overlays.setDanmuViewSettings(event.obj is Boolean && event.obj as Boolean)
        } else if (event.type == RefreshEvent.TYPE_DANMU_REFRESH) {
            overlays.checkDanmu(if (event.obj is String) event.obj as String else "")
        }
    }

    private fun init() {
        initView()
        overlays.initDanmuView()
    }

    fun applyDanmuSettings(reload: Boolean) = overlays.applyDanmuSettings(reload)

    fun checkDanmu(danmu: String?, callback: DanmuLoadController.LoadCallback?) =
        overlays.checkDanmu(danmu, callback)

    fun startDanmuIfReady() = overlays.startDanmuIfReady()

    fun resetDanmuState() = overlays.resetDanmuState()

    fun reloadDanmuForPlayback() = overlays.reloadDanmuForPlayback()

    private fun initView() {
        EventBus.getDefault().register(this)
        mHandler = Handler { msg ->
            when (msg.what) {
                MSG_PARSE_TIMEOUT -> {
                    scheduler.stopParse()
                    overlays.errorWithRetry(mContext.getString(R.string.player_error_sniff), false)
                }
            }
            false
        }
        surfaceSlot = findViewById(R.id.surfaceSlot)
        val activity = mActivity ?: return
        mController = VideoPlayerController(activity)
        (mController as? VideoPlayerController)?.onVideoSizeReady = { width, height ->
            if (VideoOrientation.isUsableSize(width, height)) {
                notifyVideoSizeReady(VideoOrientation.isPortrait(width, height))
            }
        }

        mController.getLyricView().setTextSize(if (previewMode) 16f else 24f)
        mController.setCanChangePosition(true)
        mController.setEnableInNormal(true)
        mController.setGestureEnabled(true)
        mVideoView = engine?.player()
        FloatLog.append("app", null, "container attachEngine playerNull=" + (mVideoView == null))
        mController.setListener(controlListener)
        if (mVideoView != null) mController.setKernelProvider(mVideoView)
    }

    override fun showCast() {
        showCastDialog()
    }

    fun showCastDialog() {
        val webUrl = scheduler.webPlayUrl()
        if (TextUtils.isEmpty(webUrl)) {
            Toast.makeText(mContext, mContext.getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAttached()) return
        val castUrl = scheduler.getCastUrl(webUrl)
        if (castUrl.isNullOrEmpty()) {
            Toast.makeText(mContext, mContext.getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        val headers: HashMap<String, String>? = scheduler.webHeaderMap()?.let { HashMap(it) }
        val title = getCastTitle()
        scope.launch {
            val video = CastVideo(castUrl, title, headers, getCastPosition())
            val uiState = mController.getUiState()
            uiState.castSheet = CastSheetState(video) {
                FloatLog.append("app", null, "pause container castCancel pauseFromUser")
                mVideoView?.pauseFromUser()
            }
        }
    }

    fun openDanmuSearchSheet() = overlays.openDanmuSearchSheet()

    fun beginCastPrepare() {
        castPrepareOnly = true
        scheduler.setCastPrepareOnly(true)
    }

    fun clearCastAbort() {
        scheduler.clearCastAbort()
    }

    fun endCastPrepare() {
        if (!castPrepareOnly && !scheduler.isCastPrepareOnly()) return
        castPrepareOnly = false
        scheduler.closeCastPrepare()
        scheduler.stopParse()
        scheduler.cancelPlayTimeout()
        LOG.i("echo-cast prepare end")
    }

    private fun syncSessionVod() {
        if (mController == null || scheduler == null) return
        mController.getUiState().sessionVod = scheduler.vod()
    }

    private fun getCastTitle(): String {
        val vod = scheduler.vod() ?: return "TVBox"
        val series = vod.seriesMap?.get(vod.playFlag)?.getOrNull(vod.playIndex)
        if (series != null) return vod.name + " " + series.name
        val name = vod.name
        return if (name.isNullOrEmpty()) "TVBox" else name
    }

    private suspend fun getCastPosition(): Long {
        val live = try {
            mVideoView?.currentPosition ?: 0L
        } catch (e: Exception) {
            0L
        }
        if (live > 0) return live
        return try {
            scheduler.getSavedProgress(scheduler.progressKey())
        } catch (e: Exception) {
            0L
        }
    }

    fun setSubtitle(path: String?) = subtitles.setSubtitle(path)

    fun selectMySubtitle() = subtitles.selectMySubtitle()

    fun setSubtitleViewTextStyle(style: Int) = subtitles.setSubtitleViewTextStyle(style)

    fun selectMyInternalSubtitle() = subtitles.selectMyInternalSubtitle()

    override fun onLocalSubtitlePicked(uri: Uri) = subtitles.onLocalSubtitlePicked(uri)

    fun selectMyAudioTrack() {
        trackSelector.selectAudioTrack()
    }

    fun selectMyVideoTrack() {
        trackSelector.selectVideoTrack()
    }

    fun setTip(msg: String, loading: Boolean, err: Boolean) = overlays.setTip(msg, loading, err)

    fun hideTip() = overlays.hideTip()

    fun hideTipOnUiThread() = overlays.hideTipOnUiThread()

    fun showPreloadReady() = overlays.showPreloadReady()

    fun hidePreloadReady() = overlays.hidePreloadReady()

    fun errorWithRetry(err: String, finish: Boolean) = overlays.errorWithRetry(err, finish)

    fun initSubtitleView() = subtitles.initSubtitleView()

    fun closeSubtitles() = subtitles.closeSubtitles()

    fun toggleSubtitles() = subtitles.toggleSubtitles()

    fun trackMemoryKey(): String = subtitles.trackMemoryKey()

    private fun rebindPlaybackOverlay() {
        subtitles.initSubtitleView()
        overlays.checkDanmu(scheduler.playDanmu())
    }

    fun clearLyricView() = subtitles.clearLyricView()

    fun releasePlayerKernel() {
        val e = engine
        val view = mVideoView
        if (e != null) {
            e.releasePlayer()
        } else if (view != null) {
            view.release()
        }
    }

    fun setAudioOnlyMode(audioOnly: Boolean) {
        (mVideoView?.mediaPlayer as? FongmiKernelPlayer)?.setAudioOnlyMode(audioOnly)
    }

    fun isAudioOnlyMode(): Boolean =
        (mVideoView?.mediaPlayer as? FongmiKernelPlayer)?.isAudioOnlyMode() == true

    fun reviveEngineIfReleased(): Boolean {
        if (engine?.isReleased() == false) return false
        val activity = mActivity
        if (activity == null || surfaceSlot == null) return false
        if (scheduler != null) scheduler.stopPlaybackForPageExit()
        val revived = PlaybackService.engine(activity)
        engine = revived
        scheduler = revived.controller()
        mVideoView = revived.player()
        FloatLog.append("app", null, "container reviveEngine playerNull=" + (mVideoView == null))
        revived.attach(this)
        handedOver = false
        if (mVideoView != null) {
            mController.setKernelProvider(mVideoView)
            danmuLoadController?.setVideoView(mVideoView)
        }
        LOG.i("echo-p2 revive engine after release")
        return true
    }

    override fun play(reset: Boolean) {
        FloatLog.append("app", null, "container play reset=$reset")
        reviveEngineIfReleased()
        scheduler.play(reset)
    }

    override fun selectQuality(position: Int): Boolean {
        val accepted = scheduler != null && scheduler.selectQuality(position)
        if (accepted) qualitySelectedListener?.onQualitySelected(position)
        return accepted
    }

    override fun setData(session: PlaybackSession) {
        val current = engine
        if (current == null || current.isReleased()) {
            if (!reviveEngineIfReleased()) {
                LOG.i("echo-p5 setData skipped: engine released")
                return
            }
        }
        FloatLog.append("app", null, "setData key=" + session.playbackKey())
        val engineRef = engine ?: return
        LOG.i(
            "echo-p2 setData: key=" + session.playbackKey()
                + " sameOwned=" + (!castPrepareOnly && isSamePlaybackOwned(session))
                + " mediaPlayer=" + (mVideoView?.mediaPlayer != null)
                + " state=" + mVideoView?.playState,
        )
        if (!castPrepareOnly && isSamePlaybackOwned(session)) {
            LOG.i("echo-p3 take over same playback: " + session.playbackKey())
            engineRef.setData(session)
            syncSessionVod()
            mController.setPlayerConfig(requireNotNull(scheduler.playerCfg()) { "player cfg missing on takeover" })
            scheduler.markContentStarted()
            scheduler.publishTitle()
            scheduler.clearTriedLines()
            scheduler.setUserPickedLine(session.userPickedLine())
            rebindPlaybackOverlay()
            ownedPlaybackKey = session.playbackKey()
            if (mController != null) mController.onContentUrlSet(mVideoView?.currentUrl)
            if (alignInstanceConfigOnTakeover()) return
            val view = mVideoView
            if (view != null && !view.isPlaying) view.start()
            return
        }
        // 同片换集/换线路：对标昏黄 stopPlaybackForRefresh + 重新 playEpisode
        // 禁止 reuse 旧媒体，避免 UI 已是 107 集画面仍是第 1 集
        val sameVodSwitch = !castPrepareOnly && isSameVodEpisodeSwitch(session)
        mVideoView?.forgetVideoSize()
        engineRef.setData(session)
        syncSessionVod()
        mController.setPlayerConfig(requireNotNull(scheduler.playerCfg()) { "player cfg missing on setData" })
        scheduler.clearTriedLines()
        scheduler.setUserPickedLine(session.userPickedLine())
        ownedPlaybackKey = if (castPrepareOnly) null else session.playbackKey()
        if (sameVodSwitch) {
            // 同片换集/线：必须换新地址（play true），但内核要 REUSE。
            // releasePlayer 后 Exo 常能重建，MPV 容易黑屏/无法再播。
            scheduler.setReusePlayerOnSwitch(true)
            playViaScheduler(true)
        } else {
            playViaScheduler(false)
        }
    }

    private fun isSameVodEpisodeSwitch(session: PlaybackSession): Boolean {
        val view = mVideoView
        if (scheduler == null || view == null || view.mediaPlayer == null) return false
        val started = scheduler.startedPlaybackKey() ?: return false
        val key = session.playbackKey()
        val cut = key.lastIndexOf('|')
        return cut > 0 && started.startsWith(key.substring(0, cut + 1))
    }

    fun playViaScheduler(reset: Boolean) {
        reviveEngineIfReleased()
        scheduler.play(reset)
    }

    fun replayCurrentAddress() {
        clearCastAbort()
        overlays.reloadDanmuForPlayback()
        val url = scheduler.webPlayUrl()
        if (url != null && !url.isEmpty()) {
            scheduler.stopParse()
            scheduler.initParseLoadFound()
            mVideoView?.saveCurrentProgress()
            if (!scheduler.isCrossContentReuseAllowed()) releasePlayerKernel()
            scheduler.goPlayUrl(url, scheduler.webHeaderMap())
        } else {
            playViaScheduler(false)
        }
    }

    private fun alignInstanceConfigOnTakeover(): Boolean {
        val view = mVideoView
        if (view == null || scheduler == null) return false
        val cfg = scheduler.playerCfg() ?: return false
        view.setScreenScaleType(cfg.optInt("sc", 0))
        if (cfg.optInt("pl", 2) >= 10) return false
        // 基准必须用「配置里期望的渲染类型」，不能用渲染视图工厂：音乐页的
        // switchRenderToTexture 会把工厂改成 Texture，退出音乐页时工厂与配置不一致，
        // 会据此误判成渲染类型变更而白白重建内核。
        val renderChanged = !scheduler.isConfirmedAudioOnly()
            && view.isSurfaceRenderMismatch(cfg.optInt("pr", 1))
        val decodeChanged = !PlayerHelper.isExoDecodeApplied(cfg)
        if (!renderChanged && !decodeChanged) return false
        LOG.i(
            if (renderChanged) "echo-render-changed: rebuild kernel on takeover"
            else "echo-exo-decode-changed: rebuild kernel on takeover",
        )
        scheduler.beginNewPlay()
        controlListener.replay(false)
        return true
    }

    fun hasClaimedPlayback(): Boolean {
        return !TextUtils.isEmpty(ownedPlaybackKey)
    }

    fun ownsEngineContent(): Boolean {
        if (!hasClaimedPlayback()) return false
        return TextUtils.equals(ownedPlaybackKey, scheduler?.startedPlaybackKey())
    }

    private fun isSamePlaybackOwned(session: PlaybackSession): Boolean {
        val view = mVideoView
        if (HistoryHelper.isIncognito() && (view == null || !view.isPlaying)) return false
        if (!TextUtils.equals(scheduler.startedPlaybackKey(), session.playbackKey())) return false
        val e = engine ?: return false
        if (e.isLiveMode()) return false
        if (view == null || view.mediaPlayer == null) return false
        val state = view.playState
        return state != PlayState.ERROR && state != PlayState.IDLE
    }

    override fun onBackPressed(): Boolean {
        return mController.onBackPressed()
    }

    fun isPortraitVideo(): Boolean {
        return mVideoView?.isPortraitVideo() == true
    }

    fun hasVideoSize(): Boolean {
        val size = mVideoView?.videoSize ?: return false
        return size.size >= 2 && size[0] > 0 && size[1] > 0
    }

    fun hasCastUrl(): Boolean = !TextUtils.isEmpty(scheduler.webPlayUrl())

    override fun setExitingPreview(exitingPreview: Boolean) {
        mExitingPreview = exitingPreview
    }

    override fun resumeFromMediaSession() {
        val view = mVideoView ?: return
        view.start()
        scheduler.updateMusicSession()
    }

    override fun pauseFromMediaSession() {
        val view = mVideoView ?: return
        FloatLog.append("app", null, "pause container pauseFromMediaSession")
        view.pauseFromUser()
        scheduler.updateMusicSession()
    }

    override fun stopFromMediaSession() {
        FloatLog.append("app", null, "pause container stopFromMediaSession")
        mVideoView?.pauseFromUser()
        scheduler.stopMusicSession()
    }

    override fun seekFromMediaSession(position: Long) {
        val view = mVideoView ?: return
        view.seekTo(position)
        scheduler.updateMusicSession()
    }

    override fun playNext(isProgress: Boolean) {
        scheduler.clearTriedLines()
        val vod = scheduler.vod()
        val episodes = vod?.seriesMap?.get(vod.playFlag)
        if (vod == null || episodes == null || vod.playIndex + 1 >= episodes.size) {
            Toast.makeText(mContext, mContext.getString(R.string.player_last_episode), Toast.LENGTH_SHORT).show()
            return
        }
        vod.playIndex++
        scheduler.setUserPickedLine(false)
        scheduler.setReusePlayerOnSwitch(true)
        playViaScheduler(false)
    }

    override fun playPrevious() {
        scheduler.clearTriedLines()
        val vod = scheduler.vod()
        val episodes = vod?.seriesMap?.get(vod.playFlag)
        if (vod == null || episodes == null || vod.playIndex - 1 < 0) {
            Toast.makeText(mContext, mContext.getString(R.string.player_first_episode), Toast.LENGTH_SHORT).show()
            return
        }
        vod.playIndex--
        scheduler.setUserPickedLine(false)
        scheduler.setReusePlayerOnSwitch(true)
        playViaScheduler(false)
    }

    override fun setPlayTitle(show: Boolean) {
        if (!show) {
            mController.setTitle("")
            return
        }
        val vod = scheduler.vod()
        val vs = if (vod == null) null else scheduler.currentSeries(vod.playFlag, vod.playIndex)
        mController.setTitle(if (vod == null) "" else if (vs == null) vod.name ?: "" else vod.name + " " + vs.name)
    }

    fun buildPreloadSnapshot(): PreloadCoordinator.Snapshot? {
        try {
            val vod = scheduler.vod()
            val seriesMap = vod?.seriesMap
            if (vod == null || seriesMap == null) return null
            val episodes = seriesMap[vod.playFlag]
            if (episodes == null || vod.playIndex < 0 || vod.playIndex + 1 >= episodes.size) return null
            val next = episodes[vod.playIndex + 1]
            if (next == null || TextUtils.isEmpty(next.url)) return null
            val nextIndex = vod.playIndex + 1
            val nextKey = vod.sourceKey + vod.id + vod.playFlag + nextIndex + next.name
            val nextSubtKey = vod.sourceKey + "-" + vod.id + "-" + vod.playFlag + "-" + nextIndex + "-" + next.name + "-subt"
            val cfg = scheduler.playerCfg()
            val startSkipMs = if (cfg == null) 0L else cfg.optInt("st", 0) * 1000L
            val mediaPlayer = mVideoView?.mediaPlayer
            val exoKernel = (mediaPlayer as? FongmiKernelPlayer)?.isMpvKernel() == false
            return PreloadCoordinator.Snapshot(
                mContext,
                scheduler.sourceKey(),
                vod.playFlag,
                scheduler.progressKey(),
                nextKey,
                next.url,
                nextSubtKey,
                startSkipMs,
                exoKernel,
            )
        } catch (th: Throwable) {
            LOG.i("echo-preload-skip: snapshot error " + th)
            return null
        }
    }

    override fun setAutoSwitchLineEnabled(enabled: Boolean) {
        scheduler.setAutoSwitchLineEnabled(enabled)
    }

    override fun setPreviewMode(previewMode: Boolean) {
        this.previewMode = previewMode
        if (mController != null) {
            mController.setPreviewMode(previewMode)
            mController.getLyricView().setTextSize(if (previewMode) 16f else 24f)
            subtitles.applySubtitleTextSize()
        }
    }

    override fun toggleControllerControls() {
        if (mController != null) {
            mController.toggleControlBar()
        }
    }

    override fun stopForSourceSwitch(tip: String) {
        endCastPrepare()
        val view = mVideoView ?: return
        scheduler.cancelPlayTimeout()
        scheduler.stopParse()
        scheduler.markStoppedForSourceSwitch()
        scheduler.stopMusicSessionForFailedPlayback()

        val position = view.currentPosition
        scheduler.setPendingInherit(scheduler.progressKey(), position)
        FloatLog.append("app", null, "pause container switchSource pauseForShell")
        view.pauseForShell()
        if (scheduler.isCrossContentReuseAllowed()) {
            view.saveCurrentProgress()
            LOG.i("echo-switchSource keep player kernel for reuse")
        } else {
            releasePlayerKernel()
        }
        if (mController != null) mController.stopOther()
        overlays.resetDanmuState()
        scheduler.setWebPlayUrl(null)
        scheduler.setWebHeaderMap(null)
        scheduler.initParseLoadFound()
        LOG.i("echo-switchSource stop at " + position + "ms, key=" + scheduler.progressKey())
        if (!TextUtils.isEmpty(tip)) setTip(tip, true, false)
    }

    override fun clearSourceSwitchTip() {
        if (!scheduler.isSwitchStopPending()) return
        hideTipOnUiThread()
    }

    fun stopForContentSwitch() {
        endCastPrepare()
        val view = mVideoView ?: return
        if (!ownsEngineContent()) return
        scheduler.cancelInFlight()
        FloatLog.append("app", null, "stopForContentSwitch pauseForShell")
        view.pauseForShell()
        view.saveCurrentProgress()
        view.stopPlaybackKeepPlayer()
    }

    fun stopForExitFullscreen() {
        val view = mVideoView ?: return
        if (!hasClaimedPlayback()) return
        scheduler.cancelInFlight()
        if (mController != null) mController.setExitPaused(true)
        FloatLog.append("app", null, "stopForExitFullscreen pauseForShell")
        view.pauseForShell()
        view.saveCurrentProgress()
        view.stopPlaybackKeepPlayer()
        scheduler.stopMusicSession()
        ownedPlaybackKey = null
    }

    fun getPlayer(): MyVideoView? {
        return mVideoView
    }

    inner class MyWebView(context: Context) : WebView(context) {

        override fun setOverScrollMode(mode: Int) {
            super.setOverScrollMode(mode)
            if (mContext is Activity) {
                AutoSize.autoConvertDensityOfCustomAdapt(mContext as Activity, this@PlayContainer)
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            return false
        }
    }

    companion object {

        private const val MSG_PARSE_TIMEOUT = 100
    }
}
