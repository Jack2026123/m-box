package com.github.tvbox.osc.music

import com.github.tvbox.osc.util.LOG
import org.json.JSONObject

/**
 * MusicFree 插件返回值 → 我们的数据模型。
 *
 * 插件返回的一首歌（本体 TrackItem 的松散版）：
 *
 *     {
 *       "id": "...", "title": "歌名", "artist": "歌手",
 *       "album": "...", "artwork": "...", "duration": 240,
 *       "platform": "qq",
 *       ...插件自定义字段（音质 hash 之类都在这里面）
 *     }
 *
 * **关键**：整个对象必须原样留着，取链时回传给插件。
 * 插件凭什么认得出这首歌，就在那些自定义字段里。
 */
object MusicFreeMapper {

    /** MusicFree 本体的音质档位名 */
    val QUALITIES = listOf("super", "high", "standard", "low")

    fun parseSearch(text: String, platform: String, sourceName: String): MusicSearchPage {
        return try {
            val root = JSONObject(text)
            if (root.has("error")) {
                LOG.e("mf-search-plugin-error : " + root.optString("error"))
                return MusicSearchPage(emptyList(), true)
            }
            val isEnd = root.optBoolean("isEnd", true)
            val arr = root.optJSONArray("data") ?: return MusicSearchPage(emptyList(), true)
            val songs = ArrayList<MusicSong>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                songs.add(toSong(item, platform, sourceName))
            }
            MusicSearchPage(songs, isEnd)
        } catch (th: Throwable) {
            LOG.e("mf-search-parse-failed : " + th.message)
            MusicSearchPage(emptyList(), true)
        }
    }

    private fun toSong(item: JSONObject, platform: String, sourceName: String): MusicSong {
        val id = item.optString("id").ifBlank {
            (item.optString("title") + "@" + item.optString("artist")).hashCode().toString()
        }
        val raw = item.opt("duration")
        val duration = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }
        // 有些插件 duration 给毫秒
        val seconds = if (duration > 10_000) duration / 1000 else duration

        return MusicSong(
            id = id,
            title = item.optString("title").ifBlank { "未知歌曲" },
            artist = item.optString("artist"),
            album = item.optString("album"),
            artwork = item.optString("artwork"),
            durationSec = seconds,
            platform = item.optString("platform").ifBlank { platform },
            sourceId = "",
            sourceName = sourceName,
            qualities = QUALITIES,
            // 整个对象原样带上，取链时回传
            extra = item,
        )
    }

    fun parseMedia(text: String): MusicMedia? {
        return try {
            val o = JSONObject(text)
            if (o.has("error")) {
                LOG.e("mf-media-plugin-error : " + o.optString("error"))
                return null
            }
            val url = o.optString("url")
            if (url.isBlank()) return null
            val headers = LinkedHashMap<String, String>()
            o.optJSONObject("headers")?.let { h ->
                val keys = h.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headers[k] = h.optString(k)
                }
            }
            MusicMedia(url = url, headers = headers, quality = o.optString("quality"))
        } catch (th: Throwable) {
            LOG.e("mf-media-parse-failed : " + th.message)
            null
        }
    }
}