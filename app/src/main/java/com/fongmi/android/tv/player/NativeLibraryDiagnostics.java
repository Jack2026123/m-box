package com.fongmi.android.tv.player;

// AVBox 适配垫片：webhtv 的原生库诊断上报在 AVBox 不存在，全部为空操作。
public final class NativeLibraryDiagnostics {

    private NativeLibraryDiagnostics() {
    }

    public static void request() {
    }

    public static void failure(String library, Throwable error) {
    }
}
