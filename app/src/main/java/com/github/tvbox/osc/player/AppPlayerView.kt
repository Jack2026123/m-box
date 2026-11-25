package com.github.tvbox.osc.player

import android.app.Activity
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Parcelable
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.tvbox.osc.player.host.EngineSurfaceRenderViewFactory
import com.github.tvbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.tvbox.osc.player.host.PlayerRenderView
import com.github.tvbox.osc.player.host.PlayerRenderViewFactory
import com.github.tvbox.osc.player.host.PlayerAudioFocus
import com.github.tvbox.osc.player.host.AudioFocusTarget
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.FloatLog
import com.github.tvbox.osc.util.PlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

open class AppPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface ProgressSink {
        fun saveProgress(url: String?, progress: Long)

        suspend fun getSavedProgress(url: String?): Long
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var startPlaySeq = 0

    protected var mMediaPlayer: KernelPlayer? = null

    protected var mProgressSink: ProgressSink? = null

    protected val mPlayerContainer: FrameLayout = FrameLayout(context)

    protected var mRenderView: PlayerRenderView? = null

    protected var mRenderViewFactory: PlayerRenderViewFactory =
        if (com.github.tvbox.osc.util.KV.get(com.github.tvbox.osc.util.HawkConfig.PLAY_RENDER, 1) == 1) {
            EngineSurfaceRenderViewFactory.create()
        } else {
            EngineTextureRenderViewFactory.create()
        }

    protected var mCurrentScreenScaleType = SCREEN_SCALE_DEFAULT

    protected var mVideoSize = intArrayOf(0, 0)

    protected var mUrl: String? = null

    protected var mProgressKey: String? = null

    protected var mHeaders: Map<String, String>? = null

    protected var mCurrentPosition = 0L

    private val mZeroStartKeys = LinkedHashSet<String>()

    private var mLastReportedPlayState = PlayState.IDLE

    private val _playStateFlow = MutableSharedFlow<PlayState>(extraBufferCapacity = PLAY_STATE_FLOW_BUFFER)

    val playStateFlow: SharedFlow<PlayState> = _playStateFlow

    protected var mCurrentPlayerState = PLAYER_NORMAL

    protected var mEnableAudioFocus = true

    private var mAudioFocusHelper: PlayerAudioFocus? = null

    private val audioFocusTarget = object : AudioFocusTarget {
        override fun isPlaybackPlaying(): Boolean = isPlaying

        override fun isPlaybackMuted(): Boolean = false

        override fun startPlayback() {
            FloatLog.append("app", null, "audioFocus startPlayback")
            start()
        }

        override fun pausePlayback() {
            FloatLog.append("app", null, "audioFocus pausePlayback")
            pause()
        }

        override fun setPlaybackVolume(volume: Float) {
            mMediaPlayer?.setVolume(volume, volume)
        }
    }

    private var lastContainerW = 0
    private var lastContainerH = 0

