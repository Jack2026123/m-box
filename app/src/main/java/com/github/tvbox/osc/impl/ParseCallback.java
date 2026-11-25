package com.github.tvbox.osc.impl;

import java.util.Map;

/**
 * 隐藏嗅探 WebView 的解析回调（从 muse-build 移植，P0-1）。
 * onParseSuccess：嗅到视频直链，交给播放解析链（CustomWebView 的调用方接 PlaybackFetch/DetailLoader）。
 * onParseError：超时或关闭，解析失败。
 */
public interface ParseCallback {

    void onParseSuccess(Map<String, String> headers, String url, String from);

    void onParseError();
}
