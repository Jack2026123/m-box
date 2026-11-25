package com.github.tvbox.osc.ui.custom;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.loader.WebSpider;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.activity.BrowserActivity;
import com.github.tvbox.osc.ui.page.JumpKt;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.17 WebHome：本地 html 模板与壳之间的桥（window.AVBox）。
 *
 * <p>只挂给本地模板页（assets/webhome/index.html 或用户导入的模板文件），切回原网页时
 * BrowserActivity 会 removeJavascriptInterface。桥只做三件事：用 WebSpider 把网页源解析成
 * 结构化数据（首页 / 分类 / 搜索）回给模板；点海报进壳详情/播放（走 JumpKt，和首页点片同一条路，
 * 浏览器动作卡也会被 JumpKt 转回 BrowserActivity）；打开原网页。
 *
 * <p>蜘蛛方法会联网（detect 拉首页），所以 home/category/search 都在后台线程跑，结果通过
 * evaluateJavascript 回调进 JS（window.__onHome/__onCategory/__onSearch），不在桥方法里同步返回。
 * 另有 pickTemplate：模板页里的「换模板」按钮点开壳的模板切换弹层（和长按工具栏 HTML 同一个入口）。
 */
public class WebHomeBridge {

    public interface OnOpenWebListener {
        void onOpenWeb(String url);
    }

    private final Activity activity;
    private final WebView webView;
    private final SourceBean source;
    private final String homeUrl;
    private final OnOpenWebListener listener;
    private final Spider spider;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public WebHomeBridge(Activity activity, WebView webView, SourceBean source, String homeUrl, OnOpenWebListener listener) {
        this.activity = activity;
        this.webView = webView;
        this.source = source;
        this.homeUrl = homeUrl == null ? "" : homeUrl;
        this.listener = listener;
        Spider sp = null;
        try {
            sp = new WebSpider(source.getKey(), source.getApi(), source.getExt());
            sp.init(activity, source.getExt());
        } catch (Throwable ignored) {
        }
        this.spider = sp;
    }

    @JavascriptInterface
    public String siteName() {
        return source.getName() == null ? "" : source.getName();
    }

    @JavascriptInterface
    public String homeUrl() {
        return homeUrl;
    }

    @JavascriptInterface
    public void home() {
        load(() -> spider.homeContent(true), "__onHome");
    }

    @JavascriptInterface
    public void category(String tid, int pg) {
        final String t = tid == null ? "" : tid;
        final int p = Math.max(1, pg);
        load(() -> spider.categoryContent(t, String.valueOf(p), false, new HashMap<String, String>()), "__onCategory");
    }

    @JavascriptInterface
    public void search(String key) {
        final String k = key == null ? "" : key;
        load(() -> spider.searchContent(k, false), "__onSearch");
    }

    @JavascriptInterface
    public void openDetail(String vodId, String name, String pic) {
        final String id = vodId == null ? "" : vodId;
        final String n = name == null ? "" : name;
        final String p = pic == null ? "" : pic;
        activity.runOnUiThread(() -> {
            try {
                JumpKt.jumpToDetail(activity, id, source.getKey(), n, p, false, true, true);
            } catch (Throwable ignored) {
            }
        });
    }

    @JavascriptInterface
    public void openWeb(String url) {
        final String u = url == null ? "" : url;
        activity.runOnUiThread(() -> {
            try {
                if (listener != null) listener.onOpenWeb(u);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 1.18：模板页里的「换模板」按钮，点开壳的模板切换弹层（和长按工具栏 HTML 同一个入口）。 */
    @JavascriptInterface
    public void pickTemplate() {
        activity.runOnUiThread(() -> {
            try {
                if (activity instanceof BrowserActivity) {
                    ((BrowserActivity) activity).showTemplatePicker();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    public void destroy() {
        executor.shutdownNow();
    }

    private interface Job {
        String run() throws Exception;
    }

    private void load(final Job job, final String callback) {
        executor.execute(() -> {
            String json = null;
            try {
                if (spider != null) json = job.run();
            } catch (Throwable ignored) {
            }
            if (json == null) json = "{\"list\":[]}";
            final String safe = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            activity.runOnUiThread(() -> {
                try {
                    webView.evaluateJavascript("window." + callback + "(" + safe + ")", null);
                } catch (Throwable ignored) {
                }
            });
        });
    }
}
