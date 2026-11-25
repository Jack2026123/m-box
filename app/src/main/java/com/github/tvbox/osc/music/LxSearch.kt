package com.github.tvbox.osc.music

import com.github.tvbox.osc.util.LOG
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * 洛雪音源搜歌。
 *
 * ⚠ 重要：搜歌不在洛雪脚本里。洛雪本体是「壳调平台接口搜歌，
 * 搜到后把 musicInfo 交给脚本取链」。所以这里必须自己实现各平台搜索。
 *
 * 每首歌的 MusicSong.extra 必须是**洛雪原生 musicInfo**，脚本才认得出。
 * 字段不对 = 能搜到但播不出。
 */
object LxSearch {

    private const val PAGE_SIZE = 30

    private val NOISE_SUFFIX = listOf("(伴奏)", "（伴奏）", "(DJ版)", "(Live)")

    fun search(
        runtime: LxRuntime?,
        platform: String,
        keyword: String,
        page: Int,
        sourceId: String,
        sourceName: String,
    ): MusicSearchPage {
        val platforms = runtime?.sourcePlatforms?.toList().orEmpty()
        val target = platform.ifBlank { platforms.firstOrNull() ?: "kg" }

        if (platforms.isNotEmpty() && target !in platforms) {
            LOG.i("lx-search-skip platform=" + target + " not in " + platforms.joinToString(","))
            return MusicSearchPage(emptyList(), true)
        }
        val qualities = runtime?.qualityMap?.get(target).orEmpty()

        return try {
            val songs = when (target) {
                "kg" -> searchKugou(keyword, page, qualities, sourceId, sourceName)
                "kw" -> searchKuwo(keyword, page, qualities, sourceId, sourceName)
                "mg" -> searchMigu(keyword, page, qualities, sourceId, sourceName)
                "tx" -> searchQQ(keyword, page, qualities, sourceId, sourceName)
                "wy" -> searchNetease(keyword, page, qualities, sourceId, sourceName)
                else -> emptyList()
            }
            MusicSearchPage(songs, songs.size < PAGE_SIZE)
        } catch (th: Throwable) {
            LOG.e("lx-search-failed " + target + " : " + th.message)
            MusicSearchPage(emptyList(), true)
        }
    }

    // ============================================================ 酷狗

    private fun searchKugou(
        keyword: String,
        page: Int,
        qualities: List<String>,
        sourceId: String,
        sourceName: String,
    ): List<MusicSong> {
        val url = "https://songsearch.kugou.com/song_search_v2" +
            "?keyword=" + URLEncoder.encode(keyword, "UTF-8") +
            "&page=" + page +
            "&pagesize=" + PAGE_SIZE +
            "&platform=WebFilter&userid=-1&clientver=2000&iscorrection=1&privilege_filter=0"
        val body = httpGet(url, mapOf("User-Agent" to UA_PC))
        val json = JSONObject(body)
        val lists = json.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()

        val songs = ArrayList<MusicSong>(lists.length())
        val seen = HashSet<String>()
        for (i in 0 until lists.length()) {
            val item = lists.optJSONObject(i) ?: continue
            val audioId = item.optString("Audioid")
            val hash = item.optString("FileHash")
            if (audioId.isEmpty() || hash.isEmpty()) continue
            if (!seen.add(audioId)) continue

            val extra = JSONObject()
            extra.put("songmid", audioId)
            extra.put("songId", audioId)
            extra.put("audioId", audioId)
            extra.put("id", audioId)
            extra.put("hash", hash)
            extra.put("strMediaMid", item.optString("FileHash"))
            extra.put("source", "kg")
            extra.put("name", item.optString("SongName"))
            extra.put("singer", item.optString("SingerName"))
            extra.put("albumName", item.optString("AlbumName"))
            extra.put("albumId", item.optString("AlbumID"))
            extra.put("interval", item.optString("Duration"))
            extra.put("img", item.optString("Image"))

            // 分档 hash 表：脚本取链时按音质取对应 hash，缺了就播不出
            val types = JSONArray()
            val typesMap = JSONObject()
            fun addType(type: String, h: String) {
                if (h.isEmpty()) return
                types.put(type)
                typesMap.put(type, h)
            }
            addType("128k", hash)
            addType("320k", item.optString("HQFileHash"))
            addType("flac", item.optString("SQFileHash"))
            addType("flac24bit", item.optString("ResFileHash"))
            extra.put("types", types)
            extra.put("_types", typesMap)

            songs.add(
                MusicSong(
                    id = audioId,
                    title = clean(item.optString("SongName")),
                    artist = item.optString("SingerName"),
                    album = item.optString("AlbumName"),
                    artwork = item.optString("Image").replace("/{size}/", "/400/"),
                    durationSec = item.optInt("Duration", 0),
                    platform = "kg",
                    sourceId = sourceId,
                    sourceName = sourceName,
                    qualities = qualities,
                    extra = extra,
                ),
            )
        }
        return songs
    }

