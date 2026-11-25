package com.github.tvbox.osc.api.loader;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

/**
 * r471：网页源手动切换浏览器模式（per-site 持久化开关）。
 *
 * <p>与 r470 的两种浏览器源写法（api="browser://…" / api="browser"+ext 网址）互补：
 * 那两种是配置层写死的路由，这个开关让用户在站点列表页 ⋮ 菜单里对任意网页源
 * （api="web" 或网址型）一键切换，不碰源配置本身。</p>
 *
 * <p>路由位置：{@link BaseLoader#getSpider} 的 isWeb 分支 —— 开关打开时该站
 * 直接走 {@link BrowserSource}（WebView 浏览 + 原生播放），关闭恢复 {@link WebSpider}
 * 纯 HTTP 抓取（r470 的「HTTP 失败自动降级」仍然保留，两机制不冲突）。</p>
 *
 * <p>存储：SharedPreferences 单 key 布尔（browser_mode_&lt;siteKey&gt;），默认关闭；
 * 关闭时移除记录保持存储干净。Site.spider() 每次取数都新建实例，切换后立即生效。</p>
 */
public class BrowserMode {

    private static final String PREFIX = "browser_mode_";

    /** 该站点是否被用户手动切到浏览器模式。 */
    public static boolean isOn(String key) {
        return !TextUtils.isEmpty(key) && Prefers.getBoolean(PREFIX + key, false);
    }

    /** 设置开关；关闭时移除记录，保持存储干净。 */
    public static void setOn(String key, boolean on) {
        if (TextUtils.isEmpty(key)) return;
        if (on) Prefers.put(PREFIX + key, true);
        else Prefers.remove(PREFIX + key);
    }

    /** 切换并返回切换后的状态。 */
    public static boolean toggle(String key) {
        boolean on = !isOn(key);
        setOn(key, on);
        return on;
    }
}
