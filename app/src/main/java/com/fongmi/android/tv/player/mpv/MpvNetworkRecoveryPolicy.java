package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackRouteCapabilities;

// AVBox 适配垫片：与 webhtv 同名类的决策记录保持同构，但恢复边界一律按
// not-applicable 处理（AVBox 没有独立的网络恢复策略层）；该决策在 mpvplayer
// 包内仅用于一行诊断日志，不影响实际加载路径。
public final class MpvNetworkRecoveryPolicy {

    private MpvNetworkRecoveryPolicy() {
    }

    public static Decision resolve(String playableUri) {
        PlaybackRoute.Resolution resolution = PlaybackRoute.resolve(playableUri);
        PlaybackRouteCapabilities capabilities = PlaybackRouteCapabilities.resolve(resolution);
        return new Decision(resolution.route(), resolution.owner().label(), resolution.evidence().label(), resolution.confidence().label(), capabilities.observedLeg().label(), capabilities.upstreamVisibility().label(), capabilities.controlScope().label(), "not-applicable", false, false, false);
    }

    public record Decision(PlaybackRoute route, String routeOwner, String routeEvidence, String routeConfidence, String observedLeg, String upstreamVisibility, String controlScope, String recoveryBoundary, boolean upstreamRecoveryPolicyKnown, boolean nativeRemoteRecovery, boolean appReconnectOverlay) {
    }
}
