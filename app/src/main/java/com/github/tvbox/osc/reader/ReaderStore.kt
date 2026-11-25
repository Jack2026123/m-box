package com.github.tvbox.osc.reader

import com.github.tvbox.osc.util.KV
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one page-turn structure for both readers. Exactly one of the
 * three mutually exclusive render paths is active at a time; the
 * mode is chosen from the single capsule row in the bottom bar and
 * persisted under the single "turn" key (see the migration note in
 * [ReaderStore]).
 */
object ReaderPageTurnModes {
    /** Vertical continuous scroll — pure scroll body, no turn layer. */
    const val VERTICAL = 0
    /** Left/right pages — pager slide turns. */
    const val HORIZONTAL = 1
    /** Paper-curl simulation (Legado-style bezier curl). */
    const val CURL = 2
}

/** Novel reader settings (persisted globally in KV). */
data class NovelSettings(
    val themeIndex: Int = 0,
    /** The single page-turn mode; see [ReaderPageTurnModes]. */
    val pageTurnMode: Int = ReaderPageTurnModes.VERTICAL,
    val fontSizeSp: Float = 18f,
    val lineHeightMult: Float = 1.6f,
    /** 2.48：页边距（上下左右统一），dp，16..32，默认 24。影响分页高度与左右留白。 */
    val marginDp: Int = 24,
    /** Auto-read pace: seconds each page (or screen) stays up, 2..60. */
    val autoSecondsPerPage: Float = 20f,
    /** TTS speech rate multiplier, 0.5..2.0. */
    val ttsRate: Float = 1.0f,
    /** Narration channel: 0 = auto (system if usable, else network), 1 = system TTS, 2 = network TTS. */
    val ttsChannel: Int = 0,
) {
    /** True only in the vertical-scroll turn mode. */
    val verticalMode: Boolean get() = pageTurnMode == ReaderPageTurnModes.VERTICAL
}

/** Comic reader settings (persisted globally in KV). */
data class ComicSettings(
    /** The single page-turn mode; see [ReaderPageTurnModes]. */
    val pageTurnMode: Int = ReaderPageTurnModes.VERTICAL,
) {
    /** True only in the vertical-scroll turn mode. */
    val verticalMode: Boolean get() = pageTurnMode == ReaderPageTurnModes.VERTICAL
}

data class ReaderBookmark(
    val chapterIndex: Int,
    val page: Int,
    val excerpt: String,
    val createdAt: Long,
)

data class ReaderProgress(
    val chapterIndex: Int,
    val page: Int,
    val imageIndex: Int,
)

object ReaderStore {

    private const val NOVEL_SETTINGS_KEY = "reader_novel_settings"
    private const val COMIC_SETTINGS_KEY = "reader_comic_settings"
    private const val BOOKMARKS_PREFIX = "reader_bookmarks:"
    private const val PROGRESS_PREFIX = "reader_progress:"
    private const val USE_WEB_READER_KEY = "reader_use_web"

    /**
     * 阅读器路由：true = 小说漫画都走 Web，false = 都走自带阅读器。
     * 默认关（自带），1.93 用户定。
     */
    fun useWebReader(): Boolean = KV.get(USE_WEB_READER_KEY, false) ?: false

    fun setUseWebReader(useWeb: Boolean) {
        KV.put(USE_WEB_READER_KEY, useWeb)
    }

    fun novelSettings(): NovelSettings {
        val raw = KV.get<String>(NOVEL_SETTINGS_KEY, "") ?: ""
        if (raw.isEmpty()) return NovelSettings()
        return try {
            val obj = JSONObject(raw)
            NovelSettings(
                themeIndex = obj.optInt("theme", 0).coerceIn(0, NovelThemes.size - 1),
                pageTurnMode = readPageTurnMode(obj),
                fontSizeSp = obj.optDouble("font", 18.0).toFloat().coerceIn(12f, 32f),
                lineHeightMult = obj.optDouble("line", 1.6).toFloat().coerceIn(1.2f, 2.4f),
                marginDp = obj.optInt("margin", 24).coerceIn(16, 32),
                autoSecondsPerPage = readAutoSecondsPerPage(obj),
                ttsRate = obj.optDouble("ttsRate", 1.0).toFloat().coerceIn(0.5f, 2f),
                ttsChannel = obj.optInt("ttsChannel", 0).coerceIn(0, 2),
            )
        } catch (th: Throwable) {
            NovelSettings()
        }
    }

