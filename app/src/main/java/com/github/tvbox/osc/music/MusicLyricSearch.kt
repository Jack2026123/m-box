package com.github.tvbox.osc.music

import com.github.tvbox.osc.util.KV
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
* 歌词搜索 + 按歌锁定 + LRCLIB 兜底（2.95）。
*
* - 搜歌词面板的候选：平台链（网易→QQ→酷狗，见 MusicRepository.searchLyricCandidates）
* + LRCLIB 兜底（这里）；逐词优先。
* - 手动选的歌词按 songKey 存 KV 锁定，锁定后自动拉取不再覆盖；换歌重新拉。
* - 导入的歌词同样按 songKey 缓存，优先于自动拉取。
* - 纯音乐标记：标记后不再反复拉取，播放页显示“纯音乐，请欣赏”。
* - LRCLIB 免费免 key：/api/get 精确（带 duration 秒）+ /api/search 模糊。
*/
object MusicLyricSearch {

data class LyricCandidate(
val title: String,
val artist: String,
val source: String,
val durationSec: Int,
val wordTimed: Boolean,
val preview: String,
val lrc: String,
) {
    val durationText: String
        get() = if (durationSec <= 0) "" else "%d:%02d".format(durationSec / 60, durationSec % 60)
}

// ============================================================ 按歌锁定

object Lock {
private const val PREFIX_LOCKED = "music_lyric_locked_v2_"
private const val PREFIX_LOCKED_SRC = "music_lyric_locked_src_v2_"
private const val PREFIX_INSTRUMENTAL = "music_lyric_instrumental_v2_"

fun getLocked(songKey: String): String? {
if (songKey.isBlank()) return null
return KV.get(PREFIX_LOCKED + songKey, "").takeIf { it.isNotBlank()}
}

fun lockedSource(songKey: String): String {
if (songKey.isBlank()) return ""
return KV.get(PREFIX_LOCKED_SRC + songKey, "")
}

fun hasLocked(songKey: String): Boolean =!getLocked(songKey).isNullOrBlank()

fun saveLocked(songKey: String, lrc: String, source: String) {
if (songKey.isBlank() || lrc.isBlank()) return
KV.put(PREFIX_LOCKED + songKey, lrc)
KV.put(PREFIX_LOCKED_SRC + songKey, source)
KV.delete(PREFIX_INSTRUMENTAL + songKey)
}

fun clearLocked(songKey: String) {
if (songKey.isBlank()) return
KV.delete(PREFIX_LOCKED + songKey)
KV.delete(PREFIX_LOCKED_SRC + songKey)
}

fun isInstrumental(songKey: String): Boolean {
if (songKey.isBlank()) return false
return KV.get(PREFIX_INSTRUMENTAL + songKey, false)
}

fun setInstrumental(songKey: String, value: Boolean) {
if (songKey.isBlank()) return
if (value) {
KV.put(PREFIX_INSTRUMENTAL + songKey, true)
KV.delete(PREFIX_LOCKED + songKey)
KV.delete(PREFIX_LOCKED_SRC + songKey)
} else {
KV.delete(PREFIX_INSTRUMENTAL + songKey)
}
}
}

// ============================================================ LRCLIB

data class LrclibHit(
val text: String?,
val instrumental: Boolean,
val synced: Boolean,
)

/** 自动拉取的 LRCLIB 兜底：精确 /api/get（带 duration）→ 模糊 /api/search 取时长最接近的一条。 */
fun fetchLrclibLyric(song: MusicSong): LrclibHit {
val exact = lrclibGet(song)
if (exact!= null) return exact
return lrclibSearchBest(song)?: LrclibHit(null, false, false)
}

private fun lrclibGet(song: MusicSong): LrclibHit? {
val url = "https://lrclib.net/api/get?artist_name=" + URLEncoder.encode(song.artist, "UTF-8") +
"&track_name=" + URLEncoder.encode(song.title, "UTF-8") +
"&album_name=" + URLEncoder.encode(song.album, "UTF-8") +
"&duration=" + song.durationSec
val body = httpGet(url)
if (body.isBlank()) return null
val json = runCatching { JSONObject(body)}.getOrNull()?: return null
if (json.optInt("statusCode", 0) == 404) return null
if (json.optString("trackName").isBlank() && json.optString("name").isBlank()) return null
return lrclibHitFrom(json, song)
}

private fun lrclibSearchBest(song: MusicSong): LrclibHit? {
val q = URLEncoder.encode(song.title + " " + song.artist, "UTF-8")
val body = httpGet("https://lrclib.net/api/search?q=" + q)
if (body.isBlank()) return null
val arr = runCatching { JSONArray(body)}.getOrNull()?: return null
var best: JSONObject? = null
var bestScore = Long.MAX_VALUE
for (i in 0 until arr.length()) {
val item = arr.optJSONObject(i)?: continue
val titleScore = if (sameName(item.optString("trackName"), song.title)) 0L else 1_000_000L
val durScore = Math.abs(item.optDouble("duration", 0.0).toLong() - song.durationSec.toLong()) * 10L
val score = titleScore + durScore
if (score < bestScore) {
bestScore = score
best = item
}
}
val picked = best?: return null
return lrclibHitFrom(picked, song)
}

private fun lrclibHitFrom(json: JSONObject, song: MusicSong): LrclibHit {
if (json.optBoolean("instrumental", false)) return LrclibHit(null, true, false)
val synced = json.optString("syncedLyrics").orEmpty()
if (synced.isNotBlank()) return LrclibHit(synced, false, true)
val plain = json.optString("plainLyrics").orEmpty()
if (plain.isBlank()) return LrclibHit(null, false, false)
return LrclibHit(toTimedLrc(plain, song.durationSec), false, false)
}

/** 搜歌词面板的 LRCLIB 候选（模糊搜前 5 条，有词优先）。 */
fun fetchLrclibCandidates(song: MusicSong, queryTitle: String, queryArtist: String): List<LyricCandidate> {
val out = ArrayList<LyricCandidate>()
val q = URLEncoder.encode(queryTitle + " " + queryArtist, "UTF-8")
val body = httpGet("https://lrclib.net/api/search?q=" + q)
if (body.isBlank()) return out
val arr = runCatching { JSONArray(body)}.getOrNull()?: return out
for (i in 0 until minOf(arr.length(), 5)) {
val item = arr.optJSONObject(i)?: continue
val title = item.optString("trackName")
val artist = item.optString("artistName")
if (title.isBlank()) continue
val hit = lrclibHitFrom(item, song)
if (hit.instrumental || hit.text.isNullOrBlank()) continue
out.add(
LyricCandidate(
title = title,
artist = artist,
source = "LRCLIB",
durationSec = item.optDouble("duration", 0.0).toInt(),
wordTimed = item.optBoolean("hasWordSync", false) || isWordTimed(hit.text),
preview = previewOf(hit.text),
lrc = hit.text,
),
)
}
return out
}

// ============================================================ 工具

/** 纯文本歌词（无时间标签）转带时间的 LRC：按行均匀铺到整首歌时长上（没时长就每行 4 秒），只为能滚动显示，不保证逐行对上唱。 */
fun toTimedLrc(plain: String, durationSec: Int): String {
val lines = plain.lineSequence().map { it.trim()}.filter { it.isNotEmpty()}.toList()
if (lines.isEmpty()) return ""
val totalMs = if (durationSec > 0) durationSec * 1000L else lines.size * 4000L
val step = (totalMs / lines.size).coerceAtLeast(1000L)
val sb = StringBuilder()
for ((index, line) in lines.withIndex()) {
sb.append("[").append(formatLrcTime(index * step)).append("]").append(line).append("\n")
}
return sb.toString()
}

private fun formatLrcTime(ms: Long): String {
val totalCs = ms / 10
val mm = totalCs / 6000
val ss = (totalCs % 6000) / 100
val cs = totalCs % 100
return String.format("%02d:%02d.%02d", mm, ss, cs)
}

/** 候选预览：去时间标签后的前两行唱词。 */
fun previewOf(lrc: String): String {
val tag = Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]|<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
return lrc.lineSequence()
.map { tag.replace(it, "").trim()}
.filter { it.isNotEmpty()}
.take(2)
.joinToString("\n")
}

/** 是否带逐词时间标签（<> 词标签）。 */
fun isWordTimed(lrc: String): Boolean =
Regex("<\\d{1,3}:\\d{1,2}").containsMatchIn(lrc)

private fun sameName(a: String, b: String): Boolean {
if (a.isBlank() || b.isBlank()) return false
fun norm(s: String) = s.lowercase().replace(Regex("[\\s\\-_()（）\\[\\]]"), "")
val x = norm(a)
val y = norm(b)
return x == y || x.contains(y) || y.contains(x)
}

private const val UA =
"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

private fun httpGet(url: String): String {
val client = com.github.tvbox.osc.net.OkGoHelper.getDefaultClient()?: return ""
val builder = okhttp3.Request.Builder()
.url(url)
.header("User-Agent", UA)
return runCatching {
client.newCall(builder.build()).execute().use { it.body?.string().orEmpty()}
}.getOrDefault("")
}
}
