package com.fongmi.android.tv.setting;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;

// AVBox 适配垫片：AVBox 没有独立的预加载设置体系，预加载一律关闭，
// 其余数值取不影响内存缓存决策的默认值；常量与 webhtv 保持一致。
public final class PreloadSetting {

    public static final int WHOLE_MEDIA_AHEAD_SECONDS = 0;

    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 4;

    public static final int PAUSE_PRELOAD_LEGACY_OFF = 0;
    public static final int PAUSE_PRELOAD_WIFI = 1;
    public static final int PAUSE_PRELOAD_ALWAYS = 2;
    public static final int DEFAULT_PAUSE_PRELOAD = PAUSE_PRELOAD_ALWAYS;

    public static final int MIN_SIZE_MB = 128;
    public static final int MAX_SIZE_MB = 4096;
    public static final int STEP_SIZE_MB = 128;
    public static final int MIN_TIME_SECONDS = 20;
    public static final int MAX_TIME_SECONDS = 120;
    public static final int STEP_TIME_SECONDS = 10;

    private PreloadSetting() {
    }

    public static boolean isPreload() {
        return false;
    }

    public static boolean isPreload(int kernel) {
        return false;
    }

    public static int getPausePreloadPolicy() {
        return DEFAULT_PAUSE_PRELOAD;
    }

    public static int getPausePreloadPolicy(int kernel) {
        return DEFAULT_PAUSE_PRELOAD;
    }

    public static int getPreloadThreads(int kernel) {
        return 1;
    }

    public static long getPreloadSizeBytes(int kernel) {
        return 0;
    }

    public static int getPreloadAheadSeconds(int kernel) {
        return 0;
    }

    public static int getPreloadTimeSeconds(int kernel) {
        return 0;
    }

    public static boolean isEnabled() {
        return false;
    }

    public static void putEnabled(boolean preload) {
    }

    public static boolean isNextEpisodeEnabled() {
        return KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false);
    }

    public static void putNextEpisodeEnabled(boolean enabled) {
        KV.put(HawkConfig.PRELOAD_NEXT_EPISODE, enabled);
    }

    public static int getThreads() {
        return 1;
    }

    public static void putThreads(int threads) {
    }

    public static int getSizeMb() {
        return 128;
    }

    public static void putSizeMb(int size) {
    }

    public static long getSizeBytes() {
        return 128L * 1024L * 1024L;
    }

    public static int getTimeSeconds() {
        return 120;
    }

    public static void putTimeSeconds(int seconds) {
    }

    public static long getDurationMs() {
        return 120000L;
    }
}
