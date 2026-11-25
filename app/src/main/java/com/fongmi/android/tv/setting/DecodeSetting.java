package com.fongmi.android.tv.setting;

import androidx.media3.common.DolbyVisionOutputPolicy;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;

public class DecodeSetting {

    public static boolean isAudioPassThrough() {
        return true;
    }

    public static void putAudioPassThrough(boolean audioPassThrough) {
    }

    public static boolean isAudioPrefer() {
        return true;
    }

    public static void putAudioPrefer(boolean audioPrefer) {
    }

    public static boolean isVideoPrefer() {
        return true;
    }

    public static void putVideoPrefer(boolean videoPrefer) {
    }

    public static @DolbyVisionOutputPolicy.Mode int getDolbyVisionOutputPolicy() {
        return DolbyVisionOutputPolicy.AUTO;
    }

    public static void putDolbyVisionOutputPolicy(@DolbyVisionOutputPolicy.Mode int mode) {
    }

    public static boolean isPreferAAC() {
        return false;
    }

    public static void putPreferAAC(boolean preferAAC) {
    }

    public static boolean isTunnel() {
        return KV.get(HawkConfig.PLAY_TUNNEL, false);
    }

    public static void putTunnel(boolean tunnel) {
        KV.put(HawkConfig.PLAY_TUNNEL, tunnel);
    }

    public static boolean isTunnelingEnabled() {
        return isTunnel() && PlayerSetting.getRender() == PlayerSetting.RENDER_SURFACE;
    }
}
