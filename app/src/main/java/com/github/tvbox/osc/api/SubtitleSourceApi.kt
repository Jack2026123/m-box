package com.github.tvbox.osc.api

import com.github.catvod.crawler.js.Trans
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.net.Http
import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

// 1.45 字幕源：按 1.0.4 SubtitleApi / SubtitleSetting 的 AVBox 形态。
// 1.46 纠正（覆盖 1.45 默认迅雷）：缺省源改 Assrt；Assrt 回归 1.45 之前的
// AVBox 自带网页爬取——搜索走 secure.assrt.net/sub/（searchword＋sort＋page＋
// no_redir），下载走详情页 #detail-filelist 解析出的 secure.assrt.net/download/…
// 直链；全程不走 api.assrt.net/v1、不需要 Token。
// 源：Assrt（默认，免 Token）/ 迅雷（可选，免 Token）/ 射手（仅本地文件播放，在线不可用）。
// 渲染不走 Libass/ExoSubtitleController，沿用魔改现行 SubtitleLoader + 字幕视图。
object SubtitleSourceApi {

    const val SOURCE_XUNLEI = "xunlei"
    const val SOURCE_ASSRT = "assrt"
    const val SOURCE_SHOOTER = "shooter"

    const val SEARCH_COUNT = 15

    private const val ASSRT_SEARCH_URL = "https://secure.assrt.net/sub/"
    private const val XUNLEI_SEARCH_URL = "https://api-shoulei-ssl.xunlei.com/oracle/subtitle"

    // 1.45 下载解包格式：srt / ass / ssa / vtt / ttml / xml / dfxp。
    private val SUPPORTED_EXTS = setOf("srt", "ass", "ssa", "vtt", "ttml", "xml", "dfxp")

    private val regexShooterFileOnclick = Pattern.compile("onthefly\\(\"(\\d+)\",\"(\\d+)\",\"([\\s\\S]*)\"\\)")

    data class Item(
        val id: Int,
        val name: String,
        val url: String,
        val lang: String,
        val isZip: Boolean = false,
    )

    fun getSource(): String {
        val value = KV.get(HawkConfig.SUBTITLE_SOURCE, SOURCE_ASSRT)
        return if (value.isEmpty()) SOURCE_ASSRT else value
    }

    fun putSource(source: String) {
        KV.put(HawkConfig.SUBTITLE_SOURCE, if (source.isEmpty()) SOURCE_ASSRT else source)
    }

    fun sourceLabel(source: String): String = when (source) {
        SOURCE_ASSRT -> "Assrt（免 Token）"
        SOURCE_SHOOTER -> "射手网（仅本地文件）"
        else -> "迅雷（免 Token）"
    }

    fun isXunlei(): Boolean = getSource() == SOURCE_XUNLEI

    fun isShooter(): Boolean = getSource() == SOURCE_SHOOTER

    // Token 仍可保存（对话框保留 Token 栏），但 1.46 起 Assrt 搜索与下载
    // 不再使用 Token、不走 Token 接口。
    // 1.44 适配：1.44 的 ApiConfig 没有 getAssrt()，订阅配置 assrt 字段
    // 回落去掉，只读 KV 里用户填的 Token。
    fun getEffectiveToken(): String {
        return KV.get(HawkConfig.SUBTITLE_ASSRT_TOKEN, "")
    }

    fun requiresToken(): Boolean = false

    // 当前源是否可以直接搜：只有射手在线不可用；Assrt 与迅雷均免 Token。
    fun ready(): Boolean {
        return !isShooter()
    }

    fun isAutoMatch(): Boolean = KV.get(HawkConfig.SUBTITLE_AUTO_MATCH, true)

    fun getAutoLang(): String = KV.get(HawkConfig.SUBTITLE_AUTO_LANG, "")

    fun isSupported(name: String?, url: String?): Boolean {
        val text = ((name ?: "") + " " + (url ?: "")).lowercase()
        val dot = text.lastIndexOf('.')
        if (dot < 0) return false
        val ext = text.substring(dot + 1).split("?", "#")[0].trim()
        return SUPPORTED_EXTS.contains(ext)
    }

    fun normalize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    // 相关度：2 = 字幕名里含完整片名；1 = 一半以上字符重合；0 = 不沾边。
    fun relevance(subtitleName: String?, query: String?): Int {
        if (query.isNullOrEmpty()) return 1
        val normQuery = normalize(query)
        if (normQuery.isEmpty()) return 1
        val name = normalize(subtitleName)
        if (name.isEmpty()) return 0
        if (name.contains(normQuery) || normQuery.contains(name)) return 2
        var hit = 0
        for (c in normQuery) if (name.indexOf(c) >= 0) hit++
        return if (hit * 2 >= normQuery.length) 1 else 0
    }

    suspend fun search(keyword: String, page: Int): List<Item> {
        if (isShooter()) return emptyList()
        if (isXunlei()) return searchXunlei(keyword)
        return searchAssrtWeb(keyword, page)
    }

