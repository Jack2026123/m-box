package com.fongmi.android.tv.player;

// AVBox 适配垫片：诊断控件注册表在 AVBox 未移植，注册动作全部为空操作。
public final class DiagnosticControls {

    private DiagnosticControls() {
    }

    public static void registerFelBindProbe(FelBindProbeControl probe) {
    }

    public static void playback(PlaybackDiagnosticCollector collector) {
    }
}
