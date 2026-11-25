package com.github.tvbox.osc.ui.custom;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.api.loader.RuleConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.impl.ParseCallback;
import com.github.tvbox.osc.ui.dialog.WebDialog;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.Sniffer;
import com.google.common.net.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 隐藏嗅探 WebView（从 muse-build 移植，P0-1）。
 * 不可见的 WebView 打开播放页：拦截到视频直链就回调 onParseSuccess 交给播放解析链；
 * 遇到 Cloudflare 验证就弹 WebDialog 让用户手动过；按分站规则跑 JS、拦广告域名。
 *
 * muse 原版在 com.muse.android.tv.ui.custom.CustomWebView；按 AVBox 接线改过：
 * App.post/removeCallbacks → 主线程 Handler；App.activity() → AppManager.currentActivity()；
 * Setting.getUa() → WebView 默认 UA；VodConfig.get().getSite(key).spider() →
 * ApiConfig.getSource(key) + ApiConfig.getCSP()；TIMEOUT_PARSE_WEB = 15 秒。
 */
public class CustomWebView extends WebView implements DialogInterface.OnDismissListener {

    private static final String TAG = CustomWebView.class.getSimpleName();

    private static final Pattern PLAYER = Pattern.compile("player.*https?://");
    private static final String BLANK = "about:blank";
    private static final int MAX_URLS = 5;
    private static final long TIMEOUT_PARSE_WEB = 15000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<ParseCallback> callbackRef = new AtomicReference<>();
    private LinkedHashSet<String> urls;
    private WebResourceResponse empty;
    private WebDialog dialog;
    private Runnable timer;
    private boolean stopped;
    private boolean detect;
    private String click;
    private String from;
    private String key;
    private String url;

    public static CustomWebView create(@NonNull Context context) {
        return new CustomWebView(context);
    }

    private CustomWebView(@NonNull Context context) {
        super(context);
        initSettings();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initSettings() {
        timer = () -> stop(true);
        urls = new LinkedHashSet<>();
        empty = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
        WebSettings setting = getSettings();
        setting.setSupportZoom(true);
        setting.setUseWideViewPort(true);
        setting.setDatabaseEnabled(true);
        setting.setDomStorageEnabled(true);
        setting.setJavaScriptEnabled(true);
        setting.setBuiltInZoomControls(true);
        setting.setDisplayZoomControls(false);
        setting.setLoadWithOverviewMode(true);
        setting.setUserAgentString(WebSettings.getDefaultUserAgent(getContext()));
        setting.setMediaPlaybackRequiresUserGesture(false);
        setting.setJavaScriptCanOpenWindowsAutomatically(false);
        setting.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        setWebViewClient(webViewClient());
    }

    public CustomWebView start(String key, String from, Map<String, String> headers, String url, String click, ParseCallback callback, boolean detect) {
        SpiderDebug.log(TAG, "key=%s, from=%s, click=%s, url=%s, headers=%s", key, from, click, url, headers);
        mainHandler.postDelayed(timer, TIMEOUT_PARSE_WEB);
        callbackRef.set(callback);
        this.detect = detect;
        this.click = click;
        this.from = from;
        this.key = key;
        this.url = url;
        start(headers);
        return this;
    }

    private void start(Map<String, String> headers) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true);
        checkHeader(url, headers);
        loadUrl(url, headers);
    }

    private void checkHeader(String url, Map<String, String> headers) {
        for (String key : headers.keySet()) {
            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key)) getSettings().setUserAgentString(headers.get(key));
            else if (HttpHeaders.COOKIE.equalsIgnoreCase(key)) CookieManager.getInstance().setCookie(url, headers.get(key));
        }
    }

    private WebViewClient webViewClient() {
        return new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String host = request.getUrl().getHost();
                if (TextUtils.isEmpty(host) || isAd(host)) return empty;
                Map<String, String> headers = request.getRequestHeaders();
                if (url.contains("/cdn-cgi/challenge-platform/")) post(() -> showDialog());
                if (detect && PLAYER.matcher(url).find() && addUrl(url)) onParseAdd(headers, url);
                else if (isVideoFormat(url)) onParseSuccess(headers, url);
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url.equals(BLANK)) return;
                evaluate(getScript(url), 0);
            }

            @Override
            @SuppressLint("WebViewClientOnReceivedSslError")
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        };
    }

    private boolean addUrl(String url) {
        if (urls.size() > MAX_URLS) urls.clear();
        return urls.add(url);
    }

    private void showDialog() {
        if (dialog != null || currentActivity() == null) return;
        if (getParent() != null) ((ViewGroup) getParent()).removeView(this);
        dialog = WebDialog.create(this).show();
        mainHandler.removeCallbacks(timer);
    }

    private void hideDialog() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        stop(true);
    }

    private List<String> getScript(String url) {
        List<String> script = new ArrayList<>(Sniffer.getScript(Uri.parse(url)));
        if (TextUtils.isEmpty(click) || script.contains(click)) return script;
        script.add(0, click);
        return script;
    }

    private void evaluate(List<String> script, int index) {
        if (index >= script.size()) return;
        String js = script.get(index);
        if (TextUtils.isEmpty(js)) {
            evaluate(script, index + 1);
        } else {
            evaluateJavascript(js, value -> evaluate(script, index + 1));
        }
    }

    private boolean isAd(String host) {
        return RuleConfig.get().isAdHost(host);
    }

    private boolean isVideoFormat(String url) {
        try {
            if (!detect && url.equals(this.url)) return false;
            Spider spider = spiderForKey(key);
            if (spider != null && spider.manualVideoCheck()) return spider.isVideoFormat(url);
            return Sniffer.isVideoFormat(url);
        } catch (Exception ignored) {
            return Sniffer.isVideoFormat(url);
        }
    }

    /** muse 的 VodConfig.get().getSite(key).spider() → AVBox：按 key 取源，再拿它的蜘蛛。 */
    private Spider spiderForKey(String key) {
        try {
            SourceBean bean = ApiConfig.get().getSource(key);
            if (bean == null) return null;
            return ApiConfig.get().getCSP(bean);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void onParseAdd(Map<String, String> headers, String url) {
        ParseCallback cb = callbackRef.get();
        if (cb == null) return;
        post(() -> CustomWebView.create(getContext()).start(key, from, headers, url, click, cb, false));
    }

    private void onParseSuccess(Map<String, String> headers, String url) {
        ParseCallback cb = callbackRef.getAndSet(null);
        if (cb != null) cb.onParseSuccess(headers, url, from);
        post(() -> stop(false));
    }

    private void onParseError() {
        ParseCallback cb = callbackRef.getAndSet(null);
        if (cb != null) cb.onParseError();
    }

    public void stop(boolean error) {
        if (stopped) return;
        stopped = true;
        hideDialog();
        stopLoading();
        loadUrl(BLANK);
        mainHandler.removeCallbacks(timer);
        if (error) onParseError();
        else callbackRef.set(null);
    }

    /** 当前 Activity（AppManager 栈顶；拿不到返回 null，调用方自行处理）。 */
    private static Activity currentActivity() {
        try {
            return AppManager.getInstance().currentActivity();
        } catch (Exception ignored) {
            return null;
        }
    }
}
