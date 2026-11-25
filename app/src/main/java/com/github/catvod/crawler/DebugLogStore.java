package com.github.catvod.crawler;

import com.github.catvod.crawler.diagnostics.DiagnosticEvent;
import com.github.tvbox.osc.util.FloatLog;

// AVBox 适配垫片：webhtv 里 DebugLogStore 是悬浮日志的存储。
// 这里把本地源引擎的日志一路转发 logcat（与原 LOG.d 等价的输出），
// 一路送进 AVBox 的悬浮日志缓冲（FloatLog，来源标签 [spider]），保持引擎代码不变。
// 诊断相关接口为 mpvplayer 包的编译适配：AVBox 未移植诊断采集，isEnabled 恒 false，
// 事件入口全部为空操作，mpvplayer 的诊断路径因此整体静默、不影响播放。
public class DebugLogStore {

    public static void add(String tag, String msg) {
        try {
            android.util.Log.d("TVBox-runtime", tag + ": " + msg);
        } catch (Throwable ignored) {
        }
        try {
            FloatLog.append("spider", tag, msg);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isEnabled() {
        return false;
    }

    public static int categories() {
        return 0;
    }

    public static long captureGeneration() {
        return 0;
    }

    public static boolean categoryEnabled(String category) {
        return false;
    }

    public static boolean acceptsEvent(String name) {
        return false;
    }

    public static void event(DiagnosticEvent event) {
    }

    public static void collectorFailure() {
    }

    public static void collectorHealth(String id, DiagnosticEvent event, boolean failed, long capture) {
    }
}
