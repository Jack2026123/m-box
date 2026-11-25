package com.github.tvbox.osc.miniapp

import org.json.JSONArray
import org.json.JSONObject

/** 小程序规则 JSON 检验：语法 + 海阔常用字段 + AVBox 兼容提示 */
object MiniAppRuleValidator {

    data class Report(
        val ok: Boolean,
        val summary: String,
        val details: List<String>,
    )

    fun validate(raw: String): Report {
        val details = ArrayList<String>()
        val text = raw.trim()
        if (text.isEmpty()) {
            return Report(false, "内容为空", listOf("请粘贴完整规则 JSON（以 { 开头）"))
        }
        if (!text.startsWith("{")) {
            return Report(
                false,
                "不是 JSON 对象",
                listOf(
                    "应以 { 开头。若是云口令/分享文，请先导入，不要把口令当 JSON 检验。",
                    "当前开头: " + text.take(40).replace("\n", " "),
                ),
            )
        }
        val obj = try {
            JSONObject(text)
        } catch (e: Throwable) {
            return Report(false, "JSON 语法错误", listOf(e.message ?: e.toString()))
        }

        val title = obj.optString("title").ifBlank { obj.optString("name") }
        if (title.isBlank()) details.add("缺少 title（标题）")
        else details.add("标题: $title")

        val findRule = obj.optString("find_rule")
        if (findRule.isBlank()) details.add("缺少 find_rule（首页解析，重要）")
        else {
            val kind = if (findRule.trimStart().startsWith("js:")) " [JS]" else ""
            details.add("find_rule: 有（${findRule.length} 字符）$kind")
        }

        val url = obj.optString("url")
        if (url.isBlank()) details.add("url: 空（虚拟页规则可能如此）")
        else details.add("url: " + url.take(80) + if (url.length > 80) "…" else "")

        val searchUrl = obj.optString("search_url")
        details.add(if (searchUrl.isBlank()) "search_url: 无（不能搜索）" else "search_url: 有")

        val detail = obj.optString("detail_find_rule")
        details.add(
            if (detail.isBlank()) "detail_find_rule: 无（详情可能靠 pages/require）"
            else "detail_find_rule: 有（${detail.length} 字符）",
        )

        when (val pagesRaw = obj.opt("pages")) {
            null, "" -> details.add("pages: 无")
            is JSONArray -> details.add("pages: ${pagesRaw.length()} 个子页面")
            is String -> {
                if (pagesRaw.isBlank() || pagesRaw == "[]") details.add("pages: 空")
                else {
                    try {
                        val arr = JSONArray(pagesRaw)
                        details.add("pages: ${arr.length()} 个子页面")
                    } catch (_: Throwable) {
                        details.add("pages: 存在但不是合法 JSON 数组")
                    }
                }
            }
            else -> details.add("pages: 类型 ${pagesRaw.javaClass.simpleName}")
        }

        val warns = ArrayList<String>()
        if (text.contains("hiker://files")) warns.add("依赖 hiker://files（AVBox 支持弱）")
        if (text.contains("getCryptoJS") || text.contains("CryptoJS")) {
            warns.add("依赖 CryptoJS/getCryptoJS")
        }
        if (text.contains("writeFile(") || text.contains("fileExist(")) {
            warns.add("依赖 fileExist/writeFile 本地文件")
        }
        if (text.contains("webview://") || text.contains("x5://")) {
            warns.add("含 webview://（需嗅探能力）")
        }
        if (text.contains("refreshPage")) warns.add("含 refreshPage（需虚拟页刷新）")
        if (findRule.length > 50_000 || detail.length > 50_000) {
            warns.add("脚本体积很大，可能卡顿")
        }
        if (warns.isNotEmpty()) {
            details.add("—— AVBox 兼容提示 ——")
            details.addAll(warns)
        }

        val missingFind = details.any { it.startsWith("缺少 find_rule") }
        val missingTitle = details.any { it.startsWith("缺少 title") }
        val ok = !missingFind
        val summary = when {
            missingFind -> "未通过：缺少 find_rule"
            missingTitle && warns.isNotEmpty() -> "基本可用：缺标题，且有兼容风险"
            missingTitle -> "基本可用：缺标题"
            warns.isNotEmpty() -> "通过：JSON 合法（有兼容提示）"
            else -> "通过：JSON 合法，基本字段齐全"
        }
        return Report(ok, summary, details)
    }

    fun validateItem(item: MiniAppItem): Report = validate(item.ruleJson)
}