    /**
     * Auto-read pace in seconds per page, default 20 s (settled by
     * the user; the first seconds-based build shipped 10 s). A stored
     * "autoSec" (written by the seconds-based builds) is the user's
     * own value and is kept verbatim. A blob from the pages-per-minute
     * era only carries the legacy "speed" key: that era's factory
     * default (6 pages/min) means the user never set a pace, so it
     * lands on the new 20 s default; any other legacy value was a
     * deliberate choice and is converted (seconds = 60 / pages-per-
     * minute). saveNovelSettings writes only "autoSec", so the legacy
     * key disappears on the next save.
     */
    private fun readAutoSecondsPerPage(obj: JSONObject): Float {
        if (obj.has("autoSec")) {
            return obj.optDouble("autoSec", 20.0).toFloat().coerceIn(2f, 60f)
        }
        if (obj.has("speed")) {
            val legacyPagesPerMinute = obj.optDouble("speed", 6.0).toFloat()
            if (legacyPagesPerMinute == 6f) return 20f
            return (60f / legacyPagesPerMinute.coerceIn(2f, 30f)).coerceIn(2f, 60f)
        }
        return 20f
    }

    /**
     * The single page-turn mode. The "turn" key written by current
     * builds wins. A blob from the two-layer era only carries the
     * legacy pair — "vertical" (flip mode) and "anim" (0 = slide,
     * 1 = fade, 2 = simulation) — and maps once: vertical scroll was
     * its own path and lands on VERTICAL; simulation was its own
     * path and lands on CURL; slide and fade were both plain
     * left/right paging and land on HORIZONTAL (the fade variant is
     * gone). save*Settings writes only "turn", so the legacy keys
     * disappear on the next save.
     */
    private fun readPageTurnMode(obj: JSONObject): Int {
        if (obj.has("turn")) {
            return obj.optInt("turn", ReaderPageTurnModes.VERTICAL)
                .coerceIn(ReaderPageTurnModes.VERTICAL, ReaderPageTurnModes.CURL)
        }
        if (obj.optBoolean("vertical", true)) return ReaderPageTurnModes.VERTICAL
        return if (obj.optInt("anim", 0) == 2) {
            ReaderPageTurnModes.CURL
        } else {
            ReaderPageTurnModes.HORIZONTAL
        }
    }

    fun saveNovelSettings(settings: NovelSettings) {
        val obj = JSONObject()
        obj.put("theme", settings.themeIndex)
        obj.put("turn", settings.pageTurnMode)
        obj.put("font", settings.fontSizeSp.toDouble())
        obj.put("line", settings.lineHeightMult.toDouble())
        obj.put("margin", settings.marginDp)
        obj.put("autoSec", settings.autoSecondsPerPage.toDouble())
        obj.put("ttsRate", settings.ttsRate.toDouble())
        obj.put("ttsChannel", settings.ttsChannel)
        KV.put(NOVEL_SETTINGS_KEY, obj.toString())
    }

    fun comicSettings(): ComicSettings {
        val raw = KV.get<String>(COMIC_SETTINGS_KEY, "") ?: ""
        if (raw.isEmpty()) return ComicSettings()
        return try {
            val obj = JSONObject(raw)
            ComicSettings(
                // Comics no longer offer the paper-curl turn (the
                // mode row has no capsule for it): a stored CURL
                // lands on HORIZONTAL here, at the single read
                // point, so every comic consumer — menu selection
                // state and turn execution alike — sees the mapped
                // mode and the curl path is simply never derived.
                // The novel reader has its own blob and its own
                // read; its CURL is unaffected.
                pageTurnMode = readPageTurnMode(obj).let { mode ->
                    if (mode == ReaderPageTurnModes.CURL) {
                        ReaderPageTurnModes.HORIZONTAL
                    } else {
                        mode
                    }
                },
            )
        } catch (th: Throwable) {
            ComicSettings()
        }
    }

    fun saveComicSettings(settings: ComicSettings) {
        val obj = JSONObject()
        obj.put("turn", settings.pageTurnMode)
        KV.put(COMIC_SETTINGS_KEY, obj.toString())
    }