    // ============================================================ 酷我

    private fun searchKuwo(
        keyword: String,
        page: Int,
        qualities: List<String>,
        sourceId: String,
        sourceName: String,
    ): List<MusicSong> {
        val url = "http://search.kuwo.cn/r.s" +
            "?all=" + URLEncoder.encode(keyword, "UTF-8") +
            "&ft=music&itemset=web_2013&client=kt" +
            "&pn=" + (page - 1) * PAGE_SIZE + "&rn=" + PAGE_SIZE +
            "&rformat=json&encoding=utf8&show_copyright_off=1&vipver=1"
        val body = httpGet(url, mapOf("User-Agent" to UA_PC))
        val json = JSONObject(fixKuwoJson(body))
        val list = json.optJSONArray("abslist") ?: return emptyList()

        val songs = ArrayList<MusicSong>(list.length())
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val rid = item.optString("MUSICRID").replace("MUSIC_", "")
            if (rid.isEmpty() || !seen.add(rid)) continue

            val minfo = item.optString("N_MINFO")
            val extra = JSONObject()
            extra.put("songmid", rid)
            extra.put("songId", rid)
            extra.put("rid", rid)
            extra.put("id", rid)
            extra.put("source", "kw")
            extra.put("name", item.optString("SONGNAME"))
            extra.put("singer", item.optString("ARTIST"))
            extra.put("albumName", item.optString("ALBUM"))
            extra.put("albumId", item.optString("ALBUMID"))
            extra.put("_qualitys", minfo)

            val types = JSONArray()
            val typesMap = JSONObject()
            if (minfo.contains("bitrate:320")) {
                types.put("320k")
                typesMap.put("320k", "")
            }
            types.put("128k")
            typesMap.put("128k", "")
            extra.put("types", types)
            extra.put("_types", typesMap)

            songs.add(
                MusicSong(
                    id = rid,
                    title = clean(item.optString("SONGNAME")),
                    artist = item.optString("ARTIST"),
                    album = item.optString("ALBUM"),
                    artwork = item.optString("web_albumpic_short")
                        .takeIf { it.isNotBlank() }
                        ?.let { "https://img2.kuwo.cn/star/albumcover/" + it.replace("120/", "500/") }
                        .orEmpty(),
                    durationSec = item.optInt("DURATION", 0),
                    platform = "kw",
                    sourceId = sourceId,
                    sourceName = sourceName,
                    qualities = qualities,
                    extra = extra,
                ),
            )
        }
        return songs
    }

    /** 酷我老接口返回单引号 JSON，转标准格式 */
    private fun fixKuwoJson(raw: String): String {
        var out = raw.trim()
        // 单引号 -> 双引号
        out = out.replace('\'', '"')
        // 裸 key 加引号：key: -> "key":
        val keyPattern = Pattern.compile("([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:")
        out = keyPattern.matcher(out).replaceAll("$1\"$2\":")
        // 去掉尾随逗号：,} / ,]
        val trailPattern = Pattern.compile(",(\\s*[}\\]]])")
        out = trailPattern.matcher(out).replaceAll("$1")
        // ------------------------------------------------------------------
        // 下面这条是酷我能搜到歌但解析必挂的真正原因。
        //
        // 酷我 API 的 MINFO 字段把二级 JSON 直接塞进字符串值里，而且不转义引号：
        //   "MINFO":"level:ff,"bitrate":2000,"format":flac,"size":23.47Mb;..."
        // 标准 JSON 解析器读到第一个内层 " 就认为字符串结束，
        // 于是报 Unterminated object at character 518（长度随返回内容变化）。
        //
        // 原版洛雪客户端能播，是因为它的脚本用宽松解析；我们走 org.json，
        // 必须先把这些内层引号转成单引号（值本身只拿来显示，不参与取链）。
        // ------------------------------------------------------------------
        out = escapeQuotesInsideField(out, "MINFO")
        out = escapeQuotesInsideField(out, "FORMATS")
        return out
    }

    /**
     * 把某个字段值内部的裸双引号替换成单引号。
     *
     * 只处理 field 之后的那一段字符串值，不动结构上的引号。
     * 例：  "MINFO":"level:ff,"bitrate":2000"   ->   "MINFO":"level:ff,'bitrate':2000"
     */
    private fun escapeQuotesInsideField(json: String, field: String): String {
        val marker = "\"" + field + "\":\""
        var searchFrom = 0
        val sb = StringBuilder(json.length + 64)
        var last = 0
        while (true) {
            val start = json.indexOf(marker, searchFrom)
            if (start < 0) break
            val valueStart = start + marker.length
            // 找到这个值真正的结束引号：后面紧跟 , 或 } 或 ] 的那个
            var i = valueStart
            var end = -1
            while (i < json.length) {
                val c = json[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') {
                    val next = json.getOrNull(i + 1)
                    if (next == ',' || next == '}' || next == ']') {
                        end = i
                        break
                    }
                }
                i++
            }
            if (end < 0) break
            sb.append(json, last, valueStart)
            sb.append(json.substring(valueStart, end).replace('"', '\''))
            last = end
            searchFrom = end
        }
        if (last == 0) return json
        sb.append(json, last, json.length)
        return sb.toString()
    }

    // ============================================================ 咪咕

    private fun searchMigu(
        keyword: String,
        page: Int,
        qualities: List<String>,
        sourceId: String,
        sourceName: String,
    ): List<MusicSong> {
        val switch = """{"song":1,"album":0,"singer":0,"tagSong":0,"mvSong":0,"bestShow":1}"""
        val url = "https://pd.musicapp.migu.cn/MIGUM3.0/v1.0/content/search_all.do" +
            "?ua=Android_migu&version=5.0.1&text=" + URLEncoder.encode(keyword, "UTF-8") +
            "&pageNo=" + page + "&pageSize=" + PAGE_SIZE +
            "&searchSwitch=" + URLEncoder.encode(switch, "UTF-8")
        val body = httpGet(url, mapOf("User-Agent" to UA_ANDROID))
        val json = JSONObject(body)
        val resultList = json.optJSONObject("songResultData")
            ?.optJSONArray("resultList") ?: return emptyList()

        val songs = ArrayList<MusicSong>(resultList.length())
        val seen = HashSet<String>()
        for (i in 0 until resultList.length()) {
            val item = resultList.optJSONObject(i) ?: continue
            val copyrightId = item.optString("copyrightId")
            if (copyrightId.isEmpty() || !seen.add(copyrightId)) continue

            val singers = item.optJSONArray("singerList")
            val singerNames = buildList {
                if (singers != null) {
                    for (s in 0 until singers.length()) {
                        singers.optJSONObject(s)?.optString("name")
                            ?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }.joinToString("、")

            val artwork = item.optJSONArray("coverImgs")?.optJSONObject(0)?.optString("img").orEmpty()

            val extra = JSONObject()
            extra.put("songmid", copyrightId)
            extra.put("songId", item.optString("id"))
            extra.put("copyrightId", copyrightId)
            extra.put("id", copyrightId)
            extra.put("source", "mg")
            extra.put("name", item.optString("songName"))
            extra.put("singer", singerNames)
            extra.put("albumName", item.optString("albumName"))
            extra.put("lrcUrl", item.optString("lyricUrl"))

            songs.add(
                MusicSong(
                    id = copyrightId,
                    title = clean(item.optString("songName")),
                    artist = singerNames,
                    album = item.optString("albumName"),
                    artwork = artwork,
                    durationSec = item.optInt("length", 0) / 1000,
                    platform = "mg",
                    sourceId = sourceId,
                    sourceName = sourceName,
                    qualities = qualities,
                    extra = extra,
                ),
            )
        }
        return songs
    }

    // ============================================================ QQ

    private fun searchQQ(
        keyword: String,
        page: Int,
        qualities: List<String>,
        sourceId: String,
        sourceName: String,
    ): List<MusicSong> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?format=json&p=" + page + "&n=" + PAGE_SIZE +
            "&w=" + URLEncoder.encode(keyword, "UTF-8") + "&cr=1&t=0&new_json=1"
        val body = httpGet(
            url,
            mapOf("User-Agent" to UA_PC, "Referer" to "https://y.qq.com/"),
        )
        val json = JSONObject(
            body.trim().removePrefix("callback(").removeSuffix(")").removeSuffix(";"),
        )
        val list = json.optJSONObject("data")
            ?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()

        val songs = ArrayList<MusicSong>(list.length())
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val mid = item.optString("mid")
            if (mid.isEmpty() || !seen.add(mid)) continue

            val singers = item.optJSONArray("singer")
            val singerNames = buildList {
                if (singers != null) {
                    for (s in 0 until singers.length()) {
                        singers.optJSONObject(s)?.optString("name")
                            ?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }.joinToString("、")

            val album = item.optJSONObject("album")
            val albumMid = album?.optString("mid").orEmpty()
            val artwork = if (albumMid.isNotBlank()) {
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg"
            } else {
                ""
            }
            val extra = JSONObject()
            extra.put("songmid", mid)
            extra.put("songId", item.optString("id"))
            extra.put("id", mid)
            extra.put("source", "tx")
            extra.put("name", item.optString("title"))
            extra.put("singer", singerNames)
            extra.put("albumName", album?.optString("name").orEmpty())
            extra.put("albumMid", albumMid)
            extra.put("interval", item.optInt("interval", 0))

            songs.add(
                MusicSong(
                    id = mid,
                    title = clean(item.optString("title")),
                    artist = singerNames,
                    album = album?.optString("name").orEmpty(),
                    artwork = artwork,
                    durationSec = item.optInt("interval", 0),
                    platform = "tx",
                    sourceId = sourceId,
                    sourceName = sourceName,
                    qualities = qualities,
                    extra = extra,
                ),
            )
        }
        return songs
    }

    // ============================================================ 网易

    private fun searchNetease(
        keyword: String,
        page: Int,
        qualities: List<String>,
        sourceId: String,
        sourceName: String,
    ): List<MusicSong> {
        val url = "https://music.163.com/api/search/get/web" +
            "?s=" + URLEncoder.encode(keyword, "UTF-8") +
            "&type=1&offset=" + (page - 1) * PAGE_SIZE + "&limit=" + PAGE_SIZE
        val body = httpGet(
            url,
            mapOf("User-Agent" to UA_PC, "Referer" to "https://music.163.com/"),
        )
        val json = JSONObject(body)
        val list = json.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()

        val songs = ArrayList<MusicSong>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) continue

            val artists = item.optJSONArray("artists")
            val singerNames = buildList {
                if (artists != null) {
                    for (s in 0 until artists.length()) {
                        artists.optJSONObject(s)?.optString("name")
                            ?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }.joinToString("、")

            val album = item.optJSONObject("album")
            val extra = JSONObject()
            extra.put("songmid", id)
            extra.put("songId", id)
            extra.put("id", id)
            extra.put("source", "wy")
            extra.put("name", item.optString("name"))
            extra.put("singer", singerNames)
            extra.put("albumName", album?.optString("name").orEmpty())
            extra.put("albumId", album?.optString("id").orEmpty())

            songs.add(
                MusicSong(
                    id = id,
                    title = clean(item.optString("name")),
                    artist = singerNames,
                    album = album?.optString("name").orEmpty(),
                    artwork = album?.optString("picUrl").orEmpty(),
                    durationSec = item.optInt("duration", 0) / 1000,
                    platform = "wy",
                    sourceId = sourceId,
                    sourceName = sourceName,
                    qualities = qualities,
                    extra = extra,
                ),
            )
        }
        return songs
    }

    // ============================================================ 工具

    private fun clean(name: String): String {
        var out = name
        for (suffix in NOISE_SUFFIX) {
            if (out.endsWith(suffix)) out = out.removeSuffix(suffix).trim()
        }
        return out.replace("<em>", "").replace("</em>", "")
    }

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val client = com.github.tvbox.osc.net.OkGoHelper.getDefaultClient()
            ?: throw IllegalStateException("http client not ready")
        val builder = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> runCatching { builder.header(k, v) } }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            return resp.body?.string().orEmpty()
        }
    }

    private const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private const val UA_ANDROID =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
