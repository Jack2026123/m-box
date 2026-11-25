package com.github.tvbox.osc.music

import com.github.tvbox.osc.ui.music.LyricLine
import com.github.tvbox.osc.util.KV
import kotlin.math.abs

/**
 * 歌词时间偏移（按歌记忆）+ 半自动两点校准 + live 粗校准。
 *
 * 时间映射（把歌词时间映射到音频轴）：
 *   audioTime ≈ scale * lyricTimeMs + shift
 * 查找当前行时用：positionMs + fineOffset  去和  map(line.timeMs) 比较。
 *
 * - 单点校准：scale=1，shift = position - line.time（整体平移）
 * - 两点校准：用两行算出 scale+shift（intro 变长 / 整体变慢时用）
 * - ± 微调：只改 fineOffset，仍按歌存
 * - 切歌：下一首无记录则 scale=1、shift=0、fine=0（自动回零）
 */
object MusicLyricOffset {

    private const val PREFIX_FINE = "music_lyric_fine_v2_"
    private const val PREFIX_SHIFT = "music_lyric_shift_v2_"
    private const val PREFIX_SCALE = "music_lyric_scale_v2_"
    private const val PREFIX_AUTO_DONE = "music_lyric_auto_done_v2_"
    private const val PREFIX_PENDING_L = "music_lyric_pending_l_v2_"
    private const val PREFIX_PENDING_P = "music_lyric_pending_p_v2_"

    private const val LEGACY_OFFSET = "music_lyric_offset_ms"
    private const val LEGACY_CAL = "music_lyric_cal_offset_ms"

    private val livePattern = Regex(
        "(?i)(\\blive\\b|现场|演唱会|live版|综艺|晚会|音乐会|不插电|acoustic)",
    )

    fun songKey(
        platform: String?,
        id: String?,
        title: String?,
        artist: String?,
    ): String {
        val p = platform?.trim().orEmpty()
        val i = id?.trim().orEmpty()
        if (p.isNotEmpty() && i.isNotEmpty()) return "$p|$i"
        val t = title?.trim().orEmpty().ifBlank { "?" }
        val a = artist?.trim().orEmpty()
        return "t|$t|$a"
    }

    fun songKey(song: MusicSong): String =
        songKey(song.platform, song.id, song.title, song.artist)

    fun isLikelyLive(title: String?, subtitle: String? = null): Boolean {
        val text = listOfNotNull(title, subtitle).joinToString(" ")
        if (text.isBlank()) return false
        return livePattern.containsMatchIn(text)
    }

    /**
     * @param fineOffsetMs 人工 ± 微调（加在 position 上）
     * @param scale 歌词时间拉伸系数，1.0 = 不拉伸
     * @param shiftMs 映射：audio ≈ scale * lyric + shift
     */
    data class Offsets(
        val fineOffsetMs: Int = 0,
        val scale: Double = 1.0,
        val shiftMs: Long = 0L,
    ) {
        val totalApprox: Int
            get() = (fineOffsetMs + shiftMs).toInt()

        fun mapLyricTime(lyricTimeMs: Long): Long {
            val s = scale.coerceIn(0.5, 2.0)
            return (s * lyricTimeMs + shiftMs).toLong()
        }

        fun effectivePosition(positionMs: Long): Long =
            positionMs + fineOffsetMs
    }

    fun get(songKey: String): Offsets {
        if (songKey.isBlank()) {
            return Offsets(
                fineOffsetMs = KV.get(LEGACY_OFFSET, 0),
                scale = 1.0,
                shiftMs = KV.get(LEGACY_CAL, 0).toLong(),
            )
        }
        val fine = if (KV.contains(PREFIX_FINE + songKey)) {
            KV.get(PREFIX_FINE + songKey, 0)
        } else {
            KV.get(LEGACY_OFFSET, 0)
        }
        val scale = if (KV.contains(PREFIX_SCALE + songKey)) {
            KV.get(PREFIX_SCALE + songKey, 1.0)
        } else {
            1.0
        }
        val shift = if (KV.contains(PREFIX_SHIFT + songKey)) {
            KV.get(PREFIX_SHIFT + songKey, 0L)
        } else {
            0L
        }
        return Offsets(fineOffsetMs = fine, scale = scale, shiftMs = shift)
    }

    fun setFine(songKey: String, fineOffsetMs: Int) {
        if (songKey.isBlank()) return
        KV.put(PREFIX_FINE + songKey, fineOffsetMs.coerceIn(-5_000, 5_000))
    }

    fun setAffine(songKey: String, scale: Double, shiftMs: Long) {
        if (songKey.isBlank()) return
        KV.put(PREFIX_SCALE + songKey, scale.coerceIn(0.5, 2.0))
        KV.put(PREFIX_SHIFT + songKey, shiftMs.coerceIn(-300_000L, 300_000L))
    }

