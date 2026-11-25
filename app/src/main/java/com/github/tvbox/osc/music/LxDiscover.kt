package com.github.tvbox.osc.music

import com.github.tvbox.osc.util.LOG
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 音乐发现区：推荐歌单 + 排行榜。
 *
 * 注意：墨渊这类洛雪脚本只提供 musicUrl（取链），榜单/歌单接口它一个都没有
 * （2026-11-12 全本查过）。这里直接调各平台自己的榜单接口，实测结论见
 * ~/workspace/avbox/build_logs/toplist_probe_278.md：
 *
 *   酷我 kw：榜单 + 推荐全通（走 wapi.kuwo.cn，www 域名会被反爬拦）
 *   QQ tx：榜单 + 推荐全通（老 fcg_v8_toplist_opt 已废弃，改走 musicu.fcg）
 *   网易 wy：榜单 + 推荐通；榜单歌曲走 trackIds 分批调 /api/song/detail 拿全量（2.95）
 *   酷狗 kg：榜单通（走 www HTML 解析）；推荐走 m.kugou.com 歌单接口（沙箱地域拦截，真机待验，2.95）
 *   咪咕 mg：榜单 + 榜单歌曲通（rank-index/rank-info，2.95）；推荐歌单广场 301 到 v5 且 plaza 返回空，暂留空
 *
 * 平台列表代码里写全五个（ALL_PLATFORMS）；榜单/推荐页只显示运行时能拉回
 * 非空数据的平台（见 MusicHomePage 的可用性探测），不要写死通/不通表。
 *
 * 歌曲转 MusicSong 时，extra 必须和 LxSearch 里同平台搜索的字段一一对应，
 * 否则脚本不认（见 MusicRepository.hasPlatformId）。
 * 所有函数都是阻塞调用，必须在 IO 线程上跑；出错返回空列表，不抛。
 */
object LxDiscover {

    data class DiscoverChart(
        val id: String,
        val name: String,
        val cover: String,
        val platform: String,
        val sub: String = "",
    )

    data class DiscoverPlaylist(
        val id: String,
        val name: String,
        val cover: String,
        val platform: String,
        val sub: String = "",
    )

    fun platformName(platform: String): String = when (platform) {
        "kw" -> "酷我"
        "kg" -> "酷狗"
        "tx" -> "QQ音乐"
        "wy" -> "网易云"
        "mg" -> "咪咕"
        else -> platform.uppercase()
    }

    /** 平台列表写全五个（2.95 用户口径）：显示与否由运行时拉取结果决定，不写死。 */
    val ALL_PLATFORMS = listOf("kw", "kg", "tx", "wy", "mg")

    // ============================================================ 榜单列表

    fun charts(source: MusicSource, platform: String): List<DiscoverChart> {
        val runtime = runCatching { LxRuntime.obtain(source) }.getOrNull()
        if (runtime == null || !runtime.isReady) return emptyList()
        return try {
            when (platform) {
                "kw" -> chartsKuwo()
                "tx" -> chartsQQ()
                "wy" -> chartsNetease()
                "kg" -> chartsKugou()
                "mg" -> chartsMigu()
                else -> emptyList()
            }
        } catch (th: Throwable) {
            LOG.e("lx-discover-charts-failed " + platform + " : " + th.message)
            emptyList()
        }
    }

    // ============================================================ 榜单歌曲

    fun chartSongs(source: MusicSource, platform: String, chartId: String): List<MusicSong> {
        val runtime = runCatching { LxRuntime.obtain(source) }.getOrNull()
        if (runtime == null || !runtime.isReady) return emptyList()
        val qualities = runtime.qualityMap[platform].orEmpty()
        return try {
            when (platform) {
                "kw" -> songsKuwoBang(chartId, source, qualities)
                "tx" -> songsQQTop(chartId, source, qualities)
                "wy" -> songsNeteasePlaylist(chartId, source, qualities)
                "kg" -> songsKugouRank(chartId, source, qualities)
                "mg" -> songsMiguRank(chartId, source, qualities)
                else -> emptyList()
            }
        } catch (th: Throwable) {
            LOG.e("lx-discover-songs-failed " + platform + " : " + th.message)
            emptyList()
        }
    }