    private suspend fun searchXunlei(keyword: String): List<Item> {
        val query = try {
            Trans.t2s(keyword) ?: keyword
        } catch (e: Throwable) {
            keyword
        }
        val text = Http.get(XUNLEI_SEARCH_URL) {
            params("name", query)
        }
        val root = JSONObject(text)
        if (root.optInt("code", -1) != 0) throw IllegalStateException("迅雷字幕返回错误 code=" + root.optInt("code", -1))
        val data = root.optJSONArray("data") ?: return emptyList()
        val items = ArrayList<Item>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val url = obj.optString("url", "").trim()
            val name = obj.optString("name", "").trim()
            if (url.isEmpty() || !isSupported(name, url)) continue
            val langs = obj.optJSONArray("languages")
            var lang = ""
            if (langs != null) {
                for (j in 0 until langs.length()) {
                    val value = langs.optString(j, "").replace("&", " ").trim()
                    if (!value.isEmpty()) {
                        lang = value
                        break
                    }
                }
            }
            items.add(Item(Math.abs(url.hashCode()), if (name.isEmpty()) url else name, url, lang))
        }
        val normQuery = normalize(keyword)
        items.sortWith { a, b -> relevance(b.name, normQuery).compareTo(relevance(a.name, normQuery)) }
        return items
    }

    // 1.46 Assrt 网页搜索：照搬 1.45 之前的 AVBox 自带爬取（secure.assrt.net/sub/
    // searchword＋sort=rank＋page＋no_redir=1），解析 .resultcard
    // .sublist_box_title a.introtitle，标题须含搜词才收；结果是详情页容器项
    // （url=详情页；调用方按容器处理：手动流程点开取文件列表，自动匹配走 detailFiles）。
    private suspend fun searchAssrtWeb(keyword: String, page: Int): List<Item> {
        val content = Http.get(ASSRT_SEARCH_URL) {
            params("searchword", keyword)
            params("sort", "rank")
            params("page", page.coerceAtLeast(1).toString())
            params("no_redir", "1")
        }
        val doc = withContext(Dispatchers.IO) { Jsoup.parse(content) }
        val items = ArrayList<Item>()
        for (element in doc.select(".resultcard .sublist_box_title a.introtitle")) {
            val title = element.attr("title")
            val href = element.attr("href")
            if (href.isEmpty()) continue
            if (title.isEmpty() || !title.lowercase(Locale.ROOT).contains(keyword.lowercase(Locale.ROOT))) continue
            val detailUrl = if (href.startsWith("http://") || href.startsWith("https://")) href else "https://assrt.net" + href
            items.add(Item(0, title, detailUrl, ""))
        }
        return items
    }

    // 1.46 Assrt 详情页解析：照搬现行 fetchSubtitleFiles 的同一套 DOM 口径
    // （#detail-filelist .waves-effect 的 onthefly(...) → secure.assrt.net/download/…
    // 直链；无 filelist 时回落 .download a#btn_download），供自动匹配与点开取文件列表用。
    // item.url 为详情页地址；返回的都是直链文件项（isZip=false）。
    suspend fun detailFiles(item: Item): List<Item> {
        val url = item.url
        if (url.isEmpty()) return emptyList()
        val content = Http.get(url)
        val doc = withContext(Dispatchers.IO) { Jsoup.parse(content) }
        val items = ArrayList<Item>()
        val fileItems = doc.select("#detail-filelist .waves-effect")
        if (fileItems.size > 0) {
            for (element in fileItems) {
                val onclick = element.attr("onclick")
                if (onclick.isEmpty()) continue
                val matcher = regexShooterFileOnclick.matcher(onclick)
                if (!matcher.find()) continue
                val fileName = matcher.group(3) ?: continue
                if (!isSupported(fileName, "")) continue
                val downloadUrl = String.format("https://secure.assrt.net/download/%s/-/%s/%s", matcher.group(1), matcher.group(2), matcher.group(3))
                val nameElement = element.selectFirst("#filelist-name")
                val name = if (nameElement == null) fileName else nameElement.text()
                items.add(Item(0, name, downloadUrl, item.lang, false))
            }
            return items
        }
        val download = doc.selectFirst(".download a#btn_download") ?: return emptyList()
        val href = download.attr("href")
        if (href.isEmpty() || !isSupported(href, "")) return emptyList()
        val downloadUrl = "https://assrt.net" + href
        val title = href.substring(href.lastIndexOf("/") + 1)
        val name = try {
            java.net.URLDecoder.decode(title, "UTF-8")
        } catch (e: Throwable) {
            title
        }
        items.add(Item(0, name, downloadUrl, item.lang, false))
        return items
    }

    // 下载字幕直链；zip 解包到缓存并取第一个受支持文件，返回本地路径（失败返回 null）。
    suspend fun downloadToLocal(item: Item): String? {
        return try {
            val raw = Http.getRaw(item.url)
            val bytes = raw.body
            if (bytes.isEmpty()) return null
            val cacheDir = File(App.getInstance()?.cacheDir, "subtitle_src")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val isZip = item.isZip || item.url.lowercase().endsWith(".zip") ||
                (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
            if (!isZip) {
                val ext = guessExt(item.name, item.url)
                val out = File(cacheDir, "sub_" + Math.abs(item.url.hashCode()) + "." + ext)
                out.writeBytes(bytes)
                return out.absolutePath
            }
            val dir = File(cacheDir, "zip_" + Math.abs(item.url.hashCode()))
            if (!dir.exists()) dir.mkdirs()
            ZipInputStream(bytes.inputStream()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && isSupported(entry.name, "")) {
                        val safe = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        val out = File(dir, safe)
                        out.outputStream().use { o -> zin.copyTo(o) }
                        return out.absolutePath
                    }
                    entry = zin.nextEntry
                }
            }
            null
        } catch (e: Throwable) {
            LOG.e("SubtitleSourceApi", e)
            null
        }
    }

    private fun guessExt(name: String, url: String): String {
        for (text in listOf(name, url)) {
            val clean = text.substringBefore('?').substringBefore('#')
            val dot = clean.lastIndexOf('.')
            if (dot >= 0) {
                val ext = clean.substring(dot + 1).lowercase()
                if (SUPPORTED_EXTS.contains(ext)) return ext
            }
        }
        return "srt"
    }
}
