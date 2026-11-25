package com.fongmi.android.tv.setting;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;

// AVBox 适配垫片：只提供 mpvplayer 包引用的内核编号与开关，取 webhtv 的默认值。
// 注意 EXO/MPV 是 FongMi 内部内核编号（0/2），与 AVBox 的播放器 type 编号无关。
public final class PlayerSetting {

    public static final int EXO = 0;
    public static final int MPV = 2;

    public static final int ENGINE_EXO = 0;
    public static final int ENGINE_MPV = 1;
    public static final int RENDER_SURFACE = 0;
    public static final int RENDER_TEXTURE = 1;

    private static boolean preferredMpv = false;

    private PlayerSetting() {
    }

    /** 当前内核编号（0=EXO/2=MPV）：恒 EXO——音乐取地址链只消费该值。 */
    public static int getPlayer() {
        return EXO;
    }
    public static int sanitizePlayer(int player) {
        return player == MPV ? player : EXO;
    }

    public static long getPlayCacheSize() {
        return getPlayCacheSize(MPV);
    }

    public static long getPlayCacheSize(int kernel) {
        // webhtv 默认分支（play_cache 选项 0）：128MB，喂给核心的 HLS 代理磁盘缓存计量。
        return 128L * 1024 * 1024;
    }

    public static boolean isBlurayMenu() {
        return false;
    }

    public static boolean isCaption() {
        // webhtv 默认：Prefers "caption" 未设置时为 false，字幕样式走应用默认而非系统字幕样式。
        return false;
    }

    public static boolean isPreferAAC() {
        return false;
    }

    public static boolean isPreferAAC(int kernel) {
        return false;
    }

    public static void setPreferredMpv(boolean mpv) {
        preferredMpv = mpv;
    }

    public static int getEngine() {
        return preferredMpv ? ENGINE_MPV : ENGINE_EXO;
    }

    public static void putEngine(int engine) {
        preferredMpv = engine == ENGINE_MPV;
    }

    public static boolean isExo() {
        return !preferredMpv;
    }

    public static boolean isMpv() {
        return preferredMpv;
    }

    public static boolean isDebug() {
        return false;
    }

    public static boolean isLibass() {
        return false;
    }

    public static boolean isMpvGpuNext() {
        return false;
    }

    public static boolean isMpvVulkan() {
        return false;
    }

    public static int getBuffer() {
        return Math.clamp(KV.get(HawkConfig.BUFFER_TIMES, HawkConfig.BUFFER_TIMES_DEFAULT), 1, 10);
    }

    public static int getRender() {
        return KV.get(HawkConfig.PLAY_RENDER, 1) == 1 ? RENDER_SURFACE : RENDER_TEXTURE;
    }

    public static void putRender(int render) {
        KV.put(HawkConfig.PLAY_RENDER, render == RENDER_SURFACE ? 1 : 0);
    }
}
