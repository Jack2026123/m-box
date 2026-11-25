package com.github.tvbox.osc.api.loader;
import android.net.Uri;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.util.SiteHomePageResolver;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
/**
 * r470 起的浏览器模式源；r475（一源两用）升级 —— 首页携带**候选镜像列表**。
 *
 * <p>面向「纯 HTTP 抓不到 / 用户切网页模式」的站点：UA 过滤站、防红壳、多级 JS 跳板链，
 * 以及金牌这类 {@code api="./xxx.py" + ext={"site":"域名1,域名2,…"}} 的源——切到网页模式后，
 * 用 ext.site 里的多个候选域名做浏览首页，按顺序试，哪个能加载成功就用哪个（短超时跳过死地址）。</p>
 *
 * <p>与 {@code web://}（WebSpider，纯 HTTP 抓取解析）并列、互不干扰；一源两用：
 * 同一源默认走壳源蜘蛛（WebSpider/py/js/jar），用户在站点列表 ⋮ 切到网页模式后，
 * 路由层（SpiderLoader.getCSP）改走本类，用候选域名进 WebView 浏览。</p>
 *
 * <p>首页返回一张 {@code type=browser} 的 action 卡，vod_id 里带 url + candidates（JSON 数组）。
 * 用户点卡 → ToolVod/Jump 解析后进 BrowserActivity，携带完整候选列表做失败轮换。</p>
 */
public class BrowserSource extends Spider {
    private final String key;
    private final String url;
    private final List<String> candidates;
    public BrowserSource(String key, String api, String ext) {
        this.key = key == null ? "" : key;
        // 候选首页列表：蜘蛛记下的活域名 → 主页url → web/browser 形态 → ext.site 等键（本地地址已过滤）
        List<String> cands = SiteHomePageResolver.candidatesFrom(this.key, api, ext, null);
        this.candidates = cands;
        this.url = cands.isEmpty() ? "" : cands.get(0);
    }
    private static String hostOf(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return TextUtils.isEmpty(host) ? url : host;
        } catch (Throwable e) {
            return url;
        }
    }
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            result.put("class", new JSONArray());
            result.put("filters", new JSONObject());
            if (!TextUtils.isEmpty(url)) {
                String title = hostOf(url);
                JSONObject id = new JSONObject();
                id.put("type", "browser");
                id.put("url", url);
                id.put("title", title);
                // r475：带完整候选镜像列表，点击进入时 BrowserActivity 按序轮换
                JSONArray candArr = new JSONArray();
                for (String c : candidates) candArr.put(c);
                id.put("candidates", candArr);
                JSONObject card = new JSONObject();
                card.put("vod_id", id.toString());
                card.put("vod_name", "打开 " + title);
                card.put("vod_pic", "");
                card.put("vod_remarks", "网页模式 · 广告已过滤 · 镜像" + candidates.size());
                JSONArray list = new JSONArray();
                list.put(card);
                result.put("list", list);
            }
            return result.toString();
        } catch (Throwable e) {
            return "{\"class\":[],\"filters\":{}}";
        }
    }
    @Override
    public String homeVideoContent() {
        return "{\"list\":[]}";
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "{\"list\":[]}";
    }
    /** browser 卡点击由 ToolVod 在 UI 层截获，不走详情；这里兜底防止空指针。 */
    @Override
    public String detailContent(List<String> ids) {
        return "{\"list\":[]}";
    }
    @Override
    public String searchContent(String key, boolean quick) {
        return "";
    }
    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return "";
    }
/** browser 模式播放全部由 BrowserActivity 内完成（自动嗅探/原生按钮），不经 playerContent。 */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "";
    }
}
