package com.fongmi.android.tv.setting;

// AVBox 适配垫片：帧率匹配取 webhtv 默认值（仅无缝），常量与原版一致。
public final class MpvPerformanceSetting {

    public static final int FRAME_RATE_OFF = 0;
    public static final int FRAME_RATE_SEAMLESS = 1;

    private MpvPerformanceSetting() {
    }

    public static int getFrameRateMode() {
        return FRAME_RATE_SEAMLESS;
    }

    public static String getFrameRateText() {
        return "仅无缝";
    }
}