    // ============================================================ 推荐歌单

    fun recommend(source: MusicSource, platform: String): List<DiscoverPlaylist> {
        val runtime = runCatching { LxRuntime.obtain(source) }.getOrNull()
        if (runtime == null || !runtime.isReady) return emptyList()
        return try {
            when (platform) {
                "kw" -> recommendKuwo()
                "tx" -> recommendQQ()
                "wy" -> recommendNetease()
                "kg" -> recommendKugou()
                "mg" -> recommendMigu()
                else -> emptyList()
            }
        } catch (th: Throwable) {
            LOG.e("lx-discover-recommend-failed " + platform + " : " + th.message)
            emptyList()
        }
    }

    // ============================================================ 歌单歌曲

    fun playlistSongs(source: MusicSource, platform: String, playlistId: String): List<MusicSong> {
        val runtime = runCatching { LxRuntime.obtain(source) }.getOrNull()
        if (runtime == null || !runtime.isReady) return emptyList()
        val qualities = runtime.qualityMap[platform].orEmpty()
        return try {
            when (platform) {
                "kw" -> songsKuwoPlaylist(playlistId, source, qualities)
                "tx" -> songsQQPlaylist(playlistId, source, qualities)
                "wy" -> songsNeteasePlaylist(playlistId, source, qualities)
                "kg" -> songsKugouPlaylist(playlistId, source, qualities)
                else -> emptyList()
            }
        } catch (th: Throwable) {
            LOG.e("lx-discover-playlist-failed " + platform + " : " + th.message)
            emptyList()
        }
    }

    // ============================================================ 酷我

