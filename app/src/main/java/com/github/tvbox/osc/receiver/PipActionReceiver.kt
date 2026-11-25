package com.github.tvbox.osc.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PiP 窗内 RemoteAction 的动作面（对标 webhtv 的 event/ActionEvent +
 * receiver/ActionReceiver）。webhtv 的接收器把动作转投其 PlaybackService；
 * AVBox 的播放器活在播放页 Activity 里，等价改为进程内会话桥 [PipSession]：
 * 当前 PiP 所属页面注册处理器，动作直达页面（播放/暂停/下一集）。
 */
object PipActions {
    const val SUFFIX_PLAY = ".pip.play"
    const val SUFFIX_PAUSE = ".pip.pause"
    const val SUFFIX_NEXT = ".pip.next"

    const val PLAY = "play"
    const val PAUSE = "pause"
    const val NEXT = "next"
}

object PipSession {

    @Volatile
    var handler: ((String) -> Unit)? = null

    fun dispatch(action: String) {
        handler?.invoke(action)
    }
}

class PipActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pkg = context.packageName
        when (action) {
            pkg + PipActions.SUFFIX_PLAY -> PipSession.dispatch(PipActions.PLAY)
            pkg + PipActions.SUFFIX_PAUSE -> PipSession.dispatch(PipActions.PAUSE)
            pkg + PipActions.SUFFIX_NEXT -> PipSession.dispatch(PipActions.NEXT)
        }
    }

    companion object {
        fun getPendingIntent(context: Context, action: String): PendingIntent {
            val suffix = when (action) {
                PipActions.PLAY -> PipActions.SUFFIX_PLAY
                PipActions.PAUSE -> PipActions.SUFFIX_PAUSE
                else -> PipActions.SUFFIX_NEXT
            }
            val intent = Intent(context.packageName + suffix).setPackage(context.packageName)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
