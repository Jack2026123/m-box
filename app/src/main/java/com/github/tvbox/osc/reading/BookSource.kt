package com.github.tvbox.osc.reading

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Legado book source. The original JSON is kept verbatim ([rawJson]) so
 * fields this build does not interpret yet survive a load/save cycle.
 * Rule groups stay as raw strings; the engine parses them on use.
 */
class BookSource(val raw: JSONObject) {

    val bookSourceName: String = raw.optString("bookSourceName")
    val bookSourceUrl: String = raw.optString("bookSourceUrl").trim()
    val bookSourceGroup: String = raw.optString("bookSourceGroup")
    /** 0 = text, 1 = audio, 2 = image, 3 = file (Legado bookSourceType). */
    val bookSourceType: Int = raw.optInt("bookSourceType", 0)
    val enabled: Boolean = raw.optBoolean("enabled", true)
    val bookSourceComment: String = raw.optString("bookSourceComment")
    val customOrder: Int = raw.optInt("customOrder", 0)
    val header: String? = raw.optString("header").takeIf { it.isNotBlank() }
    val searchUrl: String = raw.optString("searchUrl")
    val exploreUrl: String? = raw.optString("exploreUrl").takeIf { it.isNotBlank() }
    val jsLib: String? = raw.optString("jsLib").takeIf { it.isNotBlank() }
    val loginUrl: String? = raw.optString("loginUrl").takeIf { it.isNotBlank() }
    val loginCheckJs: String? = raw.optString("loginCheckJs").takeIf { it.isNotBlank() }
    val bookUrlPattern: String? = raw.optString("bookUrlPattern").takeIf { it.isNotBlank() }
    val ruleSearch: JSONObject? = raw.optJSONObject("ruleSearch")
    val ruleBookInfo: JSONObject? = raw.optJSONObject("ruleBookInfo")
    val ruleToc: JSONObject? = raw.optJSONObject("ruleToc")
    val ruleContent: JSONObject? = raw.optJSONObject("ruleContent")
    val ruleExplore: JSONObject? = raw.optJSONObject("ruleExplore")

    fun searchRule(key: String): String = ruleSearch?.optString(key).orEmpty()
    fun infoRule(key: String): String = ruleBookInfo?.optString(key).orEmpty()
    fun tocRule(key: String): String = ruleToc?.optString(key).orEmpty()
    fun contentRule(key: String): String = ruleContent?.optString(key).orEmpty()

    /** Stable identity inside a pack: the declared source URL, name as fallback. */
    val sourceKey: String get() = bookSourceUrl.ifBlank { bookSourceName }

    companion object {
        /**
         * Parse an imported subscription payload into sources. Accepts the
         * standard Legado export (a JSON array), a single source object, or
         * an object wrapping an array. Entries without a name are dropped;
         * a non-URL bookSourceUrl (some sources carry comment text there)
         * is kept as identity only, exactly as exported.
         */
        fun parsePack(text: String): List<BookSource> {
            val trimmed = text.trim()
            val array: JSONArray? = when {
                trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                trimmed.startsWith("{") -> {
                    val obj = runCatching { JSONObject(trimmed) }.getOrNull()
                    when {
                        obj == null -> null
                        obj.has("bookSourceName") -> JSONArray().put(obj)
                        else -> listOf("sources", "bookSources", "data")
                            .firstNotNullOfOrNull { key -> obj.optJSONArray(key) }
                    }
                }
                else -> null
            }
            if (array == null) return emptyList()
            val out = ArrayList<BookSource>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val source = BookSource(item)
                if (source.bookSourceName.isBlank()) continue
                out.add(source)
            }
            return out.sortedBy { it.customOrder }
        }
    }
}

/** A book as exchanged between search, info, shelf and the readers. */
data class LegadoBook(
    val sourceKey: String,
    val sourceName: String,
    val sourceType: Int,
    val bookUrl: String,
    var name: String = "",
    var author: String = "",
    var intro: String = "",
    var coverUrl: String = "",
    var kind: String = "",
    var lastChapter: String = "",
    var wordCount: String = "",
    var tocUrl: String = "",
) {
    /** Unique shelf/registry key: source identity + book URL. */
    val bookKey: String get() = sourceKey + "|" + bookUrl
}

data class LegadoChapter(
    val name: String,
    val url: String,
    val index: Int,
    /** Raw toc item payload for sources whose chapterUrl is "-" / empty:
     *  the content rule then evaluates against the item itself. */
    val payload: String? = null,
    val isVip: Boolean = false,
    val updateTime: String = "",
)

sealed interface LegadoContent {
    data class Text(val body: String) : LegadoContent
    data class Audio(val url: String, val headers: Map<String, String> = emptyMap()) : LegadoContent
    data class Images(
        val urls: List<String>,
        val headers: Map<String, String> = emptyMap(),
        /** ruleContent.imageDecode JS；为空则不解密 */
        val imageDecodeJs: String = "",
    ) : LegadoContent
}
