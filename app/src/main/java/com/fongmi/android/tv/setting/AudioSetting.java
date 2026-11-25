package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;

public class AudioSetting {

    public static final int MIN_STABILITY = 0;
    public static final int MAX_STABILITY = 100;
    public static final int MIN_DIALOGUE = 0;
    public static final int MAX_DIALOGUE = 100;
    public static final int MIN_BOOST = 0;
    public static final int MAX_BOOST = 1200;
    public static final int MIN_PREAMP = -1200;
    public static final int MAX_PREAMP = 0;
    public static final int MIN_CENTER_GAIN = 0;
    public static final int MAX_CENTER_GAIN = 1200;
    public static final int MIN_BALANCE = -100;
    public static final int MAX_BALANCE = 100;

    public static boolean isEnabled() {
        return false;
    }

    public static boolean hasEffect(int channelCount) {
        return false;
    }

    public static int getPreset() {
        return 0;
    }

    public static void putPreset(int preset) {
    }

    public static short[] getAppliedLevels(AudioEffectBands bands) {
        return null;
    }

    public static int getStability() {
        return 0;
    }

    public static int getDialogue() {
        return 0;
    }

    public static int getBoost() {
        return 0;
    }

    public static int getPreamp() {
        return 0;
    }

    public static boolean isLoudnessEnabled() {
        return false;
    }

    public static int getCenterGain() {
        return 0;
    }

    public static int getBalance() {
        return 0;
    }

    public static int getChannelMode() {
        return 0;
    }
}
