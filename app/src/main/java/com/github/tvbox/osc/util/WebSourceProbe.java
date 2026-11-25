package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.api.loader.WebSourceHeal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * r349：网页源失效检测 —— 轻量 HTTP 探测（不跑 Spider 管线）。
 *
 * <p>判据（用户口径「检测网站能否访问」，保守优先防误杀）：
 * <ul>
 *   <li>GET 收到<b>任意</b> HTTP 响应（含 403 / 5xx）= 能访问 —— 403 只是反爬墙，
 *       真机 WebView 未必受影响（沙箱实测 tss.faloo/4kcz 对脚本 403，真机日志正常访问）；</li>
 *   <li>IOException（连接失败 / DNS 解析失败 / 超时）= 失效 —— 域名废了或服务器关停。</li>
 * </ul>
 *
 * 与 {@code SiteProbe} 的区别：那是重型蜘蛛管线（分类→翻页→判断 list 非空），
 * 对网页源不成比例且有全局超时副作用；这里固定 4 线程 + 5s 超时 + 每源一行日志。
 * 检测结果由调用方落 {@code WebSourceStore.markDead}（失效移入「失效源」分组，保留原分组标签）。
 * r350：判失效且源配置了发布页时自动调 {@link WebSourceHeal#heal}（发布页提取新域名→探活→落盘），
 * 自愈成功则不进失效桶 —— 「检测失效源」= 检测 + 修复，用户心智模型简单。
 */
public class WebSourceProbe {

    private static final String TAG = "webprobe";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    /** 主线程投递（AVBox 移植版：原 App.post 改为 Handler；base/App.kt 没有 post()）。 */
    private static void postMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    public interface Listener {
        /** 每完成一条（主线程）。 */
        void onTick(int done, int total);

        /** 全部完成（主线程），deadUrls 为失效源的 url 清单。 */
        void onDone(List<String> deadUrls);
    }

    /** 并发探测一批网页源（元素为 {名称, 网址, 分组, 失效标记}，只用到前两段）。 */
    public static void start(List<String[]> items, Listener listener) {
        if (items == null || items.isEmpty()) {
            if (listener != null) postMain(() -> listener.onDone(new ArrayList<>()));
            return;
        }
        final int total = items.size();
        final AtomicInteger done = new AtomicInteger(0);
        final List<String> deadUrls = new ArrayList<>();
        final Listener cb = listener == null ? new Listener() {
            @Override public void onTick(int d, int t) {
            }

            @Override public void onDone(List<String> dead) {
            }
        } : listener;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, Math.max(2, total / 4 + 1)));
        for (String[] item : items) {
            final String name = item[0];
            final String url = item[1];
            pool.execute(() -> {
                boolean alive = probe(url);
                String healed = "";
                if (!alive) {
                    // r350：判失效且有发布页 → 自动自愈（发布页提取新域名→探活→落盘+内存同步）。
                    // 成功则不进失效桶（域名已换新）；失败保持原判（不动原 url）。
                    healed = WebSourceHeal.heal(url);
                    if (!TextUtils.isEmpty(healed)) alive = true;
                }
                DebugLogStore.add(TAG, name + " " + url + " -> " + (alive ? (TextUtils.isEmpty(healed) ? "可访问" : "已自愈 → " + healed) : "失效（连接失败/超时）"));
                synchronized (deadUrls) {
                    if (!alive) deadUrls.add(url);
                }
                int d = done.incrementAndGet();
                postMain(() -> cb.onTick(d, total));
            });
        }
        pool.shutdown();
        // 守护：全部执行完（shutdown 后 await）再回调 onDone
        Thread watcher = new Thread(() -> {
            try {
                while (!pool.isTerminated()) {
                    //noinspection BusyWait
                    Thread.sleep(200);
                }
            } catch (Throwable ignored) {
            }
            postMain(() -> {
                synchronized (deadUrls) {
                    cb.onDone(new ArrayList<>(deadUrls));
                }
            });
        }, "web-probe-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** 单源探测：任意 HTTP 响应（含 403/5xx）= 能访问；IOException = 失效。 */
    private static boolean probe(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build();
            try (Response res = OkHttp.client(5000).newCall(req).execute()) {
                ResponseBody body = res.body();
                if (body != null) body.close();
                // 收到响应即算活着 —— 不看 code（403/5xx 也比「连不上」强，反爬墙不代表站死了）
                return true;
            }
        } catch (IOException e) {
            return false;
        } catch (Throwable e) {
            // 奇怪异常（URL 非法等）按失效处理，日志留痕
            DebugLogStore.add(TAG, "探测异常 " + url + " " + e);
            return false;
        }
    }
}
