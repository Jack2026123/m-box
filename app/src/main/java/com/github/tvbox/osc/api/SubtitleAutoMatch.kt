package com.github.tvbox.osc.api

import android.text.TextUtils
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 1.45 在线字幕自动匹配决策层（按 1.0.4 SubtitleAuto）：
// 开播字幕初始化时一次、片名清洗、相关度≥2 才挂、同片 6 小时只试一次、
// 挂不上不打断播放、命中 Toast（由调用方弹）。渲染沿用魔改现行字幕视图。
object SubtitleAutoMatch {

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private const val RETRY_GAP_MS = 6 * 60 * 60 * 1000L
private const val LAST_KEY_PREFIX = "subtitle_auto_last_"

fun cleanTitle(title: String?): String {
if (TextUtils.isEmpty(title)) return ""
var text = title!!
text = text.replace(Regex("(?i)\\bS\\d{1,2}E\\d{1,3}\\b"), " ")
text = text.replace(Regex("(?i)\\bE[Pp]?\\d{1,4}\\b"), " ")
text = text.replace(Regex("第\\s*[0-9一二三四五六七八九十百零]+\\s*[集话話期]"), " ")
text = text.replace(Regex("(?i)\\b(4K|8K|1080P|720P|2160P|HDR|BluRay|WEB-?DL|HDTV|HEVC|H\\.?264|H\\.?265|AAC|DDP?5\\.1)\\b"), " ")
text = text.replace(Regex("[\\[)）]{0,12}[\\]】)）]"), " ")
text = text.replace(Regex("(?i)\\.(mp4|mkv|avi|ts|m3u8|flv|rmvb|mov|wmv)$"), " ")
text = text.replace(Regex("[-_·|/\\\\]+"), " ")
return text.replace(Regex("\\s+"), " ").trim()
}

// 集初始化时调用一次；onAttach(path, name) 在主线程回，调用方负责挂字幕与 Toast。
fun maybeLoad(title: String?, onAttach: (String, String) -> Unit) {
val rawTitle = title?: return
if (!SubtitleSourceApi.isAutoMatch()) return
if (!SubtitleSourceApi.ready()) {
LOG.i("SubtitleAuto 自动字幕跳过：当前源未配置（" + SubtitleSourceApi.getSource() + "）")
return
}
val keyword = cleanTitle(rawTitle)
if (keyword.length < 2) return
val lastKey = LAST_KEY_PREFIX + Math.abs(keyword.hashCode())
val last = KV.get(lastKey, 0L)
val now = System.currentTimeMillis()
if (last > 0 && now - last < RETRY_GAP_MS) return
KV.put(lastKey, now)
scope.launch {
try {
val items = withContext(Dispatchers.IO) { SubtitleSourceApi.search(keyword, 1)}
val relevant = items.filter { SubtitleSourceApi.relevance(it.name, keyword) >= 2}
if (relevant.isEmpty()) {
LOG.i("SubtitleAuto 自动字幕无匹配：片名=" + keyword + " 候选=" + items.size)
return@launch
}
val prefLang = SubtitleSourceApi.getAutoLang()
val best = relevant.firstOrNull {!prefLang.isEmpty() && it.lang.contains(prefLang)}?: relevant[0]
val withFiles = if (SubtitleSourceApi.isXunlei()) {
listOf(best)
} else {
withContext(Dispatchers.IO) { SubtitleSourceApi.detailFiles(best)}
}
val target = withFiles.firstOrNull()?: return@launch
val local = withContext(Dispatchers.IO) { SubtitleSourceApi.downloadToLocal(target)}
val path = local?: target.url
if (path.isEmpty()) return@launch
onAttach(path, target.name)
} catch (e: Throwable) {
LOG.e("SubtitleAuto", e)
}
}
}
}
