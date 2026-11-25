package com.github.tvbox.osc.ui.music

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.CastSheetState

enum class MusicPlayMode(@StringRes val labelRes: Int) {
    SINGLE(R.string.music_mode_single),
    LIST(R.string.music_mode_list),
    ORDER(R.string.music_mode_order);

    fun toggled(): MusicPlayMode = when (this) {
        SINGLE -> LIST
        LIST -> ORDER
        ORDER -> SINGLE
    }

    companion object {
        fun of(name: String?): MusicPlayMode =
            entries.firstOrNull { it.name == name } ?: ORDER
    }
}

/** 歌词空态（2.95）：NONE=有歌词；INSTRUMENTAL=纯音乐；NOT_FOUND=没找到。 */
enum class LyricEmptyState { NONE, INSTRUMENTAL, NOT_FOUND }

class MusicPlayerState {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    // 2.97 搜歌词预填用解析后的歌名/歌手——py 歌的 title/subtitle 是 vod.name/series.name（歌手/歌名-专辑），不能直接当歌名歌手
    var lyricSearchTitle by mutableStateOf("")
    var lyricSearchArtist by mutableStateOf("")
    var sourceName by mutableStateOf("")
    // 2.92 歌词按歌校准：平台+id 进 state，主界面与悬浮窗用同一把 key（py 的歌也有）
    var musicPlatform by mutableStateOf("")
    var musicId by mutableStateOf("")
    var artwork by mutableStateOf("")
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
    // 2.95 歌词空态：无歌词时区分纯音乐与没找到，播放页显示不同的空态
    var lyricEmpty by mutableStateOf(LyricEmptyState.NONE)
    var queue by mutableStateOf<List<String>>(emptyList())
    var queueIndex by mutableStateOf(0)
    var waveSeed by mutableStateOf(0)
    var playMode by mutableStateOf(MusicPlayMode.ORDER)
    var collected by mutableStateOf(false)
    var castSheet by mutableStateOf<CastSheetState?>(null)

    /** 当前是否音乐模式（洛雪音源播放）——决定 UI 显示音质切档 */
    var musicMode by mutableStateOf(false)
    /** 当前音质档位，如 320k / flac */
    var quality by mutableStateOf("")
    /** 这首歌可选的音质档位 */
    var qualityOptions by mutableStateOf<List<String>>(emptyList())
}
