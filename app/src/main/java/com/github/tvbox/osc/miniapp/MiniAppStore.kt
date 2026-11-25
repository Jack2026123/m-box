package com.github.tvbox.osc.miniapp

import com.github.tvbox.osc.miniapp.engine.MiniAppHost
import com.github.tvbox.osc.miniapp.engine.MiniAppHttp
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class MiniAppItem(
    val name: String,
    val icon: String,
    val source: String,
    val category: String,
    val ruleJson: String,
    val addedAt: Long,
)

object MiniAppStore {
    val items: StateFlow<List<MiniAppItem>> get() = itemsFlow
    private val itemsFlow = MutableStateFlow(loadItems())

    private fun loadItems(): List<MiniAppItem> =
        KV.get(HawkConfig.MINIAPP_LIST, ArrayList<String>()).mapNotNull { raw ->
            runCatching {
                val j = JSONObject(raw)
                MiniAppItem(
                    name = j.optString("name"),
                    icon = j.optString("icon"),
                    source = j.optString("source"),
                    category = j.optString("category"),
                    ruleJson = j.optString("rule"),
                    addedAt = j.optLong("addedAt"),
                )
            }.getOrNull()
        }.filter { it.name.isNotEmpty() }

    private fun persist() {
        KV.put(
            HawkConfig.MINIAPP_LIST,
            ArrayList(itemsFlow.value.map { item ->
                JSONObject().apply {
                    put("name", item.name)
                    put("icon", item.icon)
                    put("source", item.source)
                    put("category", item.category)
                    put("rule", item.ruleJson)
                    put("addedAt", item.addedAt)
                }.toString()
            }),
        )
    }

    fun refresh() {
        itemsFlow.value = loadItems()
    }

    fun add(item: MiniAppItem): MiniAppItem {
        val entry = item.copy(name = item.name.trim().ifBlank { item.source })
        val list = itemsFlow.value.filterNot { it.source == entry.source } + entry
        itemsFlow.value = list
        persist()
        return entry
    }

    fun delete(item: MiniAppItem) {
        itemsFlow.value = itemsFlow.value.filterNot { it.source == item.source }
        persist()
        purgeRuleState(item)
    }

    /** 按 source 更新已导入小程序（用于编辑 rule JSON / 名称等） */
    fun update(item: MiniAppItem): Boolean {
        val list = itemsFlow.value
        val idx = list.indexOfFirst { it.source == item.source }
        if (idx < 0) return false
        val next = list.toMutableList()
        next[idx] = item.copy(name = item.name.trim().ifBlank { item.source })
        itemsFlow.value = next
        persist()
        return true
    }

    /** 拖动排序：把 fromSource 的项移到 toSource 的项所在位置 */
    fun move(fromSource: String, toSource: String) {
        if (fromSource == toSource) return
        val list = itemsFlow.value.toMutableList()
        val fromIdx = list.indexOfFirst { it.source == fromSource }
        val toIdx = list.indexOfFirst { it.source == toSource }
        if (fromIdx < 0 || toIdx < 0) return
        val entry = list.removeAt(fromIdx)
        val insertAt = list.indexOfFirst { it.source == toSource }.coerceAtLeast(0)
        list.add(insertAt, entry)
        itemsFlow.value = list
        persist()
    }

    /** 整单替换顺序（拖动取消时恢复用） */
    fun setOrder(items: List<MiniAppItem>) {
        itemsFlow.value = items
        persist()
    }

    /**
     * 删除无缓存：除 KV 条目外，一并清掉该小程序自己的引擎状态——
     * 按标题存的宿主变量、按规则标题的网络客户端（含该标题的 Cookie）、
     * 正在打开的运行页（先关掉，ViewModel.onCleared 会清掉它的定时任务）。
     * 规则本身不写任何按小程序存的文件（writeFile 在引擎侧是空实现），
     * 所以删除时没有要删的文件；WebView 与图片缓存是全局按网址共享的，
     * 里面没有规则本身，删了的小程序不会靠它复活，删除时不全清。
     */
    fun purgeRuleState(item: MiniAppItem) {
        val titles = LinkedHashSet<String>()
        if (item.name.isNotBlank()) titles.add(item.name)
        try {
            val ruleTitle = JSONObject(item.ruleJson).optString("title")
            if (ruleTitle.isNotBlank()) titles.add(ruleTitle)
        } catch (_: Throwable) {
        }
        for (title in titles) {
            try {
                MiniAppHost.clearRuleVars(title)
            } catch (_: Throwable) {
            }
            try {
                MiniAppHttp.clearClient(title)
            } catch (_: Throwable) {
            }
            try {
                MiniAppRunRegistry.finishAll(title)
            } catch (_: Throwable) {
            }
        }
    }
}
