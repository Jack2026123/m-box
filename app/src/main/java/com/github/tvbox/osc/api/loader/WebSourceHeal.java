package com.github.tvbox.osc.api.loader;

import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * r350：网页源域名失效自愈（参考 jable_失效自愈版.py 的机制，Java 化）。
 *
 * <p>机制：源可配置「发布页 / 项目地址」（{@link WebSourceStore} 第 5 段）。域名失效时
 * 从发布页提取同族新域名 → 探活 → 替换写回（Prefers 落盘 + 内存 Site 同步，免重启生效）。
 *
 * <p>提取双型（沙箱 28 用例验证，.taixu-outputs/r350test/verify_heal.py 28/28）：
 * <ol>
 *   <li><b>encodedData 型</b>（x99dh 系导航站）：HTML 里 {@code encodedData="base64"} →
 *       Base64 → URLDecoder → JSON {@code [{name, urls:[{url}]}]}，同族定位条目取全部 urls。
 *       实证：发布页常返 5xx 但 body 完整 —— <b>只认内容不认状态码</b>；
 *       MissAV 41 候选、Jable 3 候选（/enter 剥尾）；</li>
 *   <li><b>HTML 链接型兜底</b>：href 抽取 → 同族匹配 → 取 origin。</li>
 * </ol>
 *
 * <p>同族判据（任一）：源名互含 / oldHost 核心词互含 / 条目 urls host 互含
 * （www.thisav66.com 与 MissAV 字符串层面永不同族，但条目 urls 里就列着 thisav66，能反查定位）。
 *
 * <p>探活判据（与 r349 WebSourceProbe 的「域名活着」语义不同，自愈要「首页可用」）：
 * <b>body &gt; 5000 字节</b> —— 实证 thisav66.com origin 根路径返 HTTP 500 + 2563B 错误页，
 * 只看「有响应」会把自愈结果引到首页仍 500 的域名上。
 *
 * <p>入口：{@link #quickHeal}（WebSpider.detect 首页抓空时，进程内每 host 节流一次）、
 * {@link #heal}（WebSourceProbe 探测判失效后联动）。
 */
public class WebSourceHeal {

    private static final String TAG = "webheal";
    private static final String UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/137.0 Mobile Safari/537.36";
    private static final Pattern ENCODED = Pattern.compile("encodedData\\s*=\\s*[\"']([A-Za-z0-9+/=]+)[\"']");
    private static final Pattern HREF = Pattern.compile("href=[\"']?(https?://[^\"'\\s>]+)");
    private static final Set<String> ATTEMPTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> TLDS = new HashSet<>(Arrays.asList(
            "com", "net", "org", "ws", "tv", "cc", "io", "co", "me", "ai", "vip", "one", "life", "watch",
            "pw", "sbs", "art", "nl", "my", "icu", "pro", "info", "biz", "xyz", "top", "club", "site",
            "online", "fun", "cn", "tw", "hk", "jp", "kr", "us", "uk", "de", "fr", "ru", "to", "link",
            "lol", "cyou", "shop", "plus", "red", "live", "zone", "wiki", "edu", "gov", "mobi", "name",
            "asia", "cat", "jobs", "mail", "tel", "travel", "xxx", "tech", "store", "website", "space",
            "press", "public"));

    /** 节流版：同一 host 每次进程只自愈一次（防循环重试）。WebSpider.detect 首页抓空时用。 */
    public static String quickHeal(String oldUrl) {
        String h = WebSourceStore.host(oldUrl);
        if (TextUtils.isEmpty(h) || !ATTEMPTED.add(h)) return null;
        return heal(oldUrl);
    }

    /** 主入口：返回新 url（已落盘 + 内存同步）；失败返回 null（不动原 url）。 */
    public static String heal(String oldUrl) {
        HealResult r = healDetail(oldUrl);
        return r.ok() ? r.newUrl : null;
    }

    /** r351：自愈结果 —— newUrl 非空 = 成功；否则 reason 为人话失败原因（手动自愈汇报用）。 */
    public static class HealResult {
        public final String name, oldUrl, newUrl, reason;

        HealResult(String name, String oldUrl, String newUrl, String reason) {
            this.name = name;
            this.oldUrl = oldUrl;
            this.newUrl = newUrl;
            this.reason = reason;
        }

        public boolean ok() {
            return !TextUtils.isEmpty(newUrl);
        }
    }

    /** r351：带结果的自愈（手动自愈/汇报用）—— 全路径留日志。
     *  r350 事故：未配置发布页时静默 return null，真机日志 webheal 一条没有，
     *  用户看到「失效却没自愈」且无从排查（webprobe 判失效 → heal 静默跳过）。 */
    public static HealResult healDetail(String oldUrl) {
        String oldHost = WebSourceStore.host(oldUrl);
        String[] meta = metaOf(oldUrl);
        try {
            String pub = WebSourceStore.publishOf(oldUrl);
            if (TextUtils.isEmpty(pub)) {
                DebugLogStore.add(TAG, "跳过自愈 " + oldHost + "（未配置发布页）");
                return new HealResult(meta[0], oldUrl, "", "未配置发布页");
            }
            Extract ex = extract(pub, meta[0], oldUrl);
            if (ex.cands.isEmpty()) {
                String reason = TextUtils.isEmpty(ex.fail) ? "发布页无同族条目" : ex.fail;
                DebugLogStore.add(TAG, "自愈失败 " + oldHost + "（" + reason + "）");
                return new HealResult(meta[0], oldUrl, "", reason);
            }
            int max = Math.min(3, ex.cands.size());
            for (int i = 0; i < max; i++) {
                String cand = ex.cands.get(i);
                if (!alive(cand)) continue;
                apply(oldUrl, cand, meta[0], meta[1]);
                return new HealResult(meta[0], oldUrl, cand, "");
            }
            String reason = "候选 " + ex.cands.size() + " 个全不可用";
            DebugLogStore.add(TAG, "自愈失败 " + oldHost + "（" + reason + "）");
            return new HealResult(meta[0], oldUrl, "", reason);
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "自愈异常 " + e);
            return new HealResult(meta[0], oldUrl, "", "自愈异常 " + e);
        }
    }

    /** 按当前存储查源的 {名称, 分组}（找不到时名称回退 host）。 */
    private static String[] metaOf(String oldUrl) {
        String h = WebSourceStore.host(oldUrl);
        for (String[] item : WebSourceStore.list()) {
            if (WebSourceStore.host(item[1]).equals(h)) {
                return new String[]{item[0], item.length > 2 ? item[2] : ""};
            }
        }
        return new String[]{h, ""};
    }

    /** r351：提取结果 —— cands 为候选（保序去重）；cands 空时 fail 为人话原因。双型：encodedData → HTML 链接兜底。 */
    static Extract extract(String pubUrl, String srcName, String oldUrl) {
        Extract out = new Extract();
        String html = body(pubUrl, 10000);
        if (TextUtils.isEmpty(html)) {
            out.fail = "发布页不可达";
            return out;
        }
        String pubHost = WebSourceStore.host(pubUrl);
        String oldHost = WebSourceStore.host(oldUrl);
        String oldCore = coreOf(oldHost);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        // 型 1：encodedData（x99dh 系导航站：base64 → urldecode → JSON 站点列表）
        Matcher m = ENCODED.matcher(html);
        if (m.find()) {
            try {
                String enc = m.group(1);
                StringBuilder pad = new StringBuilder(enc);
                while (pad.length() % 4 != 0) pad.append('=');
                String json = URLDecoder.decode(new String(Base64.decode(pad.toString(), Base64.DEFAULT), StandardCharsets.UTF_8), "UTF-8");
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item == null) continue;
                    String ename = item.optString("name", "");
                    JSONArray urls = item.optJSONArray("urls");
                    if (urls == null) continue;
                    // 同族判据（三重任一）：源名互含 / oldHost 核心词互含 / 条目 urls host 互含
                    if (!kin(srcName, ename) && !kin(oldCore, ename) && !anyHostKin(urls, oldHost)) continue;
                    for (int j = 0; j < urls.length(); j++) {
                        JSONObject u = urls.optJSONObject(j);
                        if (u == null) continue;
                        String link = stripEnter(u.optString("url", "").trim());
                        String h = WebSourceStore.host(link);
                        // r351：排除自身 host —— missav09.com 就列在 MissAV 条目 urls 里，自愈不能换回自己
                        if (!link.startsWith("http") || TextUtils.isEmpty(h) || h.contains(pubHost) || h.equals(oldHost)) continue;
                        seen.add(link);
                    }
                }
                if (!seen.isEmpty()) {
                    // r351：path 分区感知 —— 同族判据可能命中多个分区条目（x99dh 的 LissAV 与 LissAV国产区
                    // 是两个条目：urls 同域名不同 path）。与 oldUrl path 首段相同的候选优先，防自愈换分区。
                    String oldPath = pathOf(oldUrl);
                    List<String> sorted = new ArrayList<>(seen);
                    sorted.sort((a, b) -> Boolean.compare(!pathOf(a).equals(oldPath), !pathOf(b).equals(oldPath)));
                    out.cands.addAll(sorted);
                    return out;
                }
            } catch (Throwable ignored) {
            }
        }
        // 型 2：HTML 链接兜底（通用发布页：站群导航 / 项目主页）
        Matcher h = HREF.matcher(html);
        while (h.find()) {
            String link = h.group(1).trim();
            String host = WebSourceStore.host(link);
            if (TextUtils.isEmpty(host) || host.equals(pubHost) || host.contains(pubHost) || host.equals(oldHost)) continue;
            if (!kin(oldCore, host) && !kin(srcName, host)) continue;
            seen.add(origin(link));
        }
        out.cands.addAll(seen);
        if (out.cands.isEmpty()) out.fail = "发布页无同族条目";
        return out;
    }

    /** r351：提取结果容器（extract 返回）。 */
    static class Extract {
        final List<String> cands = new ArrayList<>();
        String fail = "";
    }

    /** r351：url 的 path 首段（host 后第一段，如 /cn → cn、/asian/zh-CN → asian、/dm247/cn → dm247）。 */
    private static String pathOf(String url) {
        String t = url == null ? "" : url.trim();
        int start = t.indexOf("://");
        if (start >= 0) t = t.substring(start + 3);
        int slash = t.indexOf('/');
        if (slash < 0) return "";
        t = t.substring(slash + 1);
        int end = t.indexOf('/');
        return (end >= 0 ? t.substring(0, end) : t).toLowerCase(Locale.ROOT);
    }

    /** 自愈探活：body > 5000 字节才换域名（thisav66.com origin 500+2563B 错误页必须判死）。 */
    static boolean alive(String url) {
        try (Response res = OkHttp.client(6000).newCall(new Request.Builder().url(url)
                .header("User-Agent", UA).build()).execute()) {
            ResponseBody b = res.body();
            if (b == null) return false;
            return b.bytes().length > 5000;
        } catch (IOException e) {
            return false;
        }
    }

    /** 写回：Prefers 落盘（updateUrl 迁移发布页 + 清失效标记）+ 内存 SourceBean 同步（免重启生效）。
     * （AVBox 移植版：原 VodConfig/Site 改为 ApiConfig/SourceBean。） */
    private static void apply(String oldUrl, String newUrl, String name, String group) {
        boolean ok = WebSourceStore.updateUrl(oldUrl, newUrl, name, group);
        try {
            String oh = WebSourceStore.host(oldUrl);
            for (SourceBean site : ApiConfig.get().getSourceBeanList()) {
                if (WebSourceStore.host(site.getExt()).equals(oh)) {
                    site.setExt(WebSourceStore.normalize(newUrl));
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        DebugLogStore.add(TAG, (ok ? "自愈成功 " : "自愈落盘失败 ") + WebSourceStore.host(oldUrl) + " → " + newUrl);
    }

    /** GET 抓 body：OkHttp 对非 2xx 不抛异常，body 正常可读（发布页 500 + 完整 body 的关键）。 */
    private static String body(String url, int timeout) {
        try (Response res = OkHttp.client(timeout).newCall(new Request.Builder().url(url)
                .header("User-Agent", UA).build()).execute()) {
            ResponseBody b = res.body();
            return b == null ? "" : new String(b.bytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 条目 urls 里是否存在与 oldHost 互含的域名（thisav66 ↔ MissAV 条目的反查定位）。 */
    private static boolean anyHostKin(JSONArray urls, String oldHost) {
        for (int j = 0; j < urls.length(); j++) {
            JSONObject u = urls.optJSONObject(j);
            if (u != null && kin(oldHost, WebSourceStore.host(u.optString("url", "")))) return true;
        }
        return false;
    }

    /** 同族：小写互含。 */
    static boolean kin(String a, String b) {
        String x = a == null ? "" : a.trim().toLowerCase(Locale.ROOT);
        String y = b == null ? "" : b.trim().toLowerCase(Locale.ROOT);
        return !x.isEmpty() && !y.isEmpty() && (x.contains(y) || y.contains(x));
    }

    /** oldHost 核心词：去 www. 前缀，取第一个「长度>=3 且非 TLD」段（thisav66/jable1/missav09 含数字段才是核心词）。 */
    static String coreOf(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        for (String seg : h.split("\\.")) {
            if (seg.length() >= 3 && !TLDS.contains(seg)) return seg;
        }
        String[] parts = h.split("\\.");
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "";
    }

    private static String stripEnter(String url) {
        return url.endsWith("/enter") ? url.substring(0, url.length() - "/enter".length()) : url;
    }

    private static String origin(String url) {
        int end = url.indexOf('/', url.indexOf("://") + 3);
        return end > 0 ? url.substring(0, end) : url;
    }
}
