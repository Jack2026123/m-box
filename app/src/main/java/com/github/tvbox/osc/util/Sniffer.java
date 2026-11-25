package com.github.tvbox.osc.util;

import android.net.Uri;
import android.text.TextUtils;

import com.github.tvbox.osc.api.loader.RuleConfig;
import com.github.tvbox.osc.bean.Rule;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频地址嗅探（从 muse-build 移植，P0-1）。
 * 判定一个 URL 是不是视频直链：先按分站规则（RuleConfig 的 hosts/regex/script/exclude），
 * 再按通用视频后缀/特征。getScript 取分站要跑的 JS。
 *
 * muse 原版在 com.muse.android.tv.utils.Sniffer；包名改成 AVBox。
 * buildClickable 没搬（它要 muse 的 bean/Result 做可点击文本，AVBox 的网页核用不上）。
 * UrlUtil.uri/host（muse 的工具）内联成下面两个私有方法；Json.isObj（AVBox 的 catvod 里没有）
 * 内联成 isObj。
 */
public class Sniffer {

    public static final Pattern AI_PUSH = Pattern.compile("(https?|magnet|ed2k|video):\\S+");
    public static final Pattern SNIFFER = Pattern.compile("https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\\?.*)?|https?://.*?video/tos[^\\s]*|rtmp:[^\\s]+");

    public static String getUrl(String text) {
        if (isObj(text) || text.contains("$")) return text;
        Matcher m = AI_PUSH.matcher(text);
        if (m.find()) return m.group(0);
        return text;
    }

    public static boolean isVideoFormat(String url) {
        Rule rule = getRule(uri(url));
        for (String exclude : rule.getExclude()) if (url.contains(exclude)) return false;
        for (String exclude : rule.getExclude()) if (Pattern.compile(exclude).matcher(url).find()) return false;
        for (String regex : rule.getRegex()) if (url.contains(regex)) return true;
        for (String regex : rule.getRegex()) if (Pattern.compile(regex).matcher(url).find()) return true;
        if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false;
        return SNIFFER.matcher(url).find();
    }

    public static List<String> getScript(Uri uri) {
        return getRule(uri).getScript();
    }

    private static Rule getRule(Uri uri) {
        if (uri.getHost() == null) return Rule.empty();
        String hosts = TextUtils.join(",", Arrays.asList(host(uri), host(uri.getQueryParameter("url"))));
        for (Rule rule : RuleConfig.get().getRules()) for (String host : rule.getHosts()) if (RuleConfig.containOrMatch(hosts, host)) return rule;
        return Rule.empty();
    }

    /** muse UrlUtil.uri 内联：去反斜杠，"/" 开头当本地文件，否则当网址。 */
    private static Uri uri(String url) {
        String u = url.trim().replace("\\", "");
        return u.startsWith("/") ? Uri.fromFile(new File(u)) : Uri.parse(u);
    }

    /** muse UrlUtil.host(Uri) 内联。 */
    private static String host(Uri uri) {
        String h = uri.getHost();
        return h == null ? "" : h.toLowerCase().trim();
    }

    /** muse UrlUtil.host(String) 内联。 */
    private static String host(String url) {
        if (url == null) return "";
        try {
            return host(uri(url));
        } catch (Exception ignored) {
            return "";
        }
    }

    /** catvod Json.isObj 内联：是不是 JSON 对象文本。 */
    private static boolean isObj(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (!t.startsWith("{")) return false;
        try {
            return JsonParser.parseString(t).isJsonObject();
        } catch (Exception ignored) {
            return false;
        }
    }
}
