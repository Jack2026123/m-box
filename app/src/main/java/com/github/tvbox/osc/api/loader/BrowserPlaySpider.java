package com.github.tvbox.osc.api.loader;

import android.text.TextUtils;
import android.util.Base64;
import android.webkit.CookieManager;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 3.34 网页源：浏览器模式「网页视频集数」虚拟源（移植自昏黄 SiteApi.BROWSER_PLAY）。
 *
 * <p>BrowserActivity 把网页里抽到的视频/音频地址（多集）打包成 {@code bplay:<base64url(JSON)>}
 * 作为 vod_id，经 jumpToDetail(sourceKey="browser_play") 进详情页；本 spider 就地解码，
 * 合成标准 VOD（标题 / 单线路「网页视频」/ N 集），壳播放器由此获得完整选集结构
 * （点集数、上一集/下一集、播放全部都天然可用）。pics:// 与 novel:// 载荷也走同一条路，
 * playerContent 返回 parse=0 + 原址，PlaybackFetch 再分流进图片查看器/阅读器。
 *
 * <p>载荷 JSON：{"title":..,"urls":[..],"ref":页面URL,"ua":WebView UA}。
 * 起播头（Referer/UA）登记在 bplayRef/bplayUa 静态表，playerContent 按集地址取用
 * （真机实证：无 Referer 的 m3u8 会 3003、无 Referer 的图会 404）。
 */
public class BrowserPlaySpider extends Spider {

    public static final String KEY = "browser_play";
    private static final String BPLAY_PREFIX = "bplay:";

    /** r488：UA 兜底（仿 muse PlaySpec.checkUa）。WebView UA 取不到时用这个，播放器本身不补发。 */
    private static final String FALLBACK_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Map<String, String> bplayRef = new ConcurrentHashMap<>();
    private static final Map<String, String> bplayUa = new ConcurrentHashMap<>();

    /** 日志截断（AVBox 的 CatVod Util 没有 substring(String, int)；解析器 Utils.substring 是去尾不是截断，不能用）。 */
    private static String sub(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }

    public static boolean isBrowserPlayId(String id) {
        return id != null && id.startsWith(BPLAY_PREFIX);
    }

