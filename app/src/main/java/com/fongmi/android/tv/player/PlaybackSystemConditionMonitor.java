package com.fongmi.android.tv.player;

// AVBox 适配垫片：webhtv 的系统状态监视器（网络/电量/温度采样 + 协调器/策略
// 子系统）未移植；mpvplayer 包只通过 process().currentNetworkSnapshot() 读取
// 网络快照喂给 PreloadPausePolicy，这里恒返回 unknown 快照（策略按可用网络处理）。
public final class PlaybackSystemConditionMonitor {

    private static final PlaybackSystemConditionMonitor PROCESS = new PlaybackSystemConditionMonitor();

    private PlaybackSystemConditionMonitor() {
    }

    public static PlaybackSystemConditionMonitor process() {
        return PROCESS;
    }

    public PlaybackAutoContext.NetworkSnapshot currentNetworkSnapshot() {
        return PlaybackAutoContext.NetworkSnapshot.unknown();
    }
}
