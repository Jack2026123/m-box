package com.github.tvbox.osc.api.loader;

import android.net.Uri;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.base.App;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * r339：**静默 WebView 渲染抓取** —— 解决「HTTP 抓不到、必须靠浏览器执行 JS」的站。
 *
 * 背景（真机实证）：
 *  - 厂长 www.4kcz.com 走长亭雷池 WAF：HTTP 只能拿到 694KB 的 JS 挑战页（links=3，全是 .safeline 资源），
 *    分类永远抽不到；
 *  - 黄仓 both.folrl0a2w.cc 黑料详情页的播放器靠 packer 的 document.write 动态加载。
 * 这两类都必须「真的让浏览器把页面跑起来」。
 *
 * 做法：在 UI 线程建一个不可见的 WebView → loadUrl → onPageFinished 后
 *       evaluateJavascript 取 documentElement.outerHTML → 回传字符串。
 * 安全边界：只读、不点击、不注入业务脚本；调用方自己决定怎么用这份 HTML。
 */
public class WebFetch {

    private static final String TAG = "WebFetch";

    /** 主线程投递（AVBox 移植版：原 App.post 改为 Handler；base/App.kt 没有 post()）。 */
    private static void postMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private static void postMain(Runnable r, long delayMillis) {
        new Handler(Looper.getMainLooper()).postDelayed(r, delayMillis);
    }