    fun reset(songKey: String) {
        if (songKey.isBlank()) return
        KV.delete(PREFIX_FINE + songKey)
        KV.delete(PREFIX_SHIFT + songKey)
        KV.delete(PREFIX_SCALE + songKey)
        KV.delete(PREFIX_AUTO_DONE + songKey)
        clearPending(songKey)
    }

    /** 单点：当前播放位置 = 这一行 */
    fun calibrateOnePoint(
        songKey: String,
        lineTimeMs: Long,
        positionMs: Long,
    ): Offsets {
        val shift = (positionMs - lineTimeMs).coerceIn(-300_000L, 300_000L)
        setAffine(songKey, 1.0, shift)
        setFine(songKey, 0)
        clearPending(songKey)
        return get(songKey)
    }

    /**
     * 两点校准：
     * 第一次：记下点，返回 pending=true
     * 第二次：计算 scale+shift，pending=false
     */
    fun calibrateTwoPointStep(
        songKey: String,
        lineTimeMs: Long,
        positionMs: Long,
    ): TwoPointResult {
        if (songKey.isBlank()) {
            return TwoPointResult(false, false, get(songKey), "无效歌曲")
        }
        val pKeyL = PREFIX_PENDING_L + songKey
        val pKeyP = PREFIX_PENDING_P + songKey

        if (!KV.contains(pKeyL)) {
            KV.put(pKeyL, lineTimeMs)
            KV.put(pKeyP, positionMs)
            return TwoPointResult(
                done = false,
                pending = true,
                offsets = get(songKey),
                message = "已记第 1 点，请再点另一句正在唱的歌词",
            )
        }

        val l1 = KV.get(pKeyL, 0L)
        val p1 = KV.get(pKeyP, 0L)
        val l2 = lineTimeMs
        val p2 = positionMs
        clearPending(songKey)

        val dL = (l2 - l1).toDouble()
        val dP = (p2 - p1).toDouble()
        if (abs(dL) < 3000.0 || abs(dP) < 3000.0) {
            val off = calibrateOnePoint(songKey, l2, p2)
            return TwoPointResult(true, false, off, "两点太近，已按单点对齐")
        }

        val scale = (dP / dL).coerceIn(0.5, 2.0)
        var shift = (p1 - scale * l1).toLong()
        val mapped2 = scale * l2 + shift
        shift += (p2 - mapped2).toLong() / 2

        setAffine(songKey, scale, shift)
        setFine(songKey, 0)
        return TwoPointResult(
            true,
            false,
            get(songKey),
            "两点校准完成 scale=${"%.3f".format(scale)}",
        )
    }

    fun clearPending(songKey: String) {
        if (songKey.isBlank()) return
        KV.delete(PREFIX_PENDING_L + songKey)
        KV.delete(PREFIX_PENDING_P + songKey)
    }

    fun hasPending(songKey: String): Boolean =
        songKey.isNotBlank() && KV.contains(PREFIX_PENDING_L + songKey)

    data class TwoPointResult(
        val done: Boolean,
        val pending: Boolean,
        val offsets: Offsets,
        val message: String,
    )

    fun maybeAutoCalibrate(
        songKey: String,
        title: String?,
        subtitle: String?,
        lines: List<LyricLine>,
    ): Offsets? {
        if (songKey.isBlank() || lines.isEmpty()) return null
        if (!isLikelyLive(title, subtitle)) return null
        if (KV.get(PREFIX_AUTO_DONE + songKey, false)) return null
        KV.put(PREFIX_AUTO_DONE + songKey, true)
        if (KV.contains(PREFIX_SHIFT + songKey) || KV.contains(PREFIX_SCALE + songKey)) return null

        val first = lines.firstOrNull { !isCreditLine(it.text) } ?: return null
        val expectedStart = 10_000L
        val delta = (first.timeMs - expectedStart).coerceIn(-12_000L, 12_000L)
        if (abs(delta) < 1500) return null
        setAffine(songKey, 1.0, -delta)
        return get(songKey)
    }

    private val creditPrefix = Regex(
        "^(作词|作曲|词曲|改编词曲|编曲|制作人|混音|母带|录音|监制|出品|发行|词|曲|演唱|填词|谱曲|和声|吉他|贝斯|鼓|弦乐|制作|音乐总监|OP|SP|ISRC)\\s*[:：]",
    )
    private val creditEnglish = Regex(
        "(?i)^(recording|mixing|mastering|engineer|studio|producer|composer|arranger|lyrics?|vocals?|guitars?|bass|drums?|background vocal|musicians?|OP|SP|ISRC)\\b",
    )

    private fun isCreditLine(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (creditPrefix.containsMatchIn(t)) return true
        if (creditEnglish.containsMatchIn(t)) return true
        return false
    }

    fun lyricIndex(lines: List<LyricLine>, positionMs: Long, off: Offsets): Int {
        if (lines.isEmpty()) return -1
        val pos = off.effectivePosition(positionMs)
        var idx = -1
        for (i in lines.indices) {
            val t = off.mapLyricTime(lines[i].timeMs)
            if (t <= pos) idx = i else break
        }
        return idx
    }
}
