package com.github.tvbox.osc.ui.page

import com.github.tvbox.osc.util.FloatLog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.tvbox.osc.ui.activity.BrowserActivity
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.ui.activity.SearchActivity
import org.json.JSONObject

/**
 * 点片直达播放（无详情页 UI）。
 * 仍走 DetailActivity + 昏黄 Fongmi 内核，但 purePlayer=true 时不展示详情布局。
 */
fun Context.jumpToDetail(
    id: String?,
    sourceKey: String?,
    title: String?,
    picture: String?,
    collect: Boolean = false,
    autoPlay: Boolean = true,
    purePlayer: Boolean = true,
) {
    // browser 动作卡：点开直接进 WebView 页面，全程不碰详情与播放器（蜘蛛契约）。
    // r475（一源两用）：卡里带 candidates（候选镜像列表）时一并传进 BrowserActivity 做失败轮换。
    parseBrowserTarget(id)?.let { (url, browserTitle, candidates) ->
        FloatLog.append("app", null, "jumpToBrowser url=$url sourceKey=$sourceKey cand=${candidates.size}")
        val intent = Intent(this, BrowserActivity::class.java)
        intent.putExtra("url", url)
        intent.putExtra("title", browserTitle ?: title)
        if (!candidates.isEmpty()) intent.putStringArrayListExtra("candidates", ArrayList(candidates))
        startActivity(intent)
        return
    }
    val bundle = Bundle()
    bundle.putString("id", id.orEmpty())
    bundle.putString("sourceKey", sourceKey.orEmpty())
    bundle.putString("title", title)
    bundle.putString("picture", picture)
    bundle.putBoolean("collect", collect)
    bundle.putBoolean("autoPlay", autoPlay)
    bundle.putBoolean("purePlayer", purePlayer)
    FloatLog.append("app", null, "jumpToDetail autoPlay=$autoPlay purePlayer=$purePlayer sourceKey=$sourceKey")
    startActivity(Intent(this, DetailActivity::class.java).putExtras(bundle))
}

/** vod_id 为 {"type":"browser","url":...,"candidates":[...]} 时返回 url、标题与候选镜像列表，否则返回 null。 */
private fun parseBrowserTarget(id: String?): Triple<String, String?, List<String>>? {
    val raw = id?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        val obj = JSONObject(raw)
        if (obj.optString("type") != "browser") return null
        val url = obj.optString("url")
        if (url.isEmpty()) return null
        val cands = mutableListOf<String>()
        // r475：解析候选镜像列表（失败轮换用）；无该键时为空列表，不阻塞
        if (obj.has("candidates") && obj.get("candidates") is org.json.JSONArray) {
            val arr = obj.getJSONArray("candidates")
            for (i in 0 until arr.length()) {
                val c = arr.optString(i)
                if (!c.isNullOrEmpty()) cands.add(c)
            }
        }
        Triple(url, obj.optString("title").takeIf { it.isNotEmpty() }, cands)
    } catch (_: Exception) {
        null
    }
}

/** 需要详情页时显式调用（例如从设置/调试） */
fun Context.jumpToDetailPage(
    id: String?,
    sourceKey: String?,
    title: String?,
    picture: String?,
    collect: Boolean = false,
) {
    jumpToDetail(id, sourceKey, title, picture, collect, autoPlay = false, purePlayer = false)
}

fun Context.jumpToSearch(title: String) {
    val bundle = Bundle()
    bundle.putString("title", title)
    startActivity(Intent(this, SearchActivity::class.java).putExtras(bundle))
}
