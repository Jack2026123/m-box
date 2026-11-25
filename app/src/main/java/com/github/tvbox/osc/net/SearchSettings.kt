package com.github.tvbox.osc.net

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.StringUtils

object SearchSettings {

    enum class SearchLayout { Horizontal, Vertical }

    private const val KEY_EXACT_MATCH = "search_exact_match"
    private const val KEY_RESULT_LAYOUT = "search_result_layout"
    private const val VALUE_LAYOUT_HORIZONTAL = "horizontal"
    private const val VALUE_LAYOUT_VERTICAL = "vertical"
    private const val KEY_EMPTY_SOURCES = "search_sources_empty"
    /** 对齐昏黄：仅搜支持快速搜索的站，默认关 */
    private const val KEY_QUICK_ONLY = "search_quick_only"
    /** 关键词繁转简后再搜，默认开 */
    private const val KEY_T2S = "search_keyword_t2s"
    /** 单站超时秒数，默认 30（与昏黄 TIMEOUT_SEARCH 一致） */
    private const val KEY_TIMEOUT_SEC = "search_timeout_sec"
    private const val DEFAULT_TIMEOUT_SEC = 30

    fun isExactMatchEnabled(): Boolean = KV.get(KEY_EXACT_MATCH, false)

    fun setExactMatchEnabled(enabled: Boolean) {
        KV.put(KEY_EXACT_MATCH, enabled)
    }

    fun isQuickOnlyEnabled(): Boolean = KV.get(KEY_QUICK_ONLY, false)

    fun setQuickOnlyEnabled(enabled: Boolean) {
        KV.put(KEY_QUICK_ONLY, enabled)
    }

    fun isKeywordT2sEnabled(): Boolean = KV.get(KEY_T2S, true)

    fun setKeywordT2sEnabled(enabled: Boolean) {
        KV.put(KEY_T2S, enabled)
    }

    fun searchTimeoutSec(): Int =
        KV.get(KEY_TIMEOUT_SEC, DEFAULT_TIMEOUT_SEC).coerceIn(5, 120)

    fun setSearchTimeoutSec(sec: Int) {
        KV.put(KEY_TIMEOUT_SEC, sec.coerceIn(5, 120))
    }

    fun searchTimeoutMs(): Long = searchTimeoutSec() * 1000L

    fun resultLayout(): SearchLayout =
        if (KV.get(KEY_RESULT_LAYOUT, VALUE_LAYOUT_VERTICAL) == VALUE_LAYOUT_HORIZONTAL) {
            SearchLayout.Horizontal
        } else {
            SearchLayout.Vertical
        }

    fun setResultLayout(layout: SearchLayout) {
        KV.put(
            KEY_RESULT_LAYOUT,
            if (layout == SearchLayout.Vertical) VALUE_LAYOUT_VERTICAL else VALUE_LAYOUT_HORIZONTAL,
        )
    }

    fun isSourcesEmpty(): Boolean {
        val api = KV.get(HawkConfig.API_URL, "")
        return api.isNotEmpty() && emptyApis().contains(api)
    }

    fun currentSelection(): Set<String>? {
        val api = KV.get(HawkConfig.API_URL, "")
        if (api.isEmpty()) return null
        if (isSourcesEmpty()) return emptySet()
        val stored = KV.get<HashMap<String, HashMap<String, String>>>(HawkConfig.SOURCES_FOR_SEARCH) ?: return null
        val picked = stored[api] ?: return null
        return if (picked.isEmpty()) null else picked.keys.toSet()
    }

    fun putSourcesForSearch(checked: Set<String>) {
        val api = KV.get(HawkConfig.API_URL, "")
        if (api.isEmpty()) return
        val searchable = SearchHelper.getSources().keys
        val picked = checked.filter { it in searchable }
        val all = KV.get<HashMap<String, HashMap<String, String>>>(HawkConfig.SOURCES_FOR_SEARCH) ?: HashMap()
        if (picked.isEmpty() || picked.containsAll(searchable)) {
            all.remove(api)
        } else {
            all[api] = HashMap<String, String>().apply { picked.forEach { this[it] = "1" } }
        }
        if (all.isEmpty()) KV.delete(HawkConfig.SOURCES_FOR_SEARCH) else KV.put(HawkConfig.SOURCES_FOR_SEARCH, all)
        setUpEmpty(api, picked.isEmpty() && searchable.isNotEmpty())
    }

    fun isExactMatch(title: String?, keyword: String?): Boolean {
        val normalized = normalize(title)
        return normalized.isNotEmpty() && normalized == normalize(keyword)
    }

    internal fun normalize(text: String?): String = StringUtils.normalizeTitle(text)

    private fun emptyApis(): Set<String> =
        KV.get(KEY_EMPTY_SOURCES, "").split('\n').filter { it.isNotEmpty() }.toSet()

    private fun setUpEmpty(api: String, empty: Boolean) {
        val next = emptyApis().toMutableSet()
        if (empty) next.add(api) else next.remove(api)
        if (next.isEmpty()) KV.delete(KEY_EMPTY_SOURCES) else KV.put(KEY_EMPTY_SOURCES, next.joinToString("\n"))
    }
}
