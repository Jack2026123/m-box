package com.github.catvod.crawler

import com.github.tvbox.osc.util.FloatLog

class SpiderDebug {

    companion object {

        @JvmStatic
        fun log(th: Throwable?) {
            val error = th ?: return
            try {
                android.util.Log.d("SpiderLog", "" + error.message, error)
            } catch (th1: Throwable) {
            }
            try {
                FloatLog.append("spider", "SpiderLog", android.util.Log.getStackTraceString(error))
            } catch (th1: Throwable) {
            }
        }

        @JvmStatic
        fun log(msg: String?) {
            try {
                android.util.Log.d("SpiderLog", "" + msg)
            } catch (th1: Throwable) {
            }
            try {
                FloatLog.append("spider", "SpiderLog", msg)
            } catch (th1: Throwable) {
            }
        }

        @JvmStatic
        fun ec(i: Int): String {
            return ""
        }

        // 分发层调用日志（AVBox 壳层收口补打，Vesper/FongMi 壳在分发层有同款）：
        // 仅悬浮日志开启时才打，关闭时除一次开关判定外零开销、零行为变化；
        // 头一行 + 原始结果整段各一条，全程自吞异常，绝不影响调用本身与返回值。
        @JvmStatic
        fun logDispatch(header: String) {
            try {
                if (isEnabled()) log(header)
            } catch (th: Throwable) {
            }
        }

        @JvmStatic
        fun logDispatchResult(result: String?) {
            try {
                if (isEnabled()) log(result ?: "")
            } catch (th: Throwable) {
            }
        }

        // MPV 移植适配：mpvplayer 包按 webhtv 的 SpiderDebug 接口调用以下两个方法。
        // isEnabled 与悬浮日志开关同源：开启后 mpvplayer 的调试日志才会组装并送进缓冲；
        // log 除转发 logcat 外，同步送进悬浮日志缓冲（mpv* 标签归 [player]，其余归 [spider]）。
        @JvmStatic
        fun isEnabled(): Boolean {
            return try {
                FloatLog.isEnabled()
            } catch (th: Throwable) {
                false
            }
        }

        @JvmStatic
        fun log(tag: String?, format: String?, vararg args: Any?) {
            val text = try {
                if (format == null) "" else if (args.isEmpty()) format else String.format(format, *args)
            } catch (th: Throwable) {
                format ?: ""
            }
            try {
                android.util.Log.d(tag ?: "SpiderLog", text)
            } catch (th: Throwable) {
            }
            try {
                val source = if (tag != null && tag.startsWith("mpv")) "player" else "spider"
                FloatLog.append(source, tag, text)
            } catch (th: Throwable) {
            }
        }
    }
}
