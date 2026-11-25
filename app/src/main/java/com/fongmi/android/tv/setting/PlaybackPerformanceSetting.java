package com.fongmi.android.tv.setting;

// AVBox 适配垫片：性能目录的自动判定在 AVBox 未移植，一律非自动。
public final class PlaybackPerformanceSetting {

    private PlaybackPerformanceSetting() {
    }

    public static boolean isAuto(int kernel, String key) {
        return false;
    }
}