    /** 取当前渲染后的 DOM；超时或失败返回空串（调用方需自行判空）。 */
    public static String fetch(String url, Map<String, String> headers, long timeoutMs) {
        if (TextUtils.isEmpty(url)) return "";
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<>("");
        final AtomicBoolean done = new AtomicBoolean(false);
        final long timeout = timeoutMs <= 0 ? 20000 : timeoutMs;
        postMain(() -> {
            WebView view = null;
            try {
                view = new WebView(App.getInstance());
                // r360：Application context 的 WebView 不挂窗口，默认尺寸 0×0 —— 防红壳（b7b88/7e2ck8 类
                // ap-d 壳）检测 window 尺寸异常直接拒解密（真机：WebFetch 渲染 17 秒 0 链接输出「请开启 JavaScript」，
                // 同 UA 同内核的内置浏览器 190 链接完整渲染，唯一实质差异就是从未 layout）。给真实手机尺寸。
                view.layout(0, 0, 1440, 3200);
                WebSettings s = view.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                s.setLoadsImagesAutomatically(false);   // 只要 DOM，不加载图片省流量
                if (headers != null) {
                    String ck = headers.get("Cookie");
                    if (!TextUtils.isEmpty(ck)) {
                        CookieManager.getInstance().setCookie(url, ck);
                        CookieManager.getInstance().flush();
                    }
                }
                // r360：渲染 UA 改用系统真实 UA（引擎与 UA 版本一致，Chrome/149 对 Chrome/149）。
                // 原先 WebSpider 传入写死的 Chrome/122 —— 与真实内核 149 错位，防红壳可凭版本不匹配
                // 识别伪装。内置浏览器（同引擎）用真实 UA 能完整渲染 b7b88（A 级实测 links=190）。
                // 调用方在 headers 传 UA 时仍优先调用方（保留显式伪装语义）。
                String hua = headers == null ? null : headers.get("User-Agent");
                s.setUserAgentString(TextUtils.isEmpty(hua) ? WebSettings.getDefaultUserAgent(App.getInstance()) : hua);
                final WebView fv = view;
                view.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String u) {
                        // r342：WAF 挑战页（4kcz safeline 694KB，内置浏览器实测）第一次 onPageFinished 时
                        // 挑战 JS 还没跑完 —— 直接取 DOM 拿到的是挑战页（title 空、链接≈0）→ 分类永远抽不到，
                        // 这就是 r340「WebView 渲染补抓」真机无效的根因（取证→验证→改动，无凭猜测）。
                        // 改为：先探测页面形态（title 长度 + 链接数），像挑战页/未渲染页就等待——
                        // 挑战 JS 完成后会自动 reload 触发下一次 onPageFinished，同时挂 2.5 秒轮询兜底；
                        // 真页（title 非空且链接 ≥5）才取 DOM。轮询超时后照旧取当前 DOM（最坏=旧行为，不劣化）。
                        try {
                            v.evaluateJavascript(
                                    "(function(){try{var t=(document.title||'').length;var n=document.querySelectorAll('a[href]').length;return t+'/'+n}catch(e){return '0/0'}})()",
                                    val -> {
                                        if (done.get()) return;
                                        String s = decode(val);
                                        int t = 0, n = 0;
                                        int i = s.indexOf('/');
                                        if (i > 0) {
                                            t = parseIntS(s.substring(0, i));
                                            n = parseIntS(s.substring(i + 1));
                                        }
                        // r360：判据放宽 —— b7b88/7e2ck8 站点不设 title（永远空），原判据 t>0&&n>=5
                        // 永不满足，白等 14 秒后取的 DOM 还是被拒解密的提示页。改为：links≥20 直接取
                        //（挑战页 links≤3，真页 links 上百，区分度足够）；常规站 title+links 判据保留。
                        if (n >= 20 || (t > 0 && n >= 5)) takeDom(v, out, latch, done);
                        else {
                            SpiderDebug.log(TAG, "页面尚未就绪（title=%d links=%d），等待挑战/渲染完成", t, n);
                            postMain(() -> pollReady(v, out, latch, done, System.currentTimeMillis() + 14000), 2500);
                        }
                                    });
                        } catch (Throwable e) {
                            takeDom(v, out, latch, done);
                        }
                    }

                    @Override
                    public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, android.webkit.WebResourceRequest req) {
                        // r372：**原生层拦截接口请求** —— 比 JS 注入可靠：
                        // JS 注入需要 onPageStarted 时机（WebFetch 没有），而这里能看到页面的每一个请求。
                        // 只记录不替换（返回 null = 页面正常加载），Java 侧再重放取数据。
                        try {
                            if (req != null && req.getUrl() != null) WebSpider.onUrlSeen(req.getUrl().toString());
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }

                    @Override
                    public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                        try {
                            if (req != null && req.isForMainFrame()) {
                                SpiderDebug.log(TAG, "抓取失败 code=%s url=%s", err.getErrorCode(), req.getUrl());
                                latch.countDown();
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    public void onReceivedHttpError(WebView v, WebResourceRequest req, android.webkit.WebResourceResponse resp) {
                        try {
                            if (req != null && req.isForMainFrame()) {
                                SpiderDebug.log(TAG, "抓取 HTTP %s url=%s", resp.getStatusCode(), req.getUrl());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                view.loadUrl(url);
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "创建 WebView 失败 %s", e);
                latch.countDown();
            }
            // 超时兜底：到点就放行，避免调用方线程一直挂着
            postMain(latch::countDown, timeout);
        });
        try {
            latch.await(timeout + 3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        String html = out.get();
        if (!TextUtils.isEmpty(html)) {
            SpiderDebug.log(TAG, "抓取完成 %s 字 %s", html.length(), url.length() > 80 ? url.substring(0, 80) : url);
        }
        return html == null ? "" : html;
    }

    /** evaluateJavascript 回传的是 JSON 字符串（带引号与转义），这里还原成原始文本。 */
    private static String decode(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String v = value.trim();
        if ("null".equals(v)) return "";
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            try {
                return new JSONArray("[" + v + "]").getString(0);
            } catch (Throwable ignored) {
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ r342 新增

    /** r342：轮询等待挑战/渲染完成 —— title 非空且链接 ≥5 视为真页面；到 deadline 仍不达标就取当前 DOM（最坏=旧行为）。 */
    private static void pollReady(WebView v, AtomicReference<String> out, CountDownLatch latch, AtomicBoolean done, long deadline) {
        if (done.get()) return;
        try {
            v.evaluateJavascript(
                    "(function(){try{var t=(document.title||'').length;var n=document.querySelectorAll('a[href]').length;return t+'/'+n}catch(e){return '0/0'}})()",
                    val -> {
                        if (done.get()) return;
                        String s = decode(val);
                        int t = 0, n = 0;
                        int i = s.indexOf('/');
                        if (i > 0) {
                            t = parseIntS(s.substring(0, i));
                            n = parseIntS(s.substring(i + 1));
                        }
                        if (n >= 20 || (t > 0 && n >= 5)) takeDom(v, out, latch, done);   // r360：判据与 onPageFinished 同款放宽
                        else if (System.currentTimeMillis() < deadline) postMain(() -> pollReady(v, out, latch, done, deadline), 2000);
                        else takeDom(v, out, latch, done);
                    });
        } catch (Throwable e) {
            takeDom(v, out, latch, done);
        }
    }

    /** r342：取当前渲染 DOM 并放行（done 幂等保护，防止挑战页与真页的取值乱序覆盖）。 */
    private static void takeDom(WebView v, AtomicReference<String> out, CountDownLatch latch, AtomicBoolean done) {
        if (done.get()) return;
        try {
            // r360：主文档 + 同源 iframe 一起取 —— 防红壳解密后把真页面写进 iframe
            //（跨域 iframe 拿不到是 WebView 安全限制，静默跳过）。
            v.evaluateJavascript(
                    "(function(){try{var h=document.documentElement?document.documentElement.outerHTML:'';"
                    + "var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){try{var d=fs[i].contentDocument;"
                    + "if(d&&d.documentElement)h+='\\n'+d.documentElement.outerHTML}catch(e){}}return h}catch(e){return ''}})()",
                    value -> {
                        if (done.compareAndSet(false, true)) {
                            out.set(decode(value));
                            latch.countDown();
                            // r360：顺手修 r339 起的 WebView 泄漏 —— 每次 fetch new 的实例从不 destroy，
                            // 源检测/渲染兜底多跑几轮内存就涨。done 后延迟销毁（等回调链走完）。
                            postMain(() -> { try { v.destroy(); } catch (Throwable ignored) { } }, 500);
                        }
                    });
        } catch (Throwable e) {
            if (done.compareAndSet(false, true)) latch.countDown();
        }
    }

    private static int parseIntS(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return 0;
        }
    }
}
