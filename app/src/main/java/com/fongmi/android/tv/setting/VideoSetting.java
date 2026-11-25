package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.effect.video.VideoEffectProfile;

public class VideoSetting {

    public static boolean isEnabled() {
        return false;
    }

    public static int getPreset() {
        return 0;
    }

    public static void putPreset(int preset) {
    }

    public static VideoEffectProfile getAppliedProfile() {
        return null;
    }

    public static float getSaturation() {
        return 1.0f;
    }

    public static float getContrast() {
        return 1.0f;
    }

    public static float getBrightness() {
        return 0.0f;
    }

    public static float getSharpness() {
        return 0.0f;
    }

    public static float getShadow() {
        return 0.0f;
    }

    public static float getGamma() {
        return 1.0f;
    }

    public static float getHue() {
        return 0.0f;
    }

    public static float getTemperature() {
        return 0.0f;
    }
}
