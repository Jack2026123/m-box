package com.github.tvbox.osc.util;

import com.github.catvod.utils.Prefers;

public final class AdFilterSetting {

    public static final int MODE_CONSERVATIVE = 0;
    public static final int MODE_BALANCED = 1;
    public static final int MODE_AGGRESSIVE = 2;

    private static final String KEY_MODE = "remove_ad_mode";
    private static final String KEY_THRESHOLD = "remove_ad_threshold";
    private static final String KEY_SAMPLE = "remove_ad_sample_segments";
    private static final String KEY_LOW_RATIO = "remove_ad_low_ratio";
    private static final String KEY_COOLDOWN = "remove_ad_cooldown";
    private static final String KEY_WORKERS = "remove_ad_max_workers";

    private AdFilterSetting() {
    }

    public static int getMode() {
        return clamp(Prefers.getInt(KEY_MODE, MODE_BALANCED), MODE_CONSERVATIVE, MODE_AGGRESSIVE);
    }

    public static void putMode(int mode) {
        Prefers.put(KEY_MODE, clamp(mode, MODE_CONSERVATIVE, MODE_AGGRESSIVE));
    }

    public static float getThreshold() {
        return clampF(Prefers.getInt(KEY_THRESHOLD, 350) / 1000f, 0.05f, 1.5f);
    }

    public static void putThreshold(float value) {
        Prefers.put(KEY_THRESHOLD, (int) (clampF(value, 0.05f, 1.5f) * 1000));
    }

    public static int getSampleSegments() {
        return clamp(Prefers.getInt(KEY_SAMPLE, 3), 1, 10);
    }

    public static void putSampleSegments(int value) {
        Prefers.put(KEY_SAMPLE, clamp(value, 1, 10));
    }

    public static float getLowRatio() {
        return clampF(Prefers.getInt(KEY_LOW_RATIO, 650) / 1000f, 0.2f, 1.2f);
    }

    public static void putLowRatio(float value) {
        Prefers.put(KEY_LOW_RATIO, (int) (clampF(value, 0.2f, 1.2f) * 1000));
    }

    public static int getCooldown() {
        return clamp(Prefers.getInt(KEY_COOLDOWN, 120), 0, 600);
    }

    public static void putCooldown(int value) {
        Prefers.put(KEY_COOLDOWN, clamp(value, 0, 600));
    }

    public static int getMaxWorkers() {
        return clamp(Prefers.getInt(KEY_WORKERS, 8), 1, 24);
    }

    public static void putMaxWorkers(int value) {
        Prefers.put(KEY_WORKERS, clamp(value, 1, 24));
    }

    public static void applyPreset(int mode) {
        if (mode == MODE_CONSERVATIVE) {
            putThreshold(0.50f);
            putSampleSegments(5);
            putLowRatio(0.55f);
        } else if (mode == MODE_AGGRESSIVE) {
            putThreshold(0.25f);
            putSampleSegments(3);
            putLowRatio(0.70f);
        } else {
            putThreshold(0.35f);
            putSampleSegments(3);
            putLowRatio(0.65f);
        }
        putMode(mode);
    }

    public static void reset() {
        applyPreset(MODE_BALANCED);
        putCooldown(120);
        putMaxWorkers(8);
    }

    public static String modeName(int mode) {
        if (mode == MODE_CONSERVATIVE) return "偏保守";
        if (mode == MODE_AGGRESSIVE) return "偏激进";
        return "均衡版";
    }

    public static String summary() {
        return modeName(getMode()) + " · 阈值 " + String.format(java.util.Locale.US, "%.2f", getThreshold()) + " · 采样 " + getSampleSegments();
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float clampF(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