    init {
        mPlayerContainer.setBackgroundColor(Color.BLACK)
        mPlayerContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != lastContainerW || h != lastContainerH)) {
                lastContainerW = w
                lastContainerH = h
                FloatLog.append("app", "MPV_SIZE", "video layout new=" + w + "x" + h)
                reapplyRenderTransform(w, h)
            }
        }
        addView(
            mPlayerContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        mEnableAudioFocus = true
    }

    private fun reapplyRenderTransform(w: Int, h: Int) {
        val render = mRenderView ?: return
        if (mVideoSize[0] <= 0 || mVideoSize[1] <= 0) return
        render.setScaleType(mCurrentScreenScaleType)
        render.setVideoSize(mVideoSize[0], mVideoSize[1])
        LOG.i("echo-render-resize reapply container=" + w + "x" + h + " video=" + mVideoSize[0] + "x" + mVideoSize[1])
    }

    open fun start() {
        if (isInIdleState() || isInStartAbortState()) {
            startPlay()
            return
        }
        startInPlaybackState()
    }

    protected open fun startPlay(): Boolean {
        if (showNetWarning()) {
            mMediaPlayer?.abortStart()
            dispatchPlayState(PlayState.START_ABORT)
            return false
        }
        if (mEnableAudioFocus) {
            ensureAudioFocusHelper()
            mAudioFocusHelper?.onNewPlayback()
        }
        mMediaPlayer?.release()
        mMediaPlayer = null
        initPlayer()
        addDisplay()
        val sink = mProgressSink
        val key = progressKey()
        if (sink == null) {
            applyStartPosition(key, null)
            startPrepare(false)
            return true
        }
        val seq = ++startPlaySeq
        scope.launch {
            val saved = sink.getSavedProgress(key)
            if (seq != startPlaySeq) return@launch
            applyStartPosition(key, saved)
            startPrepare(false)
        }
        return true
    }

    private fun applyStartPosition(key: String?, saved: Long?) {
        if (saved != null) mCurrentPosition = saved
        if (ZeroStartGate.consume(mZeroStartKeys, key)) {
            mCurrentPosition = 0
            LOG.i("echo-zero-start apply key=" + key)
        }
    }

    protected open fun showNetWarning(): Boolean = false

    protected open fun initPlayer() {
        val player = createPlayer()
        player.setPlayerEventListener(kernelEventListener)
        mMediaPlayer = player
        setInitOptions()
        player.initPlayer()
        setOptions()
    }

    protected open fun createPlayer(): KernelPlayer {
        val type = if (mRequestedPlayerType >= 0) {
            mRequestedPlayerType
        } else {
            com.github.tvbox.osc.util.KV.get(com.github.tvbox.osc.util.HawkConfig.PLAY_TYPE, 2)
        }
        val requestedMpv = type == PlayerHelper.TYPE_MPV && PlayerHelper.getPlayerExist(PlayerHelper.TYPE_MPV)
        val preferMpv = VesperEnginePolicy.resolveMpv(mUrl, requestedMpv)
        return FongmiKernelPlayer(context, preferMpv)
    }

    private var mRequestedPlayerType = -1

    /** 由 PlayerHelper.updateCfg 按本片配置下发的期望内核 type（<0 表示未下发，用全局设置）。 */
    fun setRequestedPlayerType(type: Int) {
        mRequestedPlayerType = type
    }

    protected open fun setInitOptions() = Unit

    protected open fun setOptions() {
        mMediaPlayer?.setLooping(false)
        mMediaPlayer?.setVolume(1.0f, 1.0f)
    }

    open fun prewarmKernel() {
        if (mMediaPlayer != null) return
        ensureAudioFocusHelper()
        initPlayer()
        addDisplay()
    }

    private fun ensureAudioFocusHelper() {
        if (mEnableAudioFocus && mAudioFocusHelper == null) {
            mAudioFocusHelper = PlayerAudioFocus(context, audioFocusTarget)
        }
    }

    /** Exo 引擎自己管焦点时壳子不再抢（否则挤掉引擎焦点致 reason=2 暂停）；MPV 等仍走壳子请求。 */
    private fun shouldRequestAudioFocus(): Boolean {
        if (isMute()) return false
        val kernel = mMediaPlayer as? FongmiKernelPlayer ?: return true
        return !kernel.isFocusManagedByEngine()
    }

    protected open fun addDisplay() {
        mRenderView?.let { render ->
            mPlayerContainer.removeView(render.getView())
            mMediaPlayer?.detachVideoSurface()
            render.release()
        }
        val render = mRenderViewFactory.createRenderView(context)
        mMediaPlayer?.let { render.attachToPlayer(it) }
        mRenderView = render
        mPlayerContainer.addView(
            render.getView(),
            0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
    }

    protected fun startPrepare(reset: Boolean) {
        startPrepare(reset, false)
    }

    protected fun startPrepare(reset: Boolean, rebindRenderView: Boolean) {
        if (reset) {
            mMediaPlayer?.reset()
            setOptions()
            if (rebindRenderView) {
                mMediaPlayer?.let { player -> mRenderView?.attachToPlayer(player) }
            }
        }
        if (prepareDataSource()) {
            mMediaPlayer?.let { player ->
                player.setStartPosition(mCurrentPosition)
                FloatLog.append("app", null, "appPlayerView prepareAsync")
                player.prepareAsync()
            }
            reportPlayState()
            setPlayerState(PLAYER_NORMAL)
        }
    }

    protected open fun prepareDataSource(): Boolean {
        val url = mUrl
        if (!url.isNullOrEmpty()) {
            mMediaPlayer?.setDataSource(url, mHeaders)
            return true
        }
        LOG.e("echo-manager datasource empty: no url, setDataSource skipped, no kernel started")
        return false
    }

    protected fun startInPlaybackState() {
        if (mMediaPlayer?.start() != true) return
        reportPlayState()
        if (shouldRequestAudioFocus()) {
            mAudioFocusHelper?.requestFocus()
        }
        mPlayerContainer.keepScreenOn = true
    }

    open fun pause() {
        FloatLog.append("app", null, "pause appPlayerView")
        if (mMediaPlayer?.pause() != true) return
        reportPlayState()
        if (!isMute()) {
            mAudioFocusHelper?.abandonFocus()
        }
        mPlayerContainer.keepScreenOn = false
    }

    /** 壳子生命周期暂停：与 pause() 完全一样，只是不清用户想播意图。 */
    open fun pauseForShell() {
        FloatLog.append("app", null, "pause appPlayerView pauseForShell")
        if (mMediaPlayer?.pauseForShell() != true) return
        reportPlayState()
        if (!isMute()) {
            mAudioFocusHelper?.abandonFocus()
        }
        mPlayerContainer.keepScreenOn = false
    }

    /** 用户主动暂停：与 pause() 完全一样，只是记用户暂停旗，兜底看门狗不再顶掉。 */
    open fun pauseFromUser() {
        FloatLog.append("app", null, "pause appPlayerView pauseFromUser")
        if (mMediaPlayer?.pauseFromUser() != true) return
        reportPlayState()
        if (!isMute()) {
            mAudioFocusHelper?.abandonFocus()
        }
        mPlayerContainer.keepScreenOn = false
    }

    open fun resume() {
        FloatLog.append("app", null, "appPlayerView resume")
        if (isInPlaybackState() && mMediaPlayer?.isPlaying == false) {
            resumePlay()
        }
    }

    private fun resumePlay() {
        if (mMediaPlayer?.start() != true) return
        reportPlayState()
        if (shouldRequestAudioFocus()) {
            mAudioFocusHelper?.requestFocus()
        }
        mPlayerContainer.keepScreenOn = true
    }

    open fun stopPlaybackKeepPlayer() {
        if (mMediaPlayer?.stop() != true) return
        reportPlayState()
    }

    open fun saveCurrentProgress() {
        saveProgress()
    }

    open fun release() {
        val hadActiveState = mLastReportedPlayState != PlayState.IDLE
        if (hadActiveState) captureLivePosition()
        mAudioFocusHelper?.abandonFocus()
        mAudioFocusHelper = null
        mMediaPlayer?.release()
        mMediaPlayer = null
        if (hadActiveState) {
            mRenderView?.let { render ->
                mPlayerContainer.removeView(render.getView())
                render.release()
            }
            mRenderView = null
            mPlayerContainer.keepScreenOn = false
            saveProgress()
            mCurrentPosition = 0
            reportPlayState()
        }
        mVideoSize[0] = 0
        mVideoSize[1] = 0
    }

    protected fun saveProgress() {
        val sink = mProgressSink ?: return
        captureLivePosition()
        if (mCurrentPosition > 0) {
            LOG.d("AppPlayerView", "saveProgress: " + mCurrentPosition)
            sink.saveProgress(progressKey(), mCurrentPosition)
        }
    }

    private fun captureLivePosition() {
        if (!isInPlaybackState()) return
        val live = mMediaPlayer?.currentPosition ?: 0L
        if (live > 0) mCurrentPosition = live
    }

    open fun resumePositionForReplay(fallback: Long): Long {
        if (isInPlaybackState()) {
            val live = mMediaPlayer?.currentPosition ?: 0L
            if (live > 0) return live
        }
        if (mCurrentPosition > 0) return mCurrentPosition
        return fallback
    }

    protected fun progressKey(): String? = mProgressKey ?: mUrl

    protected fun isInPlaybackState(): Boolean =
        mMediaPlayer?.playState?.isInPlaybackState == true

    protected fun isInIdleState(): Boolean =
        (mMediaPlayer?.playState ?: PlayState.IDLE) == PlayState.IDLE

    private fun isInStartAbortState(): Boolean = mMediaPlayer?.playState == PlayState.START_ABORT

    private val kernelEventListener = object : KernelPlayer.Listener {

        override fun onPrepared() {
            val player = mMediaPlayer ?: return
            if (mCurrentPosition > 0 && !player.isStartPositionApplied()) {
                player.seekTo(mCurrentPosition)
            }
            dispatchPlayState(PlayState.PREPARED)
            if (shouldRequestAudioFocus()) {
                mAudioFocusHelper?.requestFocus()
            }
        }

        override fun onKernelPlayStateChanged() {
            reportPlayState()
        }

        override fun onInfo(what: Int, extra: Int) {
            when (what) {
                KernelPlayer.MEDIA_INFO_BUFFERING_START -> reportPlayState()

                KernelPlayer.MEDIA_INFO_BUFFERING_END -> reportPlayState()

                KernelPlayer.MEDIA_INFO_RENDERING_START -> {
                    reportPlayState()
                    if (mMediaPlayer?.playState == PlayState.PLAYING) {
                        mPlayerContainer.keepScreenOn = true
                    }
                }

                KernelPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED ->
                    mRenderView?.setVideoRotation(extra)
            }
        }

        override fun onError() {
            mPlayerContainer.keepScreenOn = false
            reportPlayState()
        }

        override fun onCompletion() {
            mPlayerContainer.keepScreenOn = false
            mCurrentPosition = 0
            mProgressSink?.saveProgress(progressKey(), 0L)
            reportPlayState()
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            mVideoSize[0] = width
            mVideoSize[1] = height
            onVideoSizeReported(width, height)
            mVideoController?.onVideoSizeChanged(width, height)
            mRenderView?.let { render ->
                render.setScaleType(mCurrentScreenScaleType)
                render.setVideoSize(width, height)
            }
        }
    }

    protected open fun onVideoSizeReported(width: Int, height: Int) = Unit

    @get:JvmName("getMediaPlayer")
    val mediaPlayer: KernelPlayer?
        get() = mMediaPlayer

    open val duration: Long
        get() = if (isInPlaybackState()) mMediaPlayer?.duration ?: 0L else 0L

    open val currentPosition: Long
        get() {
            if (isInPlaybackState()) {
                mCurrentPosition = mMediaPlayer?.currentPosition ?: 0L
                return mCurrentPosition
            }
            return 0
        }

    open fun seekTo(pos: Long) {
        if (isInPlaybackState()) {
            mMediaPlayer?.seekTo(pos)
        }
    }

    open fun selectTrack(track: TrackInfoBean) {
        mMediaPlayer?.setTrack(track)
        reportPlayState()
    }

    open val isPlaying: Boolean
        get() = isInPlaybackState() && mMediaPlayer?.isPlaying == true

    val bufferedPercentage: Int
        get() = mMediaPlayer?.bufferedPercentage ?: 0

    open val tcpSpeed: Long
        get() = mMediaPlayer?.tcpSpeed ?: 0L

    open fun setSpeed(speed: Float) {
        if (isInPlaybackState()) mMediaPlayer?.setSpeed(speed)
    }

    open val speed: Float
        get() = if (isInPlaybackState()) mMediaPlayer?.speed ?: 1f else 1f

    open fun isMute(): Boolean = false

    open fun setUrl(url: String) {
        setUrl(url, null)
    }

    open fun setUrl(url: String, headers: Map<String, String>?) {
        mUrl = url
        mHeaders = headers
        mVideoSize[0] = 0
        mVideoSize[1] = 0
        mVideoController?.onVideoSizeCleared()
    }

    /** 当前已下发给播放器的地址，用于判断播放器是否已完成内容切换。 */
    val currentUrl: String?
        get() = mUrl

    /** 当前播放请求里的 User-Agent（源没给则为 null，播放器不补发）。 */
    val currentUserAgent: String?
        get() = mHeaders?.entries?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value

    /** r489：当前播放请求里的 Referer / Cookie（推送信息 OSD 用，与 UA 同源）。 */
    val currentReferer: String?
        get() = mHeaders?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value?.trim()

    val currentCookie: String?
        get() = mHeaders?.entries?.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value?.trim()

    open fun forgetVideoSize() {
        mVideoSize[0] = 0
        mVideoSize[1] = 0
    }

    open fun setProgressKey(key: String?) {
        mProgressKey = key
    }

    fun markZeroStartKeys(keys: List<String>) {
        if (ZeroStartGate.mark(mZeroStartKeys, keys)) {
            LOG.i("echo-zero-start mark n=" + keys.size + " pending=" + mZeroStartKeys.size)
        }
    }

    open fun setProgressSink(sink: ProgressSink?) {
        mProgressSink = sink
    }

    open fun skipPositionWhenPlay(position: Int) {
        mCurrentPosition = position.toLong()
    }

    private var kernelSwitchResumeArmed = false
    private var kernelSwitchResumePlaying = false
    private var kernelSwitchResumePositionMs = 0L

    /** 切内核前调用：记下播没播、播到哪；新内核状态一变就按原样恢复一次，用完撤旗。 */
    fun armKernelSwitchResume(wasPlaying: Boolean, positionMs: Long) {
        kernelSwitchResumeArmed = true
        kernelSwitchResumePlaying = wasPlaying
        kernelSwitchResumePositionMs = positionMs
    }

    open fun replay(resetPosition: Boolean) {
        val zeroStart = ZeroStartGate.consume(mZeroStartKeys, progressKey())
        if (zeroStart) {
            LOG.i("echo-zero-start apply key=" + progressKey())
        }
        if (resetPosition || zeroStart) {
            mCurrentPosition = 0
        }
        val player = mMediaPlayer
        if (player == null) {
            LOG.i("replay() called without kernel, fallback to start()")
            start()
            return
        }
        if (mEnableAudioFocus) {
            mAudioFocusHelper?.onNewPlayback()
        }
        player.resetTrackSelection()
        if (player.keepRenderViewOnReset()) {
            player.reset()
            setOptions()
            player.setOptions()
            startPrepare(false)
        } else {
            startPrepare(true, true)
        }
    }

    open fun factoryRenderType(): Int =
        if (mRenderViewFactory is EngineTextureRenderViewFactory) 0 else 1

    open fun setRenderViewFactory(factory: PlayerRenderViewFactory) {
        mRenderViewFactory = factory
    }

    open fun needsRenderRebuild(targetRenderType: Int): Boolean {
        val render = mRenderView ?: return false
        return (targetRenderType == 1) != (render.getView() is SurfaceView)
    }

    open fun setScreenScaleType(screenScaleType: Int) {
        mCurrentScreenScaleType = screenScaleType
        mRenderView?.setScaleType(screenScaleType)
    }

    open val videoSize: IntArray
        get() = mVideoSize

    fun getCurrentPlayerState(): Int = mCurrentPlayerState

    @Suppress("UNUSED_PARAMETER")
    open fun setMute(isMute: Boolean) = Unit

    private fun dispatchPlayState(playState: PlayState) {
        mLastReportedPlayState = playState
        mVideoController?.setPlayState(playState)
        if (!_playStateFlow.tryEmit(playState)) {
            LOG.e("echo-player playState-flow-drop: " + playState)
        }
        applyKernelSwitchResume(playState)
    }

    private fun applyKernelSwitchResume(playState: PlayState) {
        if (!kernelSwitchResumeArmed) return
        if (playState == PlayState.ERROR || playState == PlayState.IDLE || playState == PlayState.COMPLETED) {
            kernelSwitchResumeArmed = false
            return
        }
        if (playState == PlayState.PLAYING) {
            kernelSwitchResumeArmed = false
            if (kernelSwitchResumePositionMs > 0) {
                val cur = mMediaPlayer?.currentPosition ?: 0L
                if (cur > kernelSwitchResumePositionMs + 1000 || cur < kernelSwitchResumePositionMs - 1000) {
                    seekTo(kernelSwitchResumePositionMs)
                }
            }
            return
        }
        if (playState != PlayState.PREPARED && playState != PlayState.BUFFERED && playState != PlayState.PAUSED) return
        kernelSwitchResumeArmed = false
        if (!kernelSwitchResumePlaying) return
        if (kernelSwitchResumePositionMs > 0) {
            val cur = mMediaPlayer?.currentPosition ?: 0L
            if (cur > kernelSwitchResumePositionMs + 1000 || cur < kernelSwitchResumePositionMs - 1000) {
                seekTo(kernelSwitchResumePositionMs)
            }
        }
        startInPlaybackState()
    }

    private fun reportPlayState() {
        val state = mMediaPlayer?.playState ?: PlayState.IDLE
        if (state == mLastReportedPlayState) return
        dispatchPlayState(state)
    }

    protected fun setPlayerState(playerState: Int) {
        mCurrentPlayerState = playerState
        mVideoController?.setPlayerState(playerState)
    }

    interface VideoControllerHost {
        fun setPlayState(playState: PlayState)

        fun setPlayerState(playerState: Int)

        fun onVideoSizeChanged(width: Int, height: Int)

        fun onVideoSizeCleared()

        fun startProgress()

        /**
         * 控制器挂载到播放器容器上的 View。
         *
         * 默认返回控制器自身（直播等仍以 View 实现的控制器无需覆写）；
         * 纯 Compose 控制器本身不是 View，应覆写此方法返回其 ComposeView 宿主。
         */
        fun controllerView(): View? = this as? View
    }

    protected var mVideoController: VideoControllerHost? = null

    open val videoController: VideoControllerHost?
        get() = mVideoController

    open fun setVideoController(controller: VideoControllerHost?) {
        val old = mVideoController
        old?.controllerView()?.let { mPlayerContainer.removeView(it) }
        mVideoController = controller
        if (controller != null) {
            controller.controllerView()?.let { attach ->
                mPlayerContainer.addView(
                    attach,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
            }
            val state = mMediaPlayer?.playState ?: PlayState.IDLE
            controller.setPlayState(state)
            controller.setPlayerState(mCurrentPlayerState)
            if (state != PlayState.IDLE && state != PlayState.ERROR) {
                controller.startProgress()
            }
        }
    }

    open fun releaseController(): VideoControllerHost? {
        val old = mVideoController
        setVideoController(null)
        return old
    }

    open fun isFullScreen(): Boolean = false

    open fun togglePlay() {
        if (isPlaying) pauseFromUser() else start()
    }

    open fun doScreenShot(): Bitmap? = mRenderView?.doScreenShot()

    open fun onBackPressed(): Boolean = false

    open fun attachContainerTo(host: ViewGroup?) {
        if (host == null) return
        val parent = mPlayerContainer.parent as? ViewGroup
        if (parent === host) return
        mMediaPlayer?.detachVideoSurface()
        parent?.removeView(mPlayerContainer)
        val lp = mPlayerContainer.layoutParams ?: LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        host.addView(mPlayerContainer, 0, lp)
    }

    open fun detachContainerFromHost() {
        val parent = mPlayerContainer.parent as? ViewGroup
        if (parent == null || parent === this) return
        mMediaPlayer?.detachVideoSurface()
        parent.removeView(mPlayerContainer)
        addView(
            mPlayerContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    open fun isContainerAttachedTo(host: ViewGroup?): Boolean =
        host != null && mPlayerContainer.parent === host

    open fun playerContainer(): FrameLayout = mPlayerContainer

    open fun bringControllerToFront() {
        mVideoController?.controllerView()?.bringToFront()
    }

    fun hostActivity(): Activity? = PlayerUtils.scanForActivity(context)

    open val renderIsSurface: Boolean
        get() = mRenderView?.getView() is SurfaceView

    protected fun renderView(): PlayerRenderView? = mRenderView

    protected fun renderViewFactory(): PlayerRenderViewFactory = mRenderViewFactory

    override fun onSaveInstanceState(): Parcelable? {
        saveProgress()
        return super.onSaveInstanceState()
    }

    companion object {

        const val SCREEN_SCALE_DEFAULT = 0
        const val SCREEN_SCALE_16_9 = 1
        const val SCREEN_SCALE_4_3 = 2
        const val SCREEN_SCALE_MATCH_PARENT = 3
        const val SCREEN_SCALE_ORIGINAL = 4
        const val SCREEN_SCALE_CENTER_CROP = 5

        const val PLAYER_NORMAL = 10
        const val PLAYER_FULL_SCREEN = 11
        const val PLAYER_TINY_SCREEN = 12

        private const val PLAY_STATE_FLOW_BUFFER = 8
    }
}
