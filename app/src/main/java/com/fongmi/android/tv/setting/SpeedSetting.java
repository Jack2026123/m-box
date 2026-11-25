package com.fongmi.android.tv.setting;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;

public class SpeedSetting {

    public static final float MIN = 0.1f;
    public static final float MAX = 10.0f;
    public static final float STEP = 0.1f;
    public static final float NORMAL = 1.0f;

    private static boolean skipSilence = false;

    public static float clamp(float speed) {
        return Math.clamp(speed, MIN, MAX);
    }

    public static float clampPress(float speed) {
        return clamp(speed);
    }

    public static float clampLongPress(float speed) {
        return clamp(speed);
    }

    public static float getLongPress() {
        return KV.get(HawkConfig.LONG_PRESS_SPEED, HawkConfig.LONG_PRESS_SPEED_DEFAULT).floatValue();
    }

    public static void putLongPress(float speed) {
        KV.put(HawkConfig.LONG_PRESS_SPEED, (int) speed);
    }

    public static boolean isSkipSilence() {
        return skipSilence;
    }

    public static void putSkipSilence(boolean enabled) {
        skipSilence = enabled;
    }
}
