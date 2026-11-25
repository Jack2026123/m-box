package com.fongmi.android.tv.player.mpv;

import android.content.Context;

// AVBox 适配垫片：webhtv 的主线程卡顿恢复监视器会拉起恢复 Activity，AVBox 未移植；
// MpvPlayer 仅把它当看门狗回调目标，这里全部为空操作。
public final class PlaybackRecoveryMonitor implements AutoCloseable {

    public PlaybackRecoveryMonitor(Context context) {
    }

    public void onStall(long postedAtMs, long nowMs, String traceId) {
    }

    public void onHeartbeat() {
    }

    @Override
    public void close() {
    }
}
