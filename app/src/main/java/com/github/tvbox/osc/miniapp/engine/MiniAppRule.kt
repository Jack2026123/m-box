package com.github.tvbox.osc.miniapp.engine

import org.json.JSONArray
import org.json.JSONObject

data class MiniAppPageRule(
    var colType: String = "",
    var name: String = "",
    var path: String = "",
    var rule: String = "",
    var params: String = "",
)

// 海阔 $.require(名字)：取规则里叫这个名字的子页面，path 与 name 都算。
internal fun findRequirePage(rule: MiniAppRule, name: String): MiniAppPageRule? {
    if (name.isEmpty()) return null
    return rule.pageList.firstOrNull { it.path == name || it.name == name }
}

data class MiniAppListItem(
    var title: String = "",
    var pic: String = "",
    var desc: String = "",
    var descMore: String = "",
    var content: String = "",
    var url: String = "",
    var type: String = "",
    var extra: String = "",
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("title", title)
        j.put("pic", pic)
        j.put("desc", desc)
        j.put("descMore", descMore)
        j.put("content", content)
        j.put("url", url)
        j.put("col_type", type)
        if (extra.isNotEmpty()) {
            try {
                j.put("extra", JSONObject(extra))
            } catch (e: Throwable) {
                j.put("extra", extra)
            }
        }
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): MiniAppListItem {
            val item = MiniAppListItem()
            item.title = j.optString("title")
            item.pic = when {
                j.has("img") -> j.optString("img")
                j.has("pic") -> j.optString("pic")
                else -> j.optString("pic_url")
            }
            item.desc = j.optString("desc")
            item.descMore = j.optString("descMore")
            item.content = j.optString("content")
            item.url = j.optString("url")
            item.type = j.optString("col_type")
            val extra = j.opt("extra")
            item.extra = when (extra) {
                null, JSONObject.NULL -> ""
                is JSONObject, is JSONArray -> extra.toString()
                else -> extra.toString()
            }
            return item
        }
    }
}

data class MiniMovieRule(
    var baseUrl: String = "",
    var chapterUrl: String = "",
    var chapterFind: String = "",
    var movieUrl: String = "",
    var movieFind: String = "",
    var searchUrl: String = "",
    var searchFind: String = "",
    var title: String = "",
    var displayName: String = "",
    var id: String = "",
    var pages: String = "",
    var params: String = "",
    var preRule: String = "",
    var proxy: String = "",
    var ua: String = "",
    var url: String = "",
    var pinYinTitle: String = "",
    var weight: Int = 0,
)

class MiniAppRule {
    var areaName: String = ""
    var areaUrl: String = ""
    var author: String = ""
    var batchDeleting: Boolean = false
    var chapterFind: String = ""
    var className: String = ""
    var classUrl: String = ""
    var colType: String = ""
    var detailColType: String = ""
    var detailFindRule: String = ""
    var displayName: String = ""
    var findRule: String = ""
    var firstHeader: String = ""
    var gmtCreate: Long = 0L
    var gmtModified: Long = 0L
    var group: String = ""
    var icon: String = ""
    var lastUseTime: Long = 0L
    var lastChapterRule: String = ""
    var movieFind: String = ""
    var orderIndex: Int = 0
    var pages: String = ""
    var params: String = ""
    var pinYin: String = ""
    var preRule: String = ""
    var proxy: String = ""
    var sdetailColType: String = ""
    var sdetailFindRule: String = ""
    var searchFind: String = ""
    var searchUrl: String = ""
    var selected: Boolean = false
    var sortName: String = ""
    var sortUrl: String = ""
    var storage: String = ""
    var title: String = ""
    var titleColor: String = ""
    var type: String = ""
    var ua: String = ""
    var url: String = ""
    var version: String = ""
    var yearName: String = ""
    var yearUrl: String = ""

