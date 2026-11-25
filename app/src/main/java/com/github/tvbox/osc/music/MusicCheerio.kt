package com.github.tvbox.osc.music

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MusicCheerio private constructor(private val doc: Document) {

    fun selectJson(selector: String): String {
        return try {
            val out = JSONArray()
            for (el in doc.select(selector.trim())) out.put(toJson(el))
            out.toString()
        } catch (th: Throwable) {
            com.github.tvbox.osc.util.LOG.e("mf-cheerio-select-failed : " + th.message)
            "[]"
        }
    }

    private fun toJson(el: Element): JSONObject {
        val o = JSONObject()
        runCatching { o.put("tag", el.tagName()) }
        runCatching { o.put("text", el.text()) }
        runCatching { o.put("ownText", el.ownText()) }
        runCatching { o.put("html", el.outerHtml()) }
        runCatching { o.put("innerHtml", el.html()) }
        val attrs = JSONObject()
        runCatching {
            for (attr in el.attributes()) attrs.put(attr.key, attr.value)
        }
        runCatching { o.put("attrs", attrs) }
        return o
    }

    fun rootText(): String = runCatching { doc.text() }.getOrDefault("")

    fun close() {}

    companion object {
        fun parse(html: String): MusicCheerio {
            val doc = runCatching { Jsoup.parse(html) }
                .getOrElse { Jsoup.parse("<html><body></body></html>") }
            return MusicCheerio(doc)
        }
    }
}