    private fun chartsKuwo(): List<DiscoverChart> {
        val body = httpGet(
            "https://wapi.kuwo.cn/api/www/bang/bang/bangMenu",
            mapOf("User-Agent" to UA_PC, "Referer" to "https://www.kuwo.cn/"),
        )
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<DiscoverChart>()
        for (g in 0 until data.length()) {
            val group = data.optJSONObject(g) ?: continue
            val groupName = group.optString("name")
            val list = firstArray(group) ?: continue
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val sourceId = item.optString("sourceid")
                if (sourceId.isEmpty()) continue
                out.add(
                    DiscoverChart(
                        id = sourceId,
                        name = item.optString("name"),
                        cover = item.optString("pic"),
                        platform = "kw",
                        sub = groupName,
                    ),
                )
            }
        }
        return out
    }

    private fun songsKuwoBang(bangId: String, source: MusicSource, qualities: List<String>): List<MusicSong> {
        val body = httpGet(
            "https://wapi.kuwo.cn/api/www/bang/bang/musicList?bangId=" + bangId + "&pn=0&rn=30",
            mapOf("User-Agent" to UA_PC, "Referer" to "https://www.kuwo.cn/"),
        )
        val list = JSONObject(body).optJSONObject("data")?.optJSONArray("musicList")
            ?: return emptyList()
        return list.toMusicSongs("kw", source, qualities) { item -> songKuwo(item, source, qualities) }
    }

    private fun recommendKuwo(): List<DiscoverPlaylist> {
        val body = httpGet(
            "https://wapi.kuwo.cn/api/www/rcm/index/playlist?loginUid=0",
            mapOf("User-Agent" to UA_PC, "Referer" to "https://www.kuwo.cn/"),
        )
        val list = JSONObject(body).optJSONObject("data")?.optJSONArray("list")
            ?: return emptyList()
        val out = ArrayList<DiscoverPlaylist>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) continue
            out.add(
                DiscoverPlaylist(
                    id = id,
                    name = item.optString("name"),
                    cover = item.optString("img").ifBlank { item.optString("img300") },
                    platform = "kw",
                    sub = item.optString("uname"),
                ),
            )
        }
        return out
    }

    private fun songsKuwoPlaylist(pid: String, source: MusicSource, qualities: List<String>): List<MusicSong> {
        val body = httpGet(
            "https://wapi.kuwo.cn/api/www/playlist/playListInfo?pid=" + pid + "&pn=1&rn=30",
            mapOf("User-Agent" to UA_PC, "Referer" to "https://www.kuwo.cn/"),
        )
        val list = JSONObject(body).optJSONObject("data")?.optJSONArray("musicList")
            ?: return emptyList()
        return list.toMusicSongs("kw", source, qualities) { item -> songKuwo(item, source, qualities) }
    }

    /** 酷我榜单/歌单歌曲字段与 LxSearch.searchKuwo 一致；榜单接口不给 MINFO，音质走默认。 */
    private fun songKuwo(item: JSONObject, source: MusicSource, qualities: List<String>): MusicSong? {
        val rid = item.optString("musicrid").removePrefix("MUSIC_")
        if (rid.isEmpty()) return null
        val extra = JSONObject()
        extra.put("songmid", rid)
        extra.put("songId", rid)
        extra.put("rid", rid)
        extra.put("id", rid)
        extra.put("source", "kw")
        extra.put("name", item.optString("name"))
        extra.put("singer", item.optString("artist"))
        extra.put("albumName", item.optString("album"))
        extra.put("albumId", item.optString("albumid"))
        extra.put("_qualitys", "")
        val types = JSONArray()
        val typesMap = JSONObject()
        types.put("128k")
        typesMap.put("128k", "")
        types.put("320k")
        typesMap.put("320k", "")
        extra.put("types", types)
        extra.put("_types", typesMap)
        return MusicSong(
            id = rid,
            title = item.optString("name"),
            artist = item.optString("artist"),
            album = item.optString("album"),
            artwork = item.optString("pic"),
            durationSec = item.optInt("duration", 0),
            platform = "kw",
            sourceId = source.id,
            sourceName = source.name,
            qualities = qualities,
            extra = extra,
        )
    }

    // ============================================================ QQ

    private fun chartsQQ(): List<DiscoverChart> {
        val dataJson = "{\"comm\":{\"ct\":24,\"cv\":0}," +
            "\"topList\":{\"module\":\"musicToplist.ToplistInfoServer\",\"method\":\"GetAll\",\"param\":{}}}"
        val body = httpGet(
            "https://u.y.qq.com/cgi-bin/musicu.fcg?g_tk=5381&uin=0&format=json&inCharset=utf-8" +
                "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&data=" +
                URLEncoder.encode(dataJson, "UTF-8"),
            mapOf("User-Agent" to UA_PC, "Referer" to "https://y.qq.com/"),
        )
        val groups = JSONObject(body).optJSONObject("topList")
            ?.optJSONObject("data")?.optJSONArray("group") ?: return emptyList()
        val out = ArrayList<DiscoverChart>()
        for (g in 0 until groups.length()) {
            val group = groups.optJSONObject(g) ?: continue
            val list = group.optJSONArray("toplist") ?: continue
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val topId = item.optString("topId")
                if (topId.isEmpty()) continue
                out.add(
                    DiscoverChart(
                        id = topId,
                        name = item.optString("title"),
                        cover = item.optString("frontPicUrl"),
                        platform = "tx",
                        sub = group.optString("groupName"),
                    ),
                )
            }
        }
        return out
    }

    private fun songsQQTop(topId: String, source: MusicSource, qualities: List<String>): List<MusicSong> {
        val body = httpGet(
            "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?tpl=3&page=detail&topid=" + topId +
                "&type=top&song_begin=0&song_num=30&uin=0&format=json&inCharset=utf-8" +
                "&outCharset=utf-8&notice=0&platform=h5&needNewCode=1",
            mapOf("User-Agent" to UA_PC, "Referer" to "https://y.qq.com/"),
        )
        val list = JSONObject(body).optJSONArray("songlist") ?: return emptyList()
        val out = ArrayList<MusicSong>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i)?.optJSONObject("data") ?: continue
            songQQ(item, source, qualities)?.let { out.add(it) }
        }
        return out
    }

    private fun recommendQQ(): List<DiscoverPlaylist> {
        val body = httpGet(
            "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&g_tk=5381" +
                "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0" +
                "&platform=yqq.json&needNewCode=0&categoryId=10000000&sortId=5&sin=0&ein=29",
            mapOf("User-Agent" to UA_PC, "Referer" to "https://y.qq.com/"),
        )
        val list = JSONObject(body).optJSONObject("data")?.optJSONArray("list")
            ?: return emptyList()
        val out = ArrayList<DiscoverPlaylist>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("dissid")
            if (id.isEmpty()) continue
            out.add(
                DiscoverPlaylist(
                    id = id,
                    name = item.optString("dissname"),
                    cover = item.optString("imgurl"),
                    platform = "tx",
                    sub = item.optJSONObject("creator")?.optString("name").orEmpty(),
                ),
            )
        }
        return out
    }

    private fun songsQQPlaylist(dissId: String, source: MusicSource, qualities: List<String>): List<MusicSong> {
        val body = httpGet(
            "https://c.y.qq.com/qzone/fcgi-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1" +
                "&onlysong=0&disstid=" + dissId + "&g_tk=5381&loginUin=0&hostUin=0&format=json" +
                "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0",
            mapOf("User-Agent" to UA_PC, "Referer" to "https://y.qq.com/"),
        )
        val list = JSONObject(body).optJSONArray("cdlist")
            ?.optJSONObject(0)?.optJSONArray("songlist") ?: return emptyList()
        val out = ArrayList<MusicSong>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            songQQ(item, source, qualities)?.let { out.add(it) }
        }
        return out
    }

    /** QQ 榜单/歌单歌曲字段与 LxSearch.searchQQ 一致。 */
    private fun songQQ(item: JSONObject, source: MusicSource, qualities: List<String>): MusicSong? {
        val mid = item.optString("songmid")
        if (mid.isEmpty()) return null
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
        val extra = JSONObject()
        extra.put("songmid", mid)
        extra.put("songId", item.optString("songid"))
        extra.put("id", mid)
        extra.put("source", "tx")
        extra.put("name", item.optString("songname"))
        extra.put("singer", singerNames)
        extra.put("albumName", album?.optString("name").orEmpty())
        extra.put("albumMid", album?.optString("mid").orEmpty())
        extra.put("interval", item.optInt("interval", 0))
        return MusicSong(
            id = mid,
            title = item.optString("songname"),
            artist = singerNames,
            album = album?.optString("name").orEmpty(),
            artwork = "",
            durationSec = item.optInt("interval", 0),
            platform = "tx",
            sourceId = source.id,
            sourceName = source.name,
            qualities = qualities,
            extra = extra,
        )
    }

    // ============================================================ 网易云

    private fun chartsNetease(): List<DiscoverChart> {
        val body = httpGet(
            "https://music.163.com/api/toplist/detail",
            mapOf("User-Agent" to UA_PC, "Referer" to "https://music.163.com/"),
        )
        val list = JSONObject(body).optJSONArray("list") ?: return emptyList()
        val out = ArrayList<DiscoverChart>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) continue
            out.add(
                DiscoverChart(
                    id = id,
                    name = item.optString("name"),
                    cover = item.optString("coverImgUrl"),
                    platform = "wy",
                    sub = item.optString("updateFrequency"),
                ),
            )
        }
        return out
    }

    private fun recommendNetease(): List<DiscoverPlaylist> {
        val body = httpGet(
            "https://music.163.com/api/playlist/list?cat=" +
                URLEncoder.encode("全部", "UTF-8") + "&order=hot&limit=30&offset=0",
            mapOf("User-Agent" to UA_PC, "Referer" to "https://music.163.com/"),
        )
        val list = JSONObject(body).optJSONArray("playlists") ?: return emptyList()
        val out = ArrayList<DiscoverPlaylist>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) continue
            out.add(
                DiscoverPlaylist(
                    id = id,
                    name = item.optString("name"),
                    cover = item.optString("coverImgUrl"),
                    platform = "wy",
                    sub = item.optInt("trackCount", 0).toString() + "首",
                ),
            )
        }
        return out
    }

    /**
     * 网易云榜单/歌单歌曲：playlist/detail 只给前 10 首（tracks[]），全量要按
     * trackIds[] 分批调 /api/song/detail（GET，ids=[...]，2.95 沙箱实测通；
     * POST /api/v3/song/detail 从沙箱超时，不用）。detail 失败时回落前 10 首。
     */
    private fun songsNeteasePlaylist(playlistId: String, source: MusicSource, qualities: List<String>): List<MusicSong> {
        val body = httpGet(
            "https://music.163.com/api/v3/playlist/detail?id=" + playlistId,
            mapOf("User-Agent" to UA_PC, "Referer" to "https://music.163.com/"),
        )
        val playlist = JSONObject(body).optJSONObject("playlist") ?: return emptyList()
        val trackIds = ArrayList<String>()
        val idsArray = playlist.optJSONArray("trackIds")
        if (idsArray != null) {
            for (i in 0 until idsArray.length()) {
                val id = idsArray.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotEmpty()) trackIds.add(id)
            }
        }
        if (trackIds.isNotEmpty()) {
            val full = ArrayList<MusicSong>()
            for (chunk in trackIds.chunked(50)) {
                val idsParam = URLEncoder.encode("[" + chunk.joinToString(",") + "]", "UTF-8")
                val detailBody = runCatching {
                    httpGet(
                        "https://music.163.com/api/song/detail?ids=" + idsParam,
                        mapOf("User-Agent" to UA_PC, "Referer" to "https://music.163.com/"),
                    )
                }.getOrNull().orEmpty()
                if (detailBody.isBlank()) continue
                val songs = runCatching {
                    JSONObject(detailBody).optJSONArray("songs")
                }.getOrNull() ?: continue
                for (i in 0 until songs.length()) {
                    val item = songs.optJSONObject(i) ?: continue
                    songNetease(item, source, qualities)?.let { full.add(it) }
                }
            }
            if (full.isNotEmpty()) return full
        }
        val list = playlist.optJSONArray("tracks") ?: return emptyList()
        val out = ArrayList<MusicSong>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            songNetease(item, source, qualities)?.let { out.add(it) }
        }
        return out
    }

    /** 网易云歌曲字段与 LxSearch.searchNetease 一致。 */
    private fun songNetease(item: JSONObject, source: MusicSource, qualities: List<String>): MusicSong? {
        val id = item.optString("id")
        if (id.isEmpty()) return null
        val artists = item.optJSONArray("ar")
        val singerNames = buildList {
            if (artists != null) {
                for (s in 0 until artists.length()) {
                    artists.optJSONObject(s)?.optString("name")
                        ?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.joinToString("、")
        val album = item.optJSONObject("al")
        val extra = JSONObject()
        extra.put("songmid", id)
        extra.put("songId", id)
        extra.put("id", id)
        extra.put("source", "wy")
        extra.put("name", item.optString("name"))
        extra.put("singer", singerNames)
        extra.put("albumName", album?.optString("name").orEmpty())
        extra.put("albumId", album?.optString("id").orEmpty())
        return MusicSong(
            id = id,
            title = item.optString("name"),
            artist = singerNames,
            album = album?.optString("name").orEmpty(),
            artwork = album?.optString("picUrl").orEmpty(),
            durationSec = item.optInt("dt", 0) / 1000,
            platform = "wy",
            sourceId = source.id,
            sourceName = source.name,
            qualities = qualities,
            extra = extra,
        )
    }

    // ============================================================ 酷狗

    /**
     * 酷狗榜单列表：从 www 榜单页 HTML 解析链接。
     * m.kugou.com 的榜单接口本机被地域拦截（代理出口），手机直连可能通，
     * 这里先用 www 页解析（本机已验证 27 个榜）。
     */
    private fun chartsKugou(): List<DiscoverChart> {
        val body = httpGet(
            "https://www.kugou.com/yy/rank/home/1-8888.html",
            mapOf("User-Agent" to UA_PC),
        )
        val pattern = Regex("""rank/home/1-(\d+)\.html[^>]*>([^<]+)</a>""")
        val seen = HashSet<String>()
        val out = ArrayList<DiscoverChart>()
        for (m in pattern.findAll(body)) {
            val rankId = m.groupValues[1]
            val name = m.groupValues[2].trim()
            if (rankId.isEmpty() || name.isEmpty() || !seen.add(rankId)) continue
            out.add(DiscoverChart(id = rankId, name = name, cover = "", platform = "kg"))
        }
        return out
    }

    /**
     * 酷狗榜单歌曲：同页 HTML 内嵌 global.features。
     * 只有 Hash，没有 Audioid——脚本取链 hash 优先（musicInfo.hash），
     * 所以 hash 直传可用；songmid 也填 hash 做兜底。
     */
    private fun songsKugouRank(rankId: String, source: MusicSource, qualities: List<String>): List<MusicSong> {
        val body = httpGet(
            "https://www.kugou.com/yy/rank/home/1-" + rankId + ".html",
            mapOf("User-Agent" to UA_PC),
        )
        val arrayText = extractJsonArray(body, "global.features") ?: return emptyList()
        val list = JSONArray(arrayText)
        val out = ArrayList<MusicSong>()
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val hash = item.optString("Hash")
            if (hash.isEmpty() || !seen.add(hash)) continue
            songKugouItem(
                hash = hash,
                fileName = item.optString("FileName"),
                timeLen = item.optInt("timeLen", 0),
                albumId = item.optString("album_id"),
                authorName = item.optString("author_name"),
                source = source,
                qualities = qualities,
            )?.let { out.add(it) }
        }
        return out
    }

    // ============================================================ 咪咕

    /**
     * 咪咕榜单列表（2.95，沙箱实测通）：rank-index/v1.0，失败回退 v2.0。
     * 固定请求头 appid=h5、channel/subchannel=014X031、platform=H5、ua=Android_migu、
     * version=6.8.8、referer=https://music.migu.cn/，无登录。
     */
    private fun chartsMigu(): List<DiscoverChart> {
        val body = miguIndexBody()
        if (body.isBlank()) return emptyList()
        val groups = runCatching {
            JSONObject(body).optJSONObject("data")?.optJSONArray("contents")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<DiscoverChart>()
        for (g in 0 until groups.length()) {
            val group = groups.optJSONObject(g) ?: continue
            val groupName = group.optString("style")
            val list = group.optJSONArray("contents") ?: continue
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val rankId = item.optString("rankId")
                if (rankId.isEmpty()) continue
                out.add(
                    DiscoverChart(
                        id = rankId,
                        name = item.optString("rankName"),
                        cover = item.optString("imageUrl"),
                        platform = "mg",
                        sub = groupName,
                    ),
                )
            }
        }
        return out
    }

    private fun miguIndexBody(): String {
        val v1 = runCatching {
            httpGet("https://app.c.nf.migu.cn/pc/bmw/rank/rank-index/v1.0", MG_HEADERS)
        }.getOrNull().orEmpty()
        if (v1.isNotBlank() &&
            runCatching { JSONObject(v1).optString("code") }.getOrNull() == "000000"
        ) {
            return v1
        }
        return runCatching {
            httpGet("https://app.u.nf.migu.cn/bmw/rank/rank-index/v2.0", MG_HEADERS)
        }.getOrNull().orEmpty()
    }

    /** 咪咕榜单歌曲：rank-info/v1.0（2.95 沙箱实测通，code=000000）。 */
    private fun songsMiguRank(rankId: String, source: MusicSource, qualities: List<String>): List<MusicSong> {
        val body = httpGet(
            "https://app.c.nf.migu.cn/pc/bmw/rank/rank-info/v1.0?rankId=" + rankId +
                "&pageNo=1&pageSize=20",
            MG_HEADERS,
        )
        val list = runCatching {
            JSONObject(body).optJSONObject("data")?.optJSONArray("contents")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<MusicSong>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            songMigu(item, source, qualities)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 咪咕榜单歌曲字段与 LxSearch.searchMigu 对齐：id 走 copyrightId；
     * resId/contentId/copyrightId 三个都留进 extra，取链走现有咪咕后端。
     */
    private fun songMigu(item: JSONObject, source: MusicSource, qualities: List<String>): MusicSong? {
        val copyrightId = item.optString("copyrightId")
        if (copyrightId.isEmpty()) return null
        val songData = runCatching { JSONObject(item.optString("songData")) }.getOrNull()
        val contentId = songData?.optString("contentId").orEmpty()
        val extra = JSONObject()
        extra.put("songmid", copyrightId)
        extra.put("songId", item.optString("songId"))
        extra.put("copyrightId", copyrightId)
        extra.put("id", copyrightId)
        extra.put("resId", item.optString("resId"))
        extra.put("contentId", contentId)
        extra.put("source", "mg")
        extra.put("name", item.optString("txt"))
        extra.put("singer", item.optString("txt2"))
        extra.put("albumName", item.optString("txt3"))
        extra.put("albumId", songData?.optString("albumId").orEmpty())
        return MusicSong(
            id = copyrightId,
            title = item.optString("txt"),
            artist = item.optString("txt2"),
            album = item.optString("txt3"),
            artwork = item.optString("img"),
            durationSec = songData?.optInt("duration", 0) ?: 0,
            platform = "mg",
            sourceId = source.id,
            sourceName = source.name,
            qualities = qualities,
            extra = extra,
        )
    }

    /**
     * 咪咕推荐（2.95）：歌单广场 m.music.migu.cn playlist/list 301 到 v5 SPA，
     * 跟重定向只拿到 SPA 首页 HTML；MIGUM2.0 plaza-listbytag 试过多个 tagId
     * 均返回空 itemList。先留空列表，等歌单广场有可用地址再接。
     */
    private fun recommendMigu(): List<DiscoverPlaylist> = emptyList()

    private val MG_HEADERS = mapOf(
        "appid" to "h5",
        "channel" to "014X031",
        "subchannel" to "014X031",
        "platform" to "H5",
        "ua" to "Android_migu",
        "version" to "6.8.8",
        "Referer" to "https://music.migu.cn/",
        "User-Agent" to UA_PC,
    )

    // ============================================================ 酷狗推荐（2.95）

    /**
     * 酷狗推荐歌单：m.kugou.com 歌单接口。沙箱出口会被地域拦截（返回 outsidetpl
     * 模板页），手机直连（大陆 IP）可能通，需真机验证；不通时返回空列表。
     */
    private fun recommendKugou(): List<DiscoverPlaylist> {
        val body = httpGet(
            "https://m.kugou.com/plist/index?json=true",
            mapOf("User-Agent" to UA_PC),
        )
        val list = runCatching {
            JSONObject(body).optJSONObject("plist")?.optJSONObject("list")?.optJSONArray("info")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<DiscoverPlaylist>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("specialid")
            if (id.isEmpty()) continue
            out.add(
                DiscoverPlaylist(
                    id = id,
                    name = item.optString("specialname"),
                    cover = item.optString("imgurl"),
                    platform = "kg",
                    sub = item.optString("playcount"),
                ),
            )
        }
        return out
    }

    /** 酷狗歌单歌曲：m.kugou.com 歌单详情（同推荐接口，地域拦截说明同上）。 */
    private fun songsKugouPlaylist(playlistId: String, source: MusicSource, qualities: List<String>): List<MusicSong> {
        val body = httpGet(
            "https://m.kugou.com/plist/list/" + playlistId + "?json=true",
            mapOf("User-Agent" to UA_PC),
        )
        val list = runCatching {
            JSONObject(body).optJSONObject("list")?.optJSONObject("list")?.optJSONArray("info")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<MusicSong>()
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val hash = item.optString("hash").ifBlank { item.optString("Hash") }
            if (hash.isEmpty() || !seen.add(hash)) continue
            val fileName = item.optString("filename").ifBlank { item.optString("FileName") }
            val timeLen = item.optInt("duration", 0).takeIf { it > 0 }
                ?: item.optInt("timeLen", 0)
            songKugouItem(
                hash = hash,
                fileName = fileName,
                timeLen = timeLen,
                albumId = item.optString("album_id"),
                authorName = item.optString("singername").ifBlank { item.optString("author_name") },
                source = source,
                qualities = qualities,
            )?.let { out.add(it) }
        }
        return out
    }

    /**
     * 酷狗歌曲映射（榜单 HTML 的 global.features 与歌单接口的 info 共用）：
     * 只有 Hash，没有 Audioid——脚本取链 hash 优先，所以 hash 直传可用。
     */
    private fun songKugouItem(
        hash: String,
        fileName: String,
        timeLen: Int,
        albumId: String,
        authorName: String,
        source: MusicSource,
        qualities: List<String>,
    ): MusicSong? {
        if (hash.isEmpty()) return null
        val dash = fileName.indexOf(" - ")
        val singer = if (dash > 0) fileName.substring(0, dash).trim() else authorName
        val title = if (dash > 0) fileName.substring(dash + 3).trim() else fileName
        val extra = JSONObject()
        extra.put("songmid", hash)
        extra.put("songId", hash)
        extra.put("audioId", hash)
        extra.put("id", hash)
        extra.put("hash", hash)
        extra.put("strMediaMid", hash)
        extra.put("source", "kg")
        extra.put("name", title)
        extra.put("singer", singer)
        extra.put("albumName", "")
        extra.put("albumId", albumId)
        extra.put("interval", timeLen)
        val types = JSONArray()
        val typesMap = JSONObject()
        for (q in listOf("128k", "320k", "flac")) {
            types.put(q)
            typesMap.put(q, hash)
        }
        extra.put("types", types)
        extra.put("_types", typesMap)
        return MusicSong(
            id = hash,
            title = title,
            artist = singer,
            album = "",
            artwork = "",
            durationSec = timeLen,
            platform = "kg",
            sourceId = source.id,
            sourceName = source.name,
            qualities = qualities,
            extra = extra,
        )
    }

    // ============================================================ 工具

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

    /** 取对象里第一个 JSONArray 值（榜单分组结构各家写法不同，做兼容）。 */
    private fun firstArray(obj: JSONObject): JSONArray? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val v = obj.opt(keys.next())
            if (v is JSONArray) return v
        }
        return null
    }

    private fun JSONArray.toMusicSongs(
        platform: String,
        source: MusicSource,
        qualities: List<String>,
        build: (JSONObject) -> MusicSong?,
    ): List<MusicSong> {
        val out = ArrayList<MusicSong>()
        val seen = HashSet<String>()
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            val song = build(item) ?: continue
            if (!seen.add(song.id)) continue
            out.add(song)
        }
        return out
    }

    /**
     * 从文本里抠出 marker 后面的第一个 JSON 数组（处理字符串里的括号）。
     * 如：global.features = [{...}, {...}]
     */
    private fun extractJsonArray(text: String, marker: String): String? {
        val start = text.indexOf(marker)
        if (start < 0) return null
        val open = text.indexOf('[', start)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(open, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }
}
