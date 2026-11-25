package com.fongmi.android.tv.player.iso;

import java.util.Map;
import java.util.function.Consumer;

// AVBox 适配垫片：webhtv 的 ISO/BDMV 网络光盘会话子系统（本地 HTTP 会话、页面缓存、
// 轨道元数据解析）未移植。所有入口返回未命中，MpvPlayer 会回落到用原始 URI 直接
// 打开（本地 .iso 文件仍可由 libmpv 自身解复用，网络 ISO 的会话化轨道信息不可用）。
public final class IsoSessionManager {

    private IsoSessionManager() {
    }

    public static String create(String url, Map<String, String> headers) {
        return null;
    }

    public static String probeAndCreate(String url, Map<String, String> headers) {
        return null;
    }

    public static void probeAndCreateAsync(String url, Map<String, String> headers, Consumer<String> callback) {
        if (callback != null) callback.accept(null);
    }

    public static void close(long id) {
    }

    public static void closeUri(String uri) {
    }

    public static long length(long id) {
        return -1;
    }

    public static long demandWaitMs(String uri) {
        return 0;
    }

    public static int readAt(long id, long offset, java.nio.ByteBuffer target, int length) {
        return -1;
    }

    public static void prepareTrackMetadata(long id, int playlist) {
    }

    public static String getTrackLanguage(long id, int trackType, int demuxId, int ordinal) {
        return null;
    }

    public static void addTrackMetadataListener(long id, Runnable listener) {
    }

    public static void removeTrackMetadataListener(long id, Runnable listener) {
    }

    public static long parseId(String uri) {
        return -1;
    }
}
