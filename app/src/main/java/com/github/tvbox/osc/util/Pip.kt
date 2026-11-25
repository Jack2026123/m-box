package com.github.tvbox.osc.util

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.View
import com.github.tvbox.osc.receiver.PipActionReceiver
import com.github.tvbox.osc.receiver.PipActions

/**
 * 画中画（对标 webhtv mobile 的 utils/PiP.java 移植）：
 * 壳子封装的系统 PiP —— PictureInPictureParams + RemoteAction 播放/暂停/下一集
 * + sourceRectHint + 宽高比钳制，Android 12+ 用 setAutoEnterEnabled 实现
 * Home 离开自动进小窗。AVBox 无 webhtv 的后台播放模式设置，门控等价改为
 * 调用方判断「有视频内容且非纯音频」后才调 enter/update（见各播放页）。
 * webhtv 的 AUDIO 动作（切后台音频模式）AVBox 无对应形态，不移植；
 * 动作列为 [播放/暂停, 下一集（可选）]。
 */
class Pip {

    private var builder: PictureInPictureParams.Builder? = null

    init {
        if (!noPip()) builder = PictureInPictureParams.Builder()
    }

    private fun buildRemoteAction(
        activity: Activity,
        icon: Int,
        title: Int,
        action: String,
    ): RemoteAction = RemoteAction(
        Icon.createWithResource(activity, icon),
        activity.getString(title),
        "",
        PipActionReceiver.getPendingIntent(activity, action),
    )

    private fun playPauseAction(activity: Activity, playing: Boolean): RemoteAction =
        if (playing) {
            buildRemoteAction(
                activity,
                androidx.media3.ui.R.drawable.exo_icon_pause,
                androidx.media3.ui.R.string.exo_controls_pause_description,
                PipActions.PAUSE,
            )
        } else {
            buildRemoteAction(
                activity,
                androidx.media3.ui.R.drawable.exo_icon_play,
                androidx.media3.ui.R.string.exo_controls_play_description,
                PipActions.PLAY,
            )
        }

    /** sourceRectHint：视频 View 的全局可见区（对标 webhtv update(Activity, View)）。 */
    fun update(activity: Activity, view: View) {
        try {
            if (noPip()) return
            val b = builder ?: return
            val rect = Rect()
            view.getGlobalVisibleRect(rect)
            b.setSourceRectHint(rect)
            setAutoEnter()
            activity.setPictureInPictureParams(b.build())
        } catch (e: Exception) {
            LOG.e(TAG, e)
        }
    }

    /** 宽高比更新（对标 webhtv update(Activity, int, int, int)）。 */
    fun update(activity: Activity, width: Int, height: Int, scale: Int) {
        try {
            if (noPip()) return
            val b = builder ?: return
            setAspectRatio(b, width, height, scale)
            setAutoEnter()
            activity.setPictureInPictureParams(b.build())
        } catch (e: Exception) {
            LOG.e(TAG, e)
        }
    }

    /** 播放态动作列更新（对标 webhtv update(Activity, boolean)，去掉 AUDIO 动作）。 */
    fun updateActions(activity: Activity, playing: Boolean, hasNext: Boolean) {
        try {
            if (noPip()) return
            val b = builder ?: return
            val actions = ArrayList<RemoteAction>()
            actions.add(playPauseAction(activity, playing))
            if (hasNext) {
                actions.add(
                    buildRemoteAction(
                        activity,
                        androidx.media3.ui.R.drawable.exo_icon_next,
                        androidx.media3.ui.R.string.exo_controls_next_description,
                        PipActions.NEXT,
                    ),
                )
            }
            setAutoEnter()
            activity.setPictureInPictureParams(b.setActions(actions).build())
        } catch (e: Exception) {
            LOG.e(TAG, e)
        }
    }

    fun enter(activity: Activity, width: Int, height: Int, scale: Int): Boolean {
        try {
            if (noPip() || activity.isInPictureInPictureMode) return false
            val b = builder ?: return false
            setAspectRatio(b, width, height, scale)
            setAutoEnter()
            return activity.enterPictureInPictureMode(b.build())
        } catch (e: Exception) {
            LOG.e(TAG, e)
            return false
        }
    }

    private fun setAutoEnter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder?.setAutoEnterEnabled(true)
        }
    }

    private fun setAspectRatio(b: PictureInPictureParams.Builder, width: Int, height: Int, scale: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setSeamlessResizeEnabled(true)
        when (scale) {
            SCALE_16_9 -> b.setAspectRatio(Rational(16, 9))
            SCALE_4_3 -> b.setAspectRatio(Rational(4, 3))
            else -> b.setAspectRatio(getRational(width, height))
        }
    }

    private fun getRational(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val limitWide = Rational(239, 100)
        val limitTall = Rational(100, 239)
        val rational = Rational(width, height)
        if (rational.isInfinite) return Rational(16, 9)
        if (rational.toFloat() > limitWide.toFloat()) return limitWide
        if (rational.toFloat() < limitTall.toFloat()) return limitTall
        return rational
    }

    companion object {
        private const val TAG = "Pip"

        // 与 AppPlayerView 的 SCREEN_SCALE_16_9 / SCREEN_SCALE_4_3 同值（webhtv getScale 同语义）
        private const val SCALE_16_9 = 1
        private const val SCALE_4_3 = 2

        fun noPip(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            val ctx = AppContextHolder.context() ?: return true
            return !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }
    }
}
