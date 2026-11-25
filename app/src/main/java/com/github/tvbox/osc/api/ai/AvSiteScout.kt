package com.github.tvbox.osc.api.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.LinkedHashSet

/**
 * 2.50：站点侦察 + 定性（照昏黄影视 SiteScout 移植）。
 *
 * 给一个网址，把「生成 py 采集源」需要知道的事实全部量出来，不靠猜：
 * 1. 站点类型：CMS_API（采集接口）/ HTML（服务端渲染）/ SPA（前端渲染）/ UNKNOWN
 * 2. CMS_API：直接拿分类表与字段名（最稳的一条路）
 * 3. HTML：抓分类页、详情页，量列表形态、播放直链、选择器线索
 * 4. 判断依据逐条记进 notes，供人看、也供模型参考
 *
 * 纪律：只输出结构（状态码、字段名、链接形态、正则命中数），不回显页面正文。
 */
object AvSiteScout {

    const val KIND_CMS_API = "CMS_API"
    const val KIND_HTML = "HTML"
    const val KIND_SPA = "SPA"
    const val KIND_UNKNOWN = "UNKNOWN"

    data class Report(
        var kind: String = KIND_UNKNOWN,
        var baseUrl: String = "",
        var siteName: String = "",
        var cms: String = "",
        var apiUrl: String = "",
        var finalUrl: String = "",
        var code: Int = 0,
        val classes: MutableList<Clazz> = mutableListOf(),
        var detailLinkCount: Int = 0,
        val listUrls: MutableList<String> = mutableListOf(),
        val detailUrls: MutableList<String> = mutableListOf(),
        val notes: MutableList<String> = mutableListOf(),
        var hasApi: Boolean = false,
        var spaRendered: Boolean = false,
        // 2.51 修：分类页候选地址（collectLinks 里收），不然 listCandidates 永远空、分类页那步跑不到。
        val listUrlCandidates: MutableList<String> = mutableListOf(),
    ) {
        fun headline(): String = when (kind) {
            KIND_CMS_API -> "采集接口站：" + cms + "，分类 " + classes.size + " 个"
            KIND_HTML -> "HTML 站：详情链接 " + detailLinkCount + " 条，分类 " + classes.size + " 个"
            KIND_SPA -> "SPA 站：已渲染，详情链接 " + detailLinkCount + " 条"
            else -> "未知类型：详情链接 " + detailLinkCount + " 条"
        }
        fun evidence(): String = notes.joinToString("\n")
        // hasApi 用字段 report.hasApi（data class 字段），不需方法
    }

    data class Clazz(val id: String, val name: String)

    private val TIMEOUT_MS = 12000
    private val MAX_CLASSES = 40

    private fun looksJson(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("{") || t.startsWith("[")
    }

    // 2.53 修：挂载点放宽——原来只认无属性的空壳 <div id="app"></div>，
    // 带属性（<div id="app" data-v-xxx>）或渲染后有内容（<div id="app">…</div>）都认不出来。
    // 改成认开标签前缀（双引号/单引号都认），空壳和有内容的都命中。
    private fun isSpa(html: String): Boolean {
        val low = html.lowercase()
        return low.contains("<div id=\"app\"") ||
            low.contains("<div id='app'") ||
            low.contains("<div id=\"root\"") ||
            low.contains("<div id='root'") ||
            low.contains("__nuxt_data__") ||
            low.contains("__next_f")
    }