    val pageList: List<MiniAppPageRule>
        get() {
            if (!pages.startsWith("[")) return emptyList()
            return try {
                val arr = JSONArray(pages)
                val out = ArrayList<MiniAppPageRule>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        MiniAppPageRule(
                            colType = o.optString("col_type"),
                            name = o.optString("name"),
                            path = o.optString("path"),
                            rule = o.optString("rule"),
                        ),
                    )
                }
                out
            } catch (e: Throwable) {
                emptyList()
            }
        }

    fun copy(): MiniAppRule {
        val c = MiniAppRule()
        c.areaName = areaName
        c.areaUrl = areaUrl
        c.author = author
        c.batchDeleting = batchDeleting
        c.chapterFind = chapterFind
        c.className = className
        c.classUrl = classUrl
        c.colType = colType
        c.detailColType = detailColType
        c.detailFindRule = detailFindRule
        c.displayName = displayName
        c.findRule = findRule
        c.firstHeader = firstHeader
        c.gmtCreate = gmtCreate
        c.gmtModified = gmtModified
        c.group = group
        c.icon = icon
        c.lastUseTime = lastUseTime
        c.lastChapterRule = lastChapterRule
        c.movieFind = movieFind
        c.orderIndex = orderIndex
        c.pages = pages
        c.params = params
        c.pinYin = pinYin
        c.preRule = preRule
        c.proxy = proxy
        c.sdetailColType = sdetailColType
        c.sdetailFindRule = sdetailFindRule
        c.searchFind = searchFind
        c.searchUrl = searchUrl
        c.selected = selected
        c.sortName = sortName
        c.sortUrl = sortUrl
        c.storage = storage
        c.title = title
        c.titleColor = titleColor
        c.type = type
        c.ua = ua
        c.url = url
        c.version = version
        c.yearName = yearName
        c.yearUrl = yearUrl
        return c
    }

    fun copyForDetail(item: MiniAppListItem, level: Int): MiniAppRule {
        val c = copy()
        if (level == 2) {
            c.findRule = sdetailFindRule
            c.colType = sdetailColType
        } else {
            c.findRule = detailFindRule
            c.colType = detailColType
        }
        c.url = item.url
        if (item.extra.isNotEmpty()) {
            val t = item.extra.trim()
            val jsonOk = (t.startsWith("{") || t.startsWith("[")) && try {
                if (t.startsWith("{")) JSONObject(t) else JSONArray(t)
                true
            } catch (e: Throwable) {
                false
            }
            if (jsonOk) c.params = item.extra
        }
        c.detailFindRule = ""
        c.sdetailFindRule = ""
        return c
    }

    fun toRuleJson(): JSONObject {
        val j = JSONObject()
        j.put("area_name", areaName)
        j.put("area_url", areaUrl)
        j.put("author", author)
        j.put("batchDeleting", batchDeleting)
        j.put("chapter_find", chapterFind)
        j.put("class_name", className)
        j.put("class_url", classUrl)
        j.put("col_type", colType)
        j.put("detail_col_type", detailColType)
        j.put("detail_find_rule", detailFindRule)
        j.put("displayName", displayName)
        j.put("find_rule", findRule)
        j.put("firstHeader", firstHeader)
        j.put("gmtCreate", gmtCreate)
        j.put("gmtModified", gmtModified)
        j.put("group", group)
        j.put("icon", icon)
        j.put("lastUseTime", lastUseTime)
        j.put("last_chapter_rule", lastChapterRule)
        j.put("movie_find", movieFind)
        j.put("orderIndex", orderIndex)
        j.put("pages", pages)
        j.put("params", params)
        j.put("pinYin", pinYin)
        j.put("preRule", preRule)
        j.put("proxy", proxy)
        j.put("sdetail_col_type", sdetailColType)
        j.put("sdetail_find_rule", sdetailFindRule)
        j.put("searchFind", searchFind)
        j.put("search_url", searchUrl)
        j.put("selected", selected)
        j.put("sort_name", sortName)
        j.put("sort_url", sortUrl)
        j.put("storage", storage)
        j.put("title", title)
        j.put("titleColor", titleColor)
        j.put("type", type)
        j.put("ua", ua)
        j.put("url", url)
        j.put("version", version)
        j.put("year_name", yearName)
        j.put("year_url", yearUrl)
        return j
    }

    companion object {
        fun fromJson(json: String): MiniAppRule? {
            return try {
                fromJson(JSONObject(json))
            } catch (e: Throwable) {
                null
            }
        }

        fun fromJson(j: JSONObject): MiniAppRule {
            val r = MiniAppRule()
            r.areaName = j.optString("area_name")
            r.areaUrl = j.optString("area_url")
            r.author = j.optString("author")
            r.batchDeleting = j.optBoolean("batchDeleting")
            r.chapterFind = j.optString("chapter_find")
            r.className = j.optString("class_name")
            r.classUrl = j.optString("class_url")
            r.colType = j.optString("col_type")
            r.detailColType = j.optString("detail_col_type")
            r.detailFindRule = j.optString("detail_find_rule")
            r.displayName = j.optString("displayName")
            r.findRule = j.optString("find_rule")
            r.firstHeader = j.optString("firstHeader")
            r.gmtCreate = j.optLong("gmtCreate")
            r.gmtModified = j.optLong("gmtModified")
            r.group = j.optString("group")
            r.icon = j.optString("icon")
            r.lastUseTime = j.optLong("lastUseTime")
            r.lastChapterRule = j.optString("last_chapter_rule")
            r.movieFind = j.optString("movie_find")
            r.orderIndex = j.optInt("orderIndex")
            r.pages = j.optString("pages")
            r.params = j.optString("params")
            r.pinYin = j.optString("pinYin")
            r.preRule = j.optString("preRule")
            r.proxy = j.optString("proxy")
            r.sdetailColType = j.optString("sdetail_col_type")
            r.sdetailFindRule = j.optString("sdetail_find_rule")
            r.searchFind = j.optString("searchFind")
            r.searchUrl = j.optString("search_url")
            r.selected = j.optBoolean("selected")
            r.sortName = j.optString("sort_name")
            r.sortUrl = j.optString("sort_url")
            r.storage = j.optString("storage")
            r.title = j.optString("title")
            r.titleColor = j.optString("titleColor")
            r.type = j.optString("type")
            r.ua = j.optString("ua")
            r.url = j.optString("url")
            r.version = j.optString("version")
            r.yearName = j.optString("year_name")
            r.yearUrl = j.optString("year_url")
            return r
        }
    }
}
