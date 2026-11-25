package com.github.tvbox.osc.api.loader;

import android.text.TextUtils;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 3.34 网页源：浏览器模式小说虚拟源（移植自昏黄 SiteApi.BROWSER_NOVEL）。
 *
 * <p>BrowserActivity 抽到整本章节列表（「第N章」锚 ≥2）时，章节列表由阅读器直接持有；
 * 本 spider 只做「按章节页 URL 按需回源」：playerContent 经 WebSpider.novelPagePayload
 * 抽正文（目录页防御 + 链接密度反杂质 + 章节分页拼接），产出 novel:// 载荷，parse=0，
 * novel:// 只进阅读器不进播放器。与 BROWSER_PLAY（视频/图集直投）分离，起播头、队列语义互不影响。
 */
public class BrowserNovelSpider extends Spider {

    public static final String KEY = "browser_novel";

    /** 日志截断（AVBox 的 CatVod Util 没有 substring(String, int)；解析器 Utils.substring 是去尾不是截断，不能用）。 */
    private static String sub(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = (ids == null || ids.isEmpty()) ? "" : ids.get(0);
        try {
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", id);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "网页");
            vod.put("vod_play_url", "正文$" + id);
            JSONArray list = new JSONArray();
            list.put(vod);
            return new JSONObject().put("list", list).toString();
        } catch (Throwable e) {
            return "{\"list\":[]}";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String payload = null;
        try {
            payload = new WebSpider().novelPagePayload(id);
        } catch (Throwable t) {
            DebugLogStore.add("player", "browser_novel 回源失败 " + id + " -> " + t);
        }
        try {
            JSONObject r = new JSONObject();
            r.put("parse", 0);
            r.put("url", TextUtils.isEmpty(payload) ? "" : payload);
            DebugLogStore.add("player", "browser_novel 回源 " + sub(id, 80)
                    + " -> " + (TextUtils.isEmpty(payload) ? "空（阅读器显示加载失败）" : "novel:// 载荷 " + payload.length() + " 字"));
            return r.toString();
        } catch (Throwable e) {
            return "{\"parse\":0,\"url\":\"\"}";
        }
    }
}