    /**
     * 侦察入口。返回 Report，调用方据此走对应路线。
     */
    // 2.51 修：改成 suspend——里面调 JsRender.render（suspend），调用方在 runAiTask 的 suspend 块里，改签不影响调用。
    suspend fun scout(siteUrl: String, fetch: (String) -> Pair<Int, String>, progress: (String) -> Unit): Report {
        val report = Report()
        report.baseUrl = siteUrl.trim().trimEnd('/')

        val home = fetch(report.baseUrl)
        report.code = home.first
        if (home.second.length < 200) {
            // 2.52 修：正文短只记一笔，不直接回 UNKNOWN——首页短不代表接口探不到，
            // /api.php/provide/vod/ 是独立探测，首页被墙也可能命中。继续往下走，定性兜底。
            report.notes.add("· 首页正文过短（len=" + home.second.length + "），可能被墙/反爬，继续探测接口")
        }

        progress("定性站点类型…")

        // 分支 1：首页是 JSON（接口站根路径）
        if (looksJson(home.second)) {
            progress("首页是 JSON，按接口站处理")
            fillFromJson(report, report.baseUrl, home.second)
            // 2.51 修：hasApi 是 Boolean 字段，不是方法，不能加 () 调用。
            if (report.hasApi) {
                report.kind = KIND_CMS_API
                if (report.cms.isEmpty()) report.cms = "自定义 JSON 接口"
                return finish(report)
            }
        }

        // 分支 2：标准采集接口探测（苹果CMS v10 等，命中率最高）
        progress("探测标准采集接口…")
        if (probeCmsApi(report, fetch, progress)) return finish(report)

        // 分支 3：HTML 站，逐级量结构
        progress("读首页结构…")
        val title = Regex("<title[^>]*>(.{0,160}?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(home.second)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (title.isNotEmpty()) report.siteName = clean(title)

        val detailCandidates = LinkedHashSet<String>()
        collectLinks(report, home.second, detailCandidates)
        report.detailLinkCount = detailCandidates.size

        // SPA 检测：纯 HTTP 拿不到东西时用无头 WebView 渲染
        // 2.51 修：home 是 Pair<Int, String>，没有 .code，用 .first（状态码）。
        // 2.53 修：留一份渲染后的正文，定性时对它重判 isSpa——原来只查原始首页，
        // 渲染后挂载点带内容就认不出来。
        var renderedBody: String? = null
        val needRender = home.first in 200..399 && (detailCandidates.isEmpty() || isSpa(home.second))
        if (needRender) {
            progress("首页疑似 SPA，用无头 WebView 渲染…")
            try {
                val rendered = JsRender.render(report.baseUrl, 25000L)
                if (rendered.length > home.second.length) {
                    report.spaRendered = true
                    renderedBody = rendered
                    detailCandidates.clear()
                    collectLinks(report, rendered, detailCandidates)
                    report.detailLinkCount = detailCandidates.size
                }
            } catch (ignored: Throwable) {
            }
        }

        // 抓一条分类页
        // 2.51 修：用 collectLinks 收到的分类页地址，不再调永远空的 listCandidates()。
        val listUrl = pickListUrl(report.listUrlCandidates)
        if (listUrl.isNotEmpty()) {
            report.listUrls.add(listUrl)
            progress("打开分类页：" + short(listUrl))
            val listPage = fetch(listUrl)
            if (listPage.first > 0) {
                val d2 = LinkedHashSet<String>()
                collectLinks(report, listPage.second, d2)
                detailCandidates.addAll(d2)
                report.detailLinkCount = detailCandidates.size
                report.notes.add("✓ 分类页 HTTP " + listPage.first + "，抽到详情链接 " + d2.size + " 条")
            }
        }

        // 抓一条详情页，找播放直链
        val detailUrl = detailCandidates.firstOrNull() ?: ""
        if (detailUrl.isNotEmpty()) {
            report.detailUrls.add(detailUrl)
            progress("打开详情页：" + short(detailUrl))
            val detailPage = fetch(detailUrl)
            if (detailPage.first > 0) {
                // 2.51 修：players() 是 AvSiteScout 对象自己的私有方法，不是 Report 的成员，不能写 report.players()；
                // 另外原来 players() 是空实现，直链数永远 0，改成从详情页正文里提 m3u8/mp4 直链。
                report.notes.add("✓ 详情页 HTTP " + detailPage.first + "，直链 " + players(detailPage.second).size + " 条")
            }
        }

        // 定性（按用户定的树：SPA 空壳 → 渲染后重判——渲染出的内容有详情链接就是 HTML 站，
        // 没链接才是 SPA。原先 SPA 优先，有 SPA 壳的站渲染出链接也会被错判成 SPA。）
        // 2.53 修：渲染过就对渲染正文重判 isSpa，没渲染才查原始首页。
        val spaHit = isSpa(renderedBody ?: home.second)
        if (report.kind != KIND_CMS_API) {
            report.kind = if (report.detailLinkCount > 0) KIND_HTML
                else if (spaHit || report.spaRendered) KIND_SPA
                else KIND_UNKNOWN
        }

        return finish(report)
    }

    // 2.51 修：原来是空实现，直链数永远 0。改成从详情页正文里提 m3u8/mp4 直链（只计数，不回显正文）。
    // findAll 返回 Sequence，末尾要 toList() 才是 List<String>，不然返回类型对不上。
    private fun players(html: String): List<String> {
        return try {
            Regex("https?://[^\\s\"'<>]+\\.(m3u8|mp4)(\\?[^\\s\"'<>]*)?", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.value }.distinct().take(20).toList()
        } catch (ignored: Throwable) {
            emptyList()
        }
    }

    private fun finish(report: Report): Report {
        if (report.notes.isEmpty()) report.notes.add("侦察完成")
        return report
    }

    private fun short(url: String): String {
        return try {
            val u = URL(url)
            u.host + u.path
        } catch (ignored: Throwable) {
            url.take(60)
        }
    }

    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun collectLinks(report: Report, html: String, detailCandidates: MutableSet<String>) {
        // 提取分类链接（type/vodshow/vodtype/list）和详情链接（vod/detail/movie/info）
        val hrefs = Regex("<a[^>]+href=[\"']([^\"'#]{1,300})[\"'][^>]*>([\\s\\S]{0,400}?)</a>", RegexOption.IGNORE_CASE)
            .findAll(html)
        for (m in hrefs) {
            val href = m.groupValues[1]
            val text = m.groupValues[2]
            val low = href.lowercase()
            // 分类候选
            if (low.contains("/vodshow/") || low.contains("/vodtype/") || low.contains("/vod/show") ||
                low.contains("/type/") || low.contains("/frim/") || low.contains("cates-")) {
                // 2.51 修：把分类页地址收进 listUrlCandidates，供后面 pickListUrl 抓一条分类页。
                val abs = when {
                    href.startsWith("http://") || href.startsWith("https://") -> href
                    href.startsWith("/") -> report.baseUrl + href
                    else -> report.baseUrl + "/" + href
                }
                if (report.listUrlCandidates.size < MAX_CLASSES && !report.listUrlCandidates.contains(abs)) {
                    report.listUrlCandidates.add(abs)
                }
                val tid = Regex("/vodshow/(\\d+)|/vodtype/(\\d+)|/type/(\\d+)|cates?-(\\d+)").find(href)
                if (tid != null) {
                    val name = clean(text)
                    if (name.isNotEmpty() && report.classes.size < MAX_CLASSES &&
                        !report.classes.any { it.id == (tid.groupValues[1] + tid.groupValues[2] + tid.groupValues[3] + tid.groupValues[4]).filter { c -> c.isDigit() } }) {
                        val id = tid.groupValues.filter { it.isNotEmpty() }.firstOrNull() ?: continue
                        if (report.classes.none { it.id == id }) {
                            report.classes.add(Clazz(id, name))
                        }
                    }
                }
            }
            // 详情候选
            if (low.contains("/vod/") || low.contains("/vod/detail") || low.contains("/voddetail/") ||
                low.contains("/movie/") || low.contains("/detail") || low.contains("vodss-")) {
                if (href.isNotEmpty() && !href.startsWith("javascript:")) {
                    // 2.53 修：详情链接也要拼成绝对地址——相对地址（/vod/detail/id/123.html）
                    // 进 OkHttp 直接抛错，fetch 回 Pair(-1,…)，详情页那步悄悄跑空，"直链 N 条"永远不打。
                    // 跟分类候选同款拼法。
                    val abs = when {
                        href.startsWith("http://") || href.startsWith("https://") -> href
                        href.startsWith("/") -> report.baseUrl + href
                        else -> report.baseUrl + "/" + href
                    }
                    detailCandidates.add(abs)
                }
            }
        }
    }

    private fun pickListUrl(candidates: List<String>): String = candidates.firstOrNull() ?: ""

    // 2.51 修：probeCmsApi 里用 fetch(api) 抓接口，但 fetch 是 scout 的参数，传不进来；改成显式传参。
    private fun probeCmsApi(report: Report, fetch: (String) -> Pair<Int, String>, progress: (String) -> Unit): Boolean {
        val base = report.baseUrl
        val candidates = arrayOf(
            arrayOf("/api.php/provide/vod/", "苹果CMS v10 采集接口"),
            arrayOf("/api.php/provide/vod", "苹果CMS v10 采集接口"),
            arrayOf("/provide/vod/", "苹果CMS 采集接口（子目录）"),
        )
        for (candidate in candidates) {
            val api = base + candidate[0] + "?ac=list"
            val fetched = fetch(api)
            if (fetched.first != 200 || !looksJson(fetched.second)) {
                report.notes.add("· 试 " + candidate[0] + " → HTTP " + fetched.first + "（不命中）")
                continue
            }
            val hit = fillFromJson(report, base + candidate[0], fetched.second)
            if (!hit) {
                report.notes.add("· 试 " + candidate[0] + " → 返回 JSON 但没有分类表（不命中）")
                continue
            }
            report.kind = KIND_CMS_API
            report.cms = candidate[1]
            report.apiUrl = base + candidate[0]
            report.notes.add("✓✓ 命中" + candidate[1] + "：分类 " + report.classes.size + " 个")
            progress("命中采集接口：" + report.cms)
            return true
        }
        return false
    }

    private fun fillFromJson(report: Report, apiUrl: String, body: String): Boolean {
        return try {
            val json = JSONObject(body)
            val classArr = json.optJSONArray("class") ?: return false
            if (classArr.length() == 0) return false
            report.classes.clear()
            for (i in 0 until classArr.length()) {
                val o = classArr.optJSONObject(i) ?: continue
                val id = o.optString("type_id", "")
                val name = o.optString("type_name", "")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    report.classes.add(Clazz(id, name))
                }
            }
            if (report.classes.isNotEmpty()) {
                report.hasApi = true
                report.apiUrl = apiUrl
                true
            } else false
        } catch (ignored: Throwable) {
            false
        }
    }
}