    /** 把「标题 + 地址列表」编码成 browser_play 的 vod_id（URL_SAFE Base64，防 / + = 被截断）。 */
    public static String browserPlayId(String title, List<String> urls, String referer, String ua) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("title", title == null ? "" : title);
            JSONArray array = new JSONArray();
            for (String url : urls) {
                if (url != null && (url.startsWith("http") || url.startsWith("pics://") || url.startsWith("novel://"))) array.put(url);
            }
            payload.put("urls", array);
            if (!TextUtils.isEmpty(referer)) payload.put("ref", referer);
            if (!TextUtils.isEmpty(ua)) payload.put("ua", ua);
            return BPLAY_PREFIX + Base64.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable e) {
            return "";
        }
    }

    /** r489：多线路载荷 —— lines=[{"name":"LZ源","eps":[{"name":"01","url":"…"},…]}]，与旧 urls 载荷并存。 */
    public static String browserPlayIdLines(String title, org.json.JSONArray lines, String referer, String ua) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("title", title == null ? "" : title);
            payload.put("lines", lines);
            if (!TextUtils.isEmpty(referer)) payload.put("ref", referer);
            if (!TextUtils.isEmpty(ua)) payload.put("ua", ua);
            return BPLAY_PREFIX + Base64.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable e) {
            return "";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = (ids == null || ids.isEmpty()) ? "" : ids.get(0);
        String title = "";
        List<String> urls = new ArrayList<>();
        org.json.JSONArray linesArr = null;
        try {
            String json = id.substring(BPLAY_PREFIX.length());
            JSONObject payload = new JSONObject(new String(Base64.decode(json,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
            title = payload.optString("title", "");
            String ref = payload.optString("ref", "");
            String ua = payload.optString("ua", "");
            linesArr = payload.optJSONArray("lines");
            JSONArray array = payload.optJSONArray("urls");
            if (array != null) for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, "");
                if (!url.isEmpty()) {
                    urls.add(url);
                    if (!ref.isEmpty()) bplayRef.put(url, ref);
                    if (!ua.isEmpty()) bplayUa.put(url, ua);
                }
            }
            if (linesArr != null) registerLineHeaders(linesArr, ref, ua);
            // r489：上限 120 时整表清空 —— 265 集 × 4 线路的 VOD 解码完就被清空，Referer/UA 全丢；
            // 改到 3000（1060 条约 100KB），仍偶尔清空防无界增长。
            if (bplayRef.size() > 3000) bplayRef.clear();
            if (bplayUa.size() > 3000) bplayUa.clear();
        } catch (Throwable e) {
            DebugLogStore.add("detail", "browser_play 载荷解码失败 id=" + sub(id, 120) + " -> " + e);
        }
        try {
            String from;
            String playUrlStr;
            int total;
            if (linesArr != null && linesArr.length() > 0) {
                StringBuilder fromB = new StringBuilder();
                StringBuilder urlB = new StringBuilder();
                total = 0;
                for (int li = 0; li < linesArr.length(); li++) {
                    JSONObject line = linesArr.optJSONObject(li);
                    if (line == null) continue;
                    String lineName = line.optString("name", "");
                    JSONArray eps = line.optJSONArray("eps");
                    if (eps == null || eps.length() == 0 || lineName.isEmpty()) continue;
                    StringBuilder lineUrl = new StringBuilder();
                    for (int ei = 0; ei < eps.length(); ei++) {
                        JSONObject ep = eps.optJSONObject(ei);
                        if (ep == null) continue;
                        String u = ep.optString("url", "");
                        if (u.isEmpty()) continue;
                        String epName = ep.optString("name", "");
                        if (epName.isEmpty()) epName = episodeNameOf(u, ei);
                        if (lineUrl.length() > 0) lineUrl.append('#');
                        lineUrl.append(epName).append('$').append(u);
                        total++;
                    }
                    if (lineUrl.length() == 0) continue;
                    if (fromB.length() > 0) {
                        fromB.append("$$$");
                        urlB.append("$$$");
                    }
                    fromB.append(lineName);
                    urlB.append(lineUrl);
                }
                from = fromB.toString();
                playUrlStr = urlB.toString();
                if (from.isEmpty()) {
                    from = "网页视频";
                    playUrlStr = "";
                    total = 0;
                }
            } else {
                StringBuilder playUrl = new StringBuilder();
                for (int i = 0; i < urls.size(); i++) {
                    if (i > 0) playUrl.append('#');
                    String u = urls.get(i);
                    String epName = u.startsWith("pics://") ? "查看图片" : u.startsWith("novel://") ? "阅读正文" : episodeNameOf(u, i);
                    playUrl.append(epName).append('$').append(u);
                }
                from = "网页视频";
                playUrlStr = playUrl.toString();
                total = urls.size();
            }
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", TextUtils.isEmpty(title) ? "网页视频" : title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", from);
            vod.put("vod_play_url", playUrlStr);
            JSONArray list = new JSONArray();
            list.put(vod);
            DebugLogStore.add("detail", "browser_play 合成 VOD 标题=" + vod.optString("vod_name") + " 线路=" + from + " 集数=" + total
                    + (total == 0 ? "" : " 首集=" + sub(playUrlStr, 80)));
            return new JSONObject().put("list", list).toString();
        } catch (Throwable e) {
            return "{\"list\":[]}";
        }
    }

    /** r489：把多线路各集的 Referer/UA 登记进 bplayRef/bplayUa（playerContent 按集取头）。 */
    private static void registerLineHeaders(org.json.JSONArray linesArr, String ref, String ua) {
        if ((ref.isEmpty() && ua.isEmpty()) || linesArr == null) return;
        try {
            for (int li = 0; li < linesArr.length(); li++) {
                JSONObject line = linesArr.optJSONObject(li);
                if (line == null) continue;
                JSONArray eps = line.optJSONArray("eps");
                if (eps == null) continue;
                for (int ei = 0; ei < eps.length(); ei++) {
                    JSONObject ep = eps.optJSONObject(ei);
                    if (ep == null) continue;
                    String u = ep.optString("url", "");
                    if (u.isEmpty()) continue;
                    if (!ref.isEmpty()) bplayRef.put(u, ref);
                    if (!ua.isEmpty()) bplayUa.put(u, ua);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            JSONObject r = new JSONObject();
            // r489：集 URL 是页面型（/vodplay/…html）时返回 parse=1 —— 播放器走 CustomWebView
            // 现场嗅探直链再播（PlayUrlResolver type=0 直载嗅探，无需 jx 配置）；媒体直链仍 parse=0 直播。
            r.put("parse", isEpisodePageUrl(id) ? 1 : 0);
            r.put("url", id);
            JSONObject h = new JSONObject();
            String ref = bplayRef.get(id);
            String ua = bplayUa.get(id);
            if (!TextUtils.isEmpty(ref)) h.put("Referer", ref);
            // r488：muse 推送的头是 WebView 真实请求头（含 Cookie）；browser_play 之前只给了
            // Referer + UA，会话绑定的 m3u8 会被拒（同类于无 Referer 的 3003）。Cookie 现取。
            try {
                String cookie = CookieManager.getInstance().getCookie(id);
                if (!TextUtils.isEmpty(cookie)) h.put("Cookie", " " + cookie);
            } catch (Throwable ignored) {
            }
            // r488：UA 为空时补一个（仿 muse PlaySpec.checkUa）。
            if (TextUtils.isEmpty(ua)) ua = FALLBACK_UA;
            if (!TextUtils.isEmpty(ua)) h.put("User-Agent", ua);
            if (h.length() > 0) r.put("header", h);
            return r.toString();
        } catch (Throwable e) {
            return "{\"parse\":0,\"url\":\"" + id + "\"}";
        }
    }

    /** r489：集 URL 是不是选集页面（而非媒体直链）—— 只对这类 URL 走 parse=1 现场嗅探，
     *  无后缀的媒体 API 地址保持 parse=0，避免被 WebView 当页面加载。 */
    private static boolean isEpisodePageUrl(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return false;
        String u = url.split("\\?")[0].toLowerCase(java.util.Locale.US);
        if (u.endsWith(".html") || u.endsWith(".htm")) return true;
        return u.contains("/vodplay/") || u.contains("/play/") || u.contains("/episode/")
                || u.contains("/watch/") || u.contains("/view/") || u.contains("/vod/play/");
    }

    /** 给视频地址起集名 —— 尾段带 ≥2 位数字（001/02）优先用尾号，否则「第 N 集」。 */
    private static String episodeNameOf(String url, int index) {
        try {
            String tail = url;
            int cut = Math.max(tail.lastIndexOf('/'), tail.lastIndexOf('?'));
            if (cut >= 0) tail = tail.substring(cut + 1);
            Matcher m = Pattern.compile("(\\d{2,})[^/]*$").matcher(tail);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {
        }
        return "第" + (index + 1) + "集";
    }
}
