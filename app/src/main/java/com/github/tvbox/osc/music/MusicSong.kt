package com.github.tvbox.osc.music

import org.json.JSONObject

/**
 * 一首歌。既要有 UI 用的字段，也要有**洛雪原生**的取链信息。
 *
 * 关键：[extra] 里必须原样保存脚本认的字段（songmid / hash / copyrightId 等），
 * 取链时整个交回给脚本，少一个字段就解不出来。
 */
data class MusicSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artwork: String = "",
    /** 秒 */
    val durationSec: Int = 0,
    /** 来源平台：kw / kg / tx / wy / mg */
    val platform: String = "",
    /** 来自哪个音源（MusicSource.id） */
    val sourceId: String = "",
    /** 音源显示名 */
    val sourceName: String = "",
    /** 这个源支持哪些音质档位 */
    val qualities: List<String> = emptyList(),
    /** 洛雪原生 musicInfo，取链时原样回传 */
    val extra: JSONObject = JSONObject(),
) {
    val subtitle: String
        get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")

    val durationText: String
        get() = if (durationSec <= 0) "" else "%d:%02d".format(durationSec / 60, durationSec % 60)

    /** 去重键：同平台同 id 视为同一首 */
    val dedupeKey: String get() = platform + "|" + id
}

/** 搜索结果分页包装 */
data class MusicSearchPage(
    val songs: List<MusicSong>,
    val isEnd: Boolean = true,
)

/** 取链结果 */
data class MusicMedia(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: String = "",
)