package com.github.tvbox.osc.player

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.github.tvbox.osc.player.state.PlayState
import kotlinx.coroutines.flow.StateFlow

abstract class KernelPlayer {

    interface Listener {
        fun onError()
        fun onCompletion()
        fun onInfo(what: Int, extra: Int)
        fun onPrepared()
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onKernelPlayStateChanged() {}
    }

    @JvmField
    protected var mPlayerEventListener: Listener? = null

    @Volatile
    private var mStartPosition: Long = 0

    @Volatile
    private var startPositionApplied: Boolean = false

    abstract fun initPlayer()

    abstract fun setDataSource(path: String, headers: Map<String, String>?)

    abstract fun start(): Boolean

    abstract fun pause(): Boolean

    /** 壳子生命周期暂停：默认等于 pause()；Fongmi 内核重写为不清用户想播意图。 */
    open fun pauseForShell(): Boolean = pause()

    /** 用户主动暂停（播放钮/耳机线控/画中画暂停/投屏接管）：默认等于 pause()；Fongmi 内核重写为记用户暂停旗，兜底看门狗不再顶掉。 */
    open fun pauseFromUser(): Boolean = pause()

    abstract fun stop(): Boolean

    open fun abortStart() {}

    abstract fun prepareAsync()

    fun setStartPosition(position: Long) {
        mStartPosition = maxOf(0L, position)
        startPositionApplied = false
    }

    protected val startPosition: Long
        get() = mStartPosition

    protected fun markStartPositionApplied() {
        startPositionApplied = true
    }

    fun isStartPositionApplied(): Boolean = startPositionApplied

    abstract fun reset()

    open fun keepRenderViewOnReset(): Boolean = false

    open fun resetTrackSelection() {}

    open fun getTrackInfo(): TrackInfo = TrackInfo()

    open fun setTrack(track: TrackInfoBean?) {}

    abstract val isPlaying: Boolean

    abstract val playState: PlayState

    abstract val stateFlow: StateFlow<PlayState>

    abstract fun seekTo(time: Long)

    abstract fun release()

    abstract val currentPosition: Long

    abstract val duration: Long

    abstract val bufferedPercentage: Int

    abstract fun setSurface(surface: Surface?)

    abstract fun setDisplay(holder: SurfaceHolder?)

    /**
     * 把渲染用的 SurfaceView 本体交给内核，对应 media3 PlayerView 在 setPlayer 时
     * 调 setVideoSurfaceView 的契约。默认空实现、其余内核不受影响；只有 MPV 适配层
     * 需要覆写——其核心的开播 surface 门只认 SurfaceView 本体。传 null 表示解绑。
     */
    open fun bindSurfaceView(view: SurfaceView?) = Unit

    open fun clearDisplay() {
        setSurface(null)
    }

    abstract fun detachVideoSurface()

    abstract fun setVolume(leftVolume: Float, rightVolume: Float)

    abstract fun setLooping(isLooping: Boolean)

    abstract fun setOptions()

    abstract fun setSpeed(speed: Float)

    abstract val speed: Float

    abstract val tcpSpeed: Long

    fun setPlayerEventListener(listener: Listener?) {
        mPlayerEventListener = listener
    }

    companion object {

        const val MEDIA_INFO_RENDERING_START = 3

        const val MEDIA_INFO_BUFFERING_START = 701

        const val MEDIA_INFO_BUFFERING_END = 702

        const val MEDIA_INFO_VIDEO_ROTATION_CHANGED = 10001
    }
}