    fun bookmarks(bookKey: String): List<ReaderBookmark> {
        val raw = KV.get<String>(BOOKMARKS_PREFIX + bookKey, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<ReaderBookmark>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                out.add(
                    ReaderBookmark(
                        chapterIndex = obj.optInt("c", 0),
                        page = obj.optInt("p", 0),
                        excerpt = obj.optString("e", ""),
                        createdAt = obj.optLong("t", 0L),
                    )
                )
            }
            out
        } catch (th: Throwable) {
            emptyList()
        }
    }

    fun saveBookmarks(bookKey: String, bookmarks: List<ReaderBookmark>) {
        val array = JSONArray()
        for (mark in bookmarks) {
            val obj = JSONObject()
            obj.put("c", mark.chapterIndex)
            obj.put("p", mark.page)
            obj.put("e", mark.excerpt)
            obj.put("t", mark.createdAt)
            array.put(obj)
        }
        KV.put(BOOKMARKS_PREFIX + bookKey, array.toString())
    }

    fun addBookmark(bookKey: String, bookmark: ReaderBookmark): List<ReaderBookmark> {
        val current = bookmarks(bookKey).toMutableList()
        current.removeAll { it.chapterIndex == bookmark.chapterIndex && it.page == bookmark.page }
        current.add(0, bookmark)
        saveBookmarks(bookKey, current)
        return current
    }

    fun removeBookmark(bookKey: String, bookmark: ReaderBookmark): List<ReaderBookmark> {
        val current = bookmarks(bookKey).toMutableList()
        current.removeAll { it.chapterIndex == bookmark.chapterIndex && it.page == bookmark.page && it.createdAt == bookmark.createdAt }
        saveBookmarks(bookKey, current)
        return current
    }

    fun progress(bookKey: String): ReaderProgress? {
        val raw = KV.get<String>(PROGRESS_PREFIX + bookKey, "") ?: ""
        if (raw.isEmpty()) return null
        return try {
            val obj = JSONObject(raw)
            ReaderProgress(
                chapterIndex = obj.optInt("c", 0),
                page = obj.optInt("p", 0),
                imageIndex = obj.optInt("i", 0),
            )
        } catch (th: Throwable) {
            null
        }
    }

    fun saveProgress(bookKey: String, progress: ReaderProgress) {
        val obj = JSONObject()
        obj.put("c", progress.chapterIndex)
        obj.put("p", progress.page)
        obj.put("i", progress.imageIndex)
        KV.put(PROGRESS_PREFIX + bookKey, obj.toString())
    }
}

/** The 8 novel reading themes (background / text color pairs). */
data class NovelTheme(val name: String, val background: Long, val text: Long)

object NovelThemes {

    val ALL: List<NovelTheme> = listOf(
        NovelTheme("纸白", 0xFFFFFFFF, 0xFF222222),
        NovelTheme("米黄", 0xFFF7EEDC, 0xFF3E2F1C),
        NovelTheme("护眼绿", 0xFFDCE9D2, 0xFF22331F),
        NovelTheme("淡蓝", 0xFFDDE7F0, 0xFF1F2A38),
        NovelTheme("藕粉", 0xFFF3DFE3, 0xFF3A2229),
        NovelTheme("浅灰", 0xFFE8E8E8, 0xFF262626),
        NovelTheme("夜间", 0xFF1B1B1F, 0xFFC9C9CE),
        NovelTheme("纯黑", 0xFF000000, 0xFF9A9AA0),
        // 阅读器美化：新增纸感与低眩光配色；旧主题顺序不变，进度/设置兼容。
        NovelTheme("雾紫", 0xFFEAE5F2, 0xFF30283A),
        NovelTheme("海盐", 0xFFE1EDF3, 0xFF203442),
        NovelTheme("松林", 0xFFE1EBDD, 0xFF253426),
        NovelTheme("深蓝夜", 0xFF172330, 0xFFD4E2F0),
        NovelTheme("暖夜纸", 0xFF28231E, 0xFFE5D6BF),
    )

    val size: Int get() = ALL.size

    fun at(index: Int): NovelTheme = ALL[index.coerceIn(0, ALL.size - 1)]
}
