package com.github.tvbox.osc.miniapp.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.jsoup.Jsoup

class MiniAppEngine(private val appContext: Context) {

    private val host = MiniAppHost(appContext)

    sealed class PageResult {
        data class ListPage(
            val items: List<MiniAppListItem>,
            val rule: MiniAppRule,
            val pageTitle: String,
        ) : PageResult()

        data class Error(val message: String) : PageResult()
    }

    sealed class LazyResult {
        data class Play(val url: String, val headers: Map<String, String>) : LazyResult()
        data class Error(val message: String) : LazyResult()
        object Refresh : LazyResult()
        data class Toast(val message: String) : LazyResult()
        data class Route(val url: String) : LazyResult()
    }

    sealed class ItemAction {
        data class OpenList(val rule: MiniAppRule) : ItemAction()
        data class Play(val url: String, val headers: Map<String, String>) : ItemAction()
        data class OpenPage(val pageUrl: String, val item: MiniAppListItem? = null) : ItemAction()
        data class OpenItems(val rule: MiniAppRule, val items: List<MiniAppListItem>, val pageTitle: String) : ItemAction()
        data class Nothing(val reason: String) : ItemAction()
        object Refresh : ItemAction()
        data class Toast(val message: String) : ItemAction()
        data class SniffPlay(val pageUrl: String, val headers: Map<String, String> = emptyMap()) : ItemAction()
    }

    fun host(): MiniAppHost = host

    /** logtest终极版: 小程序会话结束 (ViewModel.onCleared) 时清掉本会话
     * 注册的全部周期任务 (海阔离页自动删的 AVBox 等价物). */
    fun destroyTasks() {
        host.schedulePool.destroy()
    }

    private fun jsEvalFn(): MiniAppSelector.JsEvalFn {
        return { code, input -> host.evalJs(code, input) }
    }

    private val preRuleExecutedTitles = HashSet<String>()

    fun runPreRuleIfNeeded(rule: MiniAppRule) {
        if (rule.preRule.isBlank()) return
        if (!preRuleExecutedTitles.add(rule.title)) return
        try {
            host.runPreRule(rule)
        } catch (e: Throwable) {
            Log.d(MiniAppHost.TAG, "preRule failed: $e")
        }
    }

    fun loadHome(rule: MiniAppRule, page: Int = 1, inject: Map<String, Any?>? = null): PageResult {
        runPreRuleIfNeeded(rule)
        val findRule = rule.findRule
        val jsInject = HashMap<String, Any?>()
        if (inject != null) jsInject.putAll(inject)
        jsInject["MY_PAGE"] = page
        val pageUrl = MiniAppSelector.parsePageClassUrl(rule.url, page, rule, jsInject)
        if (findRule.startsWith("js:")) {
            val exec = host.runHome(rule, pageUrl, findRule, jsInject)
            if (exec.error != null) return PageResult.Error(exec.error!!)
            val items = applyHomePostProcess(exec.finalItems() ?: emptyList(), rule)
            return PageResult.ListPage(items, rule, exec.pageTitle)
        }
        val fetchUrl = MiniAppHttp.getRealUrlFilterHeaders(pageUrl)
        if (fetchUrl.startsWith("hiker://")) {
            return PageResult.Error("虚拟页面无法直接打开：" + fetchUrl)
        }
        val headers = MiniAppHttp.getHeaders(pageUrl, jsEvalFn())
        val result = try {
            MiniAppHttp.fetch(
                url = fetchUrl,
                headers = headers ?: emptyMap(),
                ruleTitle = rule.title,
            )
        } catch (e: Throwable) {
            return PageResult.Error("网络请求失败：" + e.message)
        }
        return try {
            val items = findListHome(rule, result.body, fetchUrl)
            PageResult.ListPage(items, rule, "")
        } catch (e: Throwable) {
            PageResult.Error("解析失败：" + e.toString())
        }
    }

    fun findListHome(rule: MiniAppRule, html: String, pageUrl: String): List<MiniAppListItem> {
        val findRule = rule.findRule
        val ruleSegment = findRule.split("==>")[0]
        val segments = ruleSegment.split(";")
        val suffixParts = findRule.split("==>")
        val movieRule = MiniMovieRule()
        movieRule.baseUrl = MiniAppText.getBaseUrl(rule.url) ?: ""
        movieRule.searchUrl = rule.url
        movieRule.chapterUrl = pageUrl
        val doc = Jsoup.parse(html)
        val chain = segments[0].split("&&")
        var el = MiniAppSelector.getTrueElement(chain[0], doc)
        for (i in 1 until chain.size - 1) {
            el = MiniAppSelector.getTrueElement(chain[i], el)
        }
        val elements = MiniAppSelector.selectElements(el, chain[chain.size - 1])
        val out = ArrayList<MiniAppListItem>()
        var index = 0
        val jsEval = jsEvalFn()
        for (element in elements) {
            try {
                val item = MiniAppListItem()
                item.type = if (rule.colType.isEmpty()) "movie_3" else rule.colType
                item.title = MiniAppSelector.getTextByRule(element, segments[1], jsEval)
                if (segments.size > 2) {
                    val picRule = segments[2]
                    if (picRule == "*") {
                        item.pic = "*"
                        if (rule.colType == "movie_1") {
                            item.type = "text_1"
                        }
                    } else {
                        item.pic = MiniAppSelector.getUrlByRule("*", movieRule, element, picRule, jsEval)
                    }
                }
                item.desc = MiniAppSelector.getTextByRule(element, segments[3], jsEval)
                if (segments.size > 4) {
                    val detail = MiniAppText.arrayToString(segments.toTypedArray(), 4, "&&")
                    var url = MiniAppSelector.getUrlByRule(pageUrl, movieRule, element, detail, jsEval)
                    if (suffixParts.size > 1) {
                        val suffixHead = suffixParts[1].replace("fyIndex", index.toString())
                        val suffix = if (suffixParts.size > 2) {
                            suffixHead + "==>" + MiniAppText.arrayToString(suffixParts.toTypedArray(), 2, "==>")
                        } else {
                            suffixHead
                        }
                        if (suffix.isNotEmpty()) {
                            url += "@rule=$suffix"
                        }
                    }
                    item.url = url
                }
                if (canAdd(item.title)) {
                    out.add(item)
                }
            } catch (e: Throwable) {
                Log.d(MiniAppHost.TAG, "home item skipped: $e")
            } finally {
                index++
            }
        }
        return out
    }

    private fun canAdd(title: String): Boolean = true

    private fun applyHomePostProcess(items: List<MiniAppListItem>, rule: MiniAppRule): List<MiniAppListItem> {
        return items.map { item ->
            val copy = item.copy()
            if (rule.colType.isNotEmpty()) {
                if (copy.pic == "*") copy.type = "text_1"
                if (copy.type.isEmpty()) copy.type = rule.colType
            } else {
                if (copy.pic == "*") copy.type = "text_1"
                if (copy.type.isEmpty()) copy.type = "movie_3"
            }
            copy
        }
    }

    fun search(rule: MiniAppRule, keyword: String, page: Int = 1): PageResult {
        if (rule.searchUrl.isEmpty()) {
            return PageResult.Error("该规则没有搜索地址")
        }
        val movieRule = MiniMovieRule()
        movieRule.title = rule.title
        movieRule.displayName = rule.displayName
        movieRule.searchUrl = rule.searchUrl
        movieRule.searchFind = rule.searchFind
        movieRule.params = rule.params
        movieRule.url = rule.url
        movieRule.baseUrl = MiniAppText.getBaseUrl(rule.url) ?: ""
        movieRule.chapterUrl = rule.url
        runPreRuleIfNeeded(rule)
        if (rule.searchFind.isEmpty()) {
            return PageResult.Error("搜索解析规则不能为空")
        }
        if (rule.searchFind.startsWith("js:")) {
            val inject = HashMap<String, Any?>()
            inject["MY_PAGE"] = page
            inject["MY_KEYWORD"] = keyword
            val request = MiniAppHttp.parseSearchUrlForHtml(keyword, rule.searchUrl, jsEvalFn())
            val exec = host.runSearch(movieRule, request.url, inject)
            if (exec.error != null) return PageResult.Error(exec.error!!)
            val items = (exec.finalItems() ?: emptyList()).map { item ->
                val copy = item.copy()
                if (copy.type.isEmpty()) copy.type = "video"
                copy
            }
            return PageResult.ListPage(items, rule, exec.pageTitle)
        }
        val request = MiniAppHttp.parseSearchUrlForHtml(keyword, rule.searchUrl, jsEvalFn())
        val fetchUrl = if (request.method == "POST") {
            MiniAppHttp.formUrlOf(request.url)
        } else {
            request.url
        }
        val result = try {
            MiniAppHttp.fetch(
                url = fetchUrl,
                headers = request.headers ?: emptyMap(),
                method = request.method,
                formParams = request.formParams,
                charset = request.charset ?: "UTF-8",
                ruleTitle = rule.title,
            )
        } catch (e: Throwable) {
            return PageResult.Error("网络请求失败：" + e.message)
        }
        return try {
            val items = findListSearch(movieRule, rule, result.body, fetchUrl)
            PageResult.ListPage(items, rule, "")
        } catch (e: Throwable) {
            PageResult.Error("findList：" + e.toString())
        }
    }

    fun findListSearch(
        movieRule: MiniMovieRule,
        rule: MiniAppRule,
        html: String,
        pageUrl: String,
    ): List<MiniAppListItem> {
        val segments = rule.searchFind.split(";")
        val doc = Jsoup.parse(html)
        val chain = segments[0].split("&&")
        var el = MiniAppSelector.getTrueElement(chain[0], doc)
        for (i in 1 until chain.size - 1) {
            el = MiniAppSelector.getTrueElement(chain[i], el)
        }
        val elements = MiniAppSelector.selectElements(el, chain[chain.size - 1])
        val out = ArrayList<MiniAppListItem>()
        val jsEval = jsEvalFn()
        val context = pageUrl
        for (element in elements) {
            try {
                val item = MiniAppListItem()
                item.title = MiniAppSelector.getTextByRule(element, segments[1], jsEval)
                item.url = MiniAppSelector.getUrlByRule(context, movieRule, element, segments[2], jsEval)
                if (segments.size > 3 && segments[3].isNotEmpty() && segments[3] != "*") {
                    item.descMore = MiniAppSelector.getTextByRule(element, segments[3], jsEval)
                }
                if (segments.size > 4 && segments[4].isNotEmpty() && segments[4] != "*") {
                    item.content = MiniAppSelector.getTextByRule(element, segments[4], jsEval)
                }
                if (segments.size > 5 && segments[5].isNotEmpty() && segments[5] != "*") {
                    item.pic = MiniAppSelector.getUrlByRule("*", movieRule, element, segments[5], jsEval)
                }
                item.desc = if (rule.displayName.isNotEmpty()) rule.displayName else rule.title
                item.type = "video"
                out.add(item)
            } catch (e: Throwable) {
                Log.d(MiniAppHost.TAG, "search item skipped: $e")
            }
        }
        return out
    }

    fun resolveLazy(rule: MiniAppRule, url: String, position: Int = 0): LazyResult {
        val parts = url.split("@lazyRule=")
        if (parts.size < 2) {
            return LazyResult.Play(url, emptyMap())
        }
        var lazyTag = "@lazyRule=" + MiniAppText.arrayToString(parts.toTypedArray(), 1, "@lazyRule=")
        lazyTag = lazyTag.replace("#noLoading#", "")
        val ruleParts = lazyTag.split("@lazyRule=")
        val ruleText = if (ruleParts.size > 2) {
            "@lazyRule=" + MiniAppText.arrayToString(ruleParts.toTypedArray(), 1, "@lazyRule=")
        } else if (ruleParts.size == 2) {
            ruleParts[1]
        } else {
            return LazyResult.Error("动态解析规则有误")
        }
        val base = parts[0]
        return parseLazyRule(rule, ruleText, base, position)
    }

    private fun parseLazyRule(rule: MiniAppRule, ruleText: String, base: String, position: Int): LazyResult {
        var code = ruleText
        val prefix = when {
            code.startsWith(".js:") -> ".js:"
            code.startsWith("js:") -> "js:"
            else -> null
        }
        if (prefix != null) {
            val stripped = code.removePrefix(prefix)
            val result = host.evalLazy(rule, base, position, stripped, base)
            if (result.startsWith("error:")) {
                return LazyResult.Error(result)
            }
            // 分类按钮常见：refreshPage() 后 return 'hiker://empty' 或无返回值
            if (result == "undefined" || result.isEmpty() || result == "null") {
                return LazyResult.Refresh
            }
            return splitPlayUrl(result)
        }
        val fetchUrl = MiniAppHttp.getRealUrlFilterHeaders(base)
        val headers = MiniAppHttp.getHeaders(base, jsEvalFn())
        val html = try {
            MiniAppHttp.fetch(
                url = fetchUrl,
                headers = headers ?: emptyMap(),
                ruleTitle = rule.title,
            ).body
        } catch (e: Throwable) {
            return LazyResult.Error("网络请求失败：" + e.message)
        }
        return try {
            val doc = Jsoup.parse(html)
            val chain = code.split("&&")
            var el = MiniAppSelector.getTrueElement(chain[0], doc)
            for (i in 1 until chain.size - 1) {
                el = MiniAppSelector.getTrueElement(chain[i], el)
            }
            val movieRule = MiniMovieRule()
            movieRule.baseUrl = MiniAppText.getBaseUrl(base) ?: ""
            movieRule.chapterUrl = base
            val url = MiniAppSelector.getUrl(el, chain[chain.size - 1], movieRule, base, jsEvalFn())
            if (url.isEmpty()) {
                LazyResult.Error("动态解析无结果")
            } else {
                splitPlayUrl(url)
            }
        } catch (e: Throwable) {
            LazyResult.Error("动态解析失败：" + e.message)
        }
    }

    // lazyRule / 动态解析的返回值分类：空、hiker://empty（无 @rule）→ 刷新
    // 当前页；toast:// → 提示；hiker:// 其它 → 继续路由；其它 → 播放地址
    // （去掉 #isVideo=true# 等海阔后缀）。
    internal fun classifyLazyReturn(raw: String): LazyResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "undefined" || trimmed == "null") {
            return LazyResult.Refresh
        }
        if (trimmed.startsWith("toast://")) {
            return LazyResult.Toast(trimmed.removePrefix("toast://"))
        }
        // WebView 嗅探页：交给 Route → SniffPlay
        if (trimmed.startsWith("webview://") || trimmed.startsWith("x5://")) {
            return LazyResult.Route(trimmed)
        }
        if (trimmed.startsWith("hiker://")) {
            // 带 @rule= / @lazyRule= 的虚拟详情/二级页：交给路由
            if (trimmed.contains("@rule=") || trimmed.contains("@lazyRule=")) {
                return LazyResult.Route(trimmed)
            }
            // hiker://empty / hiker://empty##xxx / hiker://empty# 仅作刷新信号
            val rest = trimmed.removePrefix("hiker://")
            if (rest == "empty" || rest.startsWith("empty#") || rest.startsWith("empty?")) {
                return LazyResult.Refresh
            }
            return LazyResult.Route(trimmed)
        }
        var play = trimmed
        play = play.replace("#isVideo=true#", "")
        play = play.replace("#isVideo=true", "")
        if (play.endsWith("#")) play = play.dropLast(1)
        val headers = MiniAppHttp.getHeaders(play, jsEvalFn()) ?: emptyMap()
        val url = MiniAppHttp.getRealUrlFilterHeaders(play)
        if (url.startsWith("hiker://")) {
            return classifyLazyReturn(url)
        }
        return LazyResult.Play(url, headers)
    }

    // 先直连（用户 2026-10-25 17:30 定）：链接看着是直链直接播，
    // 不是直链（播放页、接口）走嗅探；非 http（magnet/thunder/
    // ftp/ed2k/video://）WebView 载不了，仍直接播。嗅探失败时
    // Activity 侧按直链判据兜底直播（见 RunActivity）。
    internal fun playOrSniff(url: String, headers: Map<String, String>): ItemAction {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return ItemAction.Play(url, headers)
        }
        return if (isMiniAppDirectVideoUrl(url)) ItemAction.Play(url, headers) else ItemAction.SniffPlay(url, headers)
    }

    private fun splitPlayUrl(raw: String): LazyResult {
        return classifyLazyReturn(raw)
    }

    fun lastChapter(rule: MiniAppRule, url: String): String? {
        if (rule.lastChapterRule.isEmpty()) return null
        val exec = host.runLastChapter(rule, url, rule.lastChapterRule, null)
        if (exec.error != null) return null
        return exec.strResult
    }

    fun routePage(rule: MiniAppRule, pageUrl: String, item: MiniAppListItem? = null): MiniAppRule? {
        return try {
            val params = MiniAppHttp.getParamsByUrl(pageUrl)
            val ruleTitle = params["rule"]
            if (!ruleTitle.isNullOrEmpty()) {
                val stored = com.github.tvbox.osc.miniapp.MiniAppStore.items.value
                    .firstOrNull { it.name == ruleTitle }
                val target = stored?.let { MiniAppRule.fromJson(it.ruleJson) }
                if (target != null) {
                    val params2 = MiniAppHttp.getParamsByUrl(pageUrl)
                    val paramUrl = params2["url"]
                    if (paramUrl != null) target.url = paramUrl
                    return target
                }
                return null
            }
            val queryStart = pageUrl.indexOf('?')
            var path = pageUrl.removePrefix("hiker://page/")
            if (queryStart >= 0) {
                path = pageUrl.substring("hiker://page/".length, queryStart)
            }
            val semi = path.indexOf(';')
            if (semi >= 0) path = path.substring(0, semi)
            val hash = path.indexOf('#')
            if (hash >= 0) path = path.substring(0, hash)
            val page = rule.pageList.firstOrNull { it.path == path } ?: return null
            val next = rule.copy()
            next.findRule = page.rule
            if (page.colType.isNotEmpty() && page.colType != "*") {
                next.colType = page.colType
            }
            val paramUrl = params["url"]
            next.url = paramUrl ?: pageUrl
            // MY_PARAMS：63 改回 61 口径：点中项 extra（JSON 才进）优先，
            // 其次链接 query 参数拼 JSON；都没有时继承父页 params，不再清空。
            val extra = item?.extra?.trim().orEmpty()
            val extraJsonOk = (extra.startsWith("{") || extra.startsWith("[")) && try {
                if (extra.startsWith("{")) JSONObject(extra) else org.json.JSONArray(extra)
                true
            } catch (e: Throwable) {
                false
            }
            if (extraJsonOk) {
                next.params = item!!.extra
            } else {
                val o = JSONObject()
                for ((k, v) in params) {
                    if (k != "url" && k != "rule") o.put(k, v)
                }
                if (o.length() > 0) next.params = o.toString()
            }
            next
        } catch (e: Throwable) {
            null
        }
    }

    // 分流顺序（海阔 ArticleListFragment.clickItem 同款）：空链接→页链接→
    // @lazyRule（空/虚拟→刷新、toast→提示、hiker→路由）→@rule（纯 require
    // 展开子页、其余透传不 eval）→hiker 路由页→详情→媒体/http→纯虚拟
    // 占位刷新；无 @rule 的 hiker 链接落空时继续往详情走，不在 hiker 处
    // 直接判死刑。
    fun actionForItem(rule: MiniAppRule, item: MiniAppListItem, level: Int = 1): ItemAction {
        val url = item.url
        if (url.isEmpty()) {
            return ItemAction.Nothing("空链接")
        }
        if (url.startsWith("hiker://page/")) {
            return ItemAction.OpenPage(url, item)
        }
        // WebView / X5 嗅探播放（可带 @lazyRule，先剥协议再嗅探页）
        if (url.startsWith("webview://") || url.startsWith("x5://")) {
            return sniffActionFromUrl(url)
        }
        if (url.contains("@lazyRule=")) {
            return when (val lazy = resolveLazy(rule, url)) {
                is LazyResult.Play -> playOrSniff(lazy.url, lazy.headers)
                is LazyResult.Error -> ItemAction.Nothing(lazy.message)
                is LazyResult.Refresh -> ItemAction.Refresh
                is LazyResult.Toast -> ItemAction.Toast(lazy.message)
                is LazyResult.Route -> routeLazyUrl(rule, item, lazy.url, level)
            }
        }
        if (url.contains("@rule=")) {
            actionForRuleUrl(rule, item, url)?.let { return it }
        }
        if (url.startsWith("hiker://")) {
            val parsed = parseHikerRuleUrl(url)
            val routed = routePage(rule, "hiker://page/" + parsed.path, item)
            if (routed != null) return ItemAction.OpenList(routed)
        }
        if (level == 1 && detailBranchApplies(url, rule.detailFindRule)) {
            return ItemAction.OpenList(rule.copyForDetail(item, 1))
        }
        if (level == 2 && detailBranchApplies(url, rule.sdetailFindRule)) {
            return ItemAction.OpenList(rule.copyForDetail(item, 2))
        }
        if (url.startsWith("video://") || url.startsWith("http") || url.startsWith("magnet") ||
            url.startsWith("thunder") || url.startsWith("ftp") || url.startsWith("ed2k")
        ) {
            return when (val lazy = resolveLazy(rule, url)) {
                is LazyResult.Play -> playOrSniff(lazy.url, lazy.headers)
                is LazyResult.Error -> ItemAction.Nothing(lazy.message)
                is LazyResult.Refresh -> ItemAction.Refresh
                is LazyResult.Toast -> ItemAction.Toast(lazy.message)
                is LazyResult.Route -> routeLazyUrl(rule, item, lazy.url, level)
            }
        }
        if (url.startsWith("hiker://")) {
            // 纯虚拟占位（分类刷新信号之类）：刷新当前页，不报错、不黑屏。
            val path = parseHikerRuleUrl(url).path
            if (path == "empty" || path.isEmpty() || path.startsWith("empty")) {
                return ItemAction.Refresh
            }
            return ItemAction.Nothing("不支持的 hiker 链接：$url")
        }
        return ItemAction.Nothing("无法播放该链接")
    }

    // 海阔 header 筛选（onClassClick 同款）：筛选项点按不走链接路由，改写
    // 规则的 <维度>_url/<维度>_name 与 firstHeader 后同页重载（页码归 1）。
    // 只认 desc 能映射到维度的 header 项；@rule 链接照旧走路由（v1 已验
    // 证的过滤网格就是 @rule 出的），不被筛选截走。
    fun isHeaderFilter(item: MiniAppListItem): Boolean {
        return item.type == "header" && headerDimension(item.desc) != null &&
            !item.url.contains("@rule=")
    }

    fun applyHeaderFilter(rule: MiniAppRule, item: MiniAppListItem, tabIndex: Int): MiniAppRule? {
        val dim = headerDimension(item.desc) ?: return null
        val (name, value) = if (tabIndex >= 0) {
            val names = item.title.split("&")
            val values = item.url.split("&")
            if (tabIndex >= names.size || tabIndex >= values.size) return null
            names[tabIndex] to values[tabIndex]
        } else {
            item.title to item.url
        }
        val c = rule.copy()
        when (dim) {
            "class" -> {
                c.className = name
                c.classUrl = value
            }
            "year" -> {
                c.yearName = name
                c.yearUrl = value
            }
            "area" -> {
                c.areaName = name
                c.areaUrl = value
            }
            "sort" -> {
                c.sortName = name
                c.sortUrl = value
            }
        }
        c.firstHeader = dim
        return c
    }

    // @rule 处理（海阔 dealRule）：纯 $.require("name") 先展开子页面规则
    // （补丁包的 erji 手段）并立即跑一次，出列表直接给页、不出再交 loadHome；
    // 其余 @rule 透传不 eval，直接作新页 find_rule（MY_URL=@rule 前的 URL、
    // MY_PARAMS=点中项 extra）。ruleCode 为空时返回 null、由调用方继续走。
    private fun actionForRuleUrl(rule: MiniAppRule, item: MiniAppListItem, url: String): ItemAction? {
        if (!url.contains("@rule=")) return null
        val ruleCode = url.substring(url.indexOf("@rule=") + "@rule=".length)
        if (ruleCode.isEmpty()) return null
        val preUrl = url.substring(0, url.indexOf("@rule="))
        if (ruleCode.startsWith("js:")) {
            val expanded = expandPureRequire(rule, ruleCode)
            if (expanded != null) {
                val copy = rule.copy()
                copy.findRule = expanded
                copy.url = preUrl
                applyItemExtra(copy, item)
                val exec = host.runHome(copy, preUrl, copy.findRule, mapOf("MY_PAGE" to 1))
                if (exec.error == null) {
                    val scripted = exec.finalItems()
                    if (!scripted.isNullOrEmpty()) {
                        return ItemAction.OpenItems(copy, applyHomePostProcess(scripted, copy), exec.pageTitle)
                    }
                }
                return ItemAction.OpenList(copy)
            }
        }
        val arrowParts = ruleCode.split("==>")
        var ruleBody = arrowParts[0]
        val suffix = if (arrowParts.size > 1) {
            arrowParts.drop(1).joinToString("==>")
        } else {
            ""
        }
        var colType = rule.colType
        if (ruleBody.startsWith("js:")) {
            val segs = ruleBody.split(";")
            if (segs.size == 6) {
                colType = segs[5]
                ruleBody = segs.subList(0, 5).joinToString(";")
            }
        }
        val copy = rule.copy()
        copy.findRule = if (suffix.isEmpty()) ruleBody else ruleBody + "==>" + suffix
        copy.colType = colType
        copy.url = preUrl
        if (item.extra.isNotEmpty()) copy.params = item.extra
        return ItemAction.OpenList(copy)
    }

    // 纯 $.require("name") 展开：返回子页面规则原文（js: 原样、HTML 原样，
    // 不像补丁包那样强行加 js: 前缀）；非纯 require 或找不到子页返回 null。
    internal fun expandPureRequire(rule: MiniAppRule, ruleCode: String): String? {
        if (!ruleCode.startsWith("js:")) return null
        val code = ruleCode.removePrefix("js:").trim()
        val m = Regex("""^\$\.require\(\s*["']([^"']+)["']\s*\);?\s*$""").find(code) ?: return null
        val pageRule = findRequirePage(rule, m.groupValues[1])?.rule ?: return null
        if (pageRule.isEmpty()) return null
        return pageRule
    }

    private fun applyItemExtra(rule: MiniAppRule, item: MiniAppListItem) {
        if (item.extra.isEmpty()) return
        val t = item.extra.trim()
        val jsonOk = (t.startsWith("{") || t.startsWith("[")) && try {
            if (t.startsWith("{")) JSONObject(t) else org.json.JSONArray(t)
            true
        } catch (e: Throwable) {
            false
        }
        if (jsonOk) rule.params = item.extra
    }

    // lazy 返回的 Route：webview://x5:// 先走嗅探；@rule= 交给 @rule 逻辑；
    // 含 @lazyRule= 的先剥掉标签再走，避免重复解析死循环；其余直接按该
    // 链接再分流一次。
    private fun routeLazyUrl(rule: MiniAppRule, item: MiniAppListItem, routeUrl: String, level: Int): ItemAction {
        if (routeUrl.startsWith("webview://") || routeUrl.startsWith("x5://")) {
            return sniffActionFromUrl(routeUrl)
        }
        if (routeUrl.contains("@rule=")) {
            actionForRuleUrl(rule, item, routeUrl)?.let { return it }
        }
        val clean = if (routeUrl.contains("@lazyRule=")) {
            routeUrl.substring(0, routeUrl.indexOf("@lazyRule="))
        } else {
            routeUrl
        }
        return actionForItem(rule, item.copy(url = clean), level)
    }

    // WebView 嗅探动作：剥 webview://x5:// 协议与 @lazyRule/@rule/#sniff
    // 标记；已是直链（m3u8/mp4/flv/magnet/thunder）直接播，否则交嗅探。
    internal fun sniffActionFromUrl(raw: String): ItemAction {
        var s = raw.trim()
        val headers = MiniAppHttp.getHeaders(s, jsEvalFn()) ?: emptyMap()
        if (s.startsWith("webview://")) s = s.removePrefix("webview://")
        if (s.startsWith("x5://")) s = s.removePrefix("x5://")
        s = s.split("@lazyRule=")[0].split("@rule=")[0]
        s = s.replace("##sniff", "").replace("#sniff", "")
        s = s.substringBefore("##noSniff").substringBefore("#noSniff")
        if (s.endsWith("#")) s = s.dropLast(1)
        s = s.trim()
        if (s.isEmpty()) {
            return ItemAction.Nothing("WebView 地址为空")
        }
        val lower = s.lowercase()
        if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv") ||
            lower.startsWith("magnet:") || lower.startsWith("thunder")
        ) {
            return ItemAction.Play(s, headers)
        }
        return ItemAction.SniffPlay(s, headers)
    }

    fun playDataForUrl(raw: String): Pair<String, Map<String, String>> {
        val headers = MiniAppHttp.getHeaders(raw, jsEvalFn()) ?: emptyMap()
        val url = MiniAppHttp.getRealUrlFilterHeaders(raw)
        return Pair(url, headers)
    }
}

// 嗅探失败时的直链兜底判据（嗅探器同款直链集＋常见容器）：
    // 原地址是直链媒体时嗅探失败仍可直接播，播放页则不行。
    internal fun isMiniAppDirectVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv") ||
            lower.contains(".mkv") || lower.contains(".mov") || lower.contains(".webm") ||
            lower.contains(".m4v") || lower.contains(".avi") ||
            lower.startsWith("magnet:") || lower.startsWith("thunder")
    }

    // 海阔规则页链接：hiker://<path>#旗标#...#@rule=<规则>，旗标如 immersiveTheme/gameTheme/autoCache，
// @rule=js:CODE 为二级规则 JS（常为 $.require("子页面名")），@rule=<直写规则> 则直接作 find_rule。
// @rule 的 # 在 JS 串里也可能出现，故先找 @rule= 再切旗标，不按 # 全切。
data class HikerRuleLink(
    val path: String,
    val flags: List<String>,
    val ruleCode: String?,
)

internal fun parseHikerRuleUrl(url: String): HikerRuleLink {
    val rest = url.removePrefix("hiker://")
    val ruleIdx = rest.indexOf("@rule=")
    val head = if (ruleIdx >= 0) rest.substring(0, ruleIdx) else rest
    val ruleCode = if (ruleIdx >= 0) rest.substring(ruleIdx + "@rule=".length) else null
    val parts = head.split("#")
    val flags = parts.drop(1).filter { it.isNotEmpty() }
    return HikerRuleLink(parts[0], flags, ruleCode)
}

// 详情分支判据（海阔 clickItem 同款）：规则有详情规则、链接不是 video://，
// 与链接是否虚拟、详情是 JS 还是 HTML 都无关；虚拟＋HTML 详情在详情页
// 抓取时才失败（海阔同款边界），不在分流处拦。
internal fun detailBranchApplies(url: String, findRule: String): Boolean {
    if (findRule.isEmpty()) return false
    if (url.startsWith("video://")) return false
    return true
}

// 海阔 header 项的 desc 即维度；firstHeader 取维度名（parsePageClassUrl
// 按它选 fyAll 填哪个字段），不是点中的值。
internal fun headerDimension(desc: String): String? {
    return when (desc.trim().lowercase()) {
        "class", "类型", "分类" -> "class"
        "year", "年份", "年" -> "year"
        "area", "地区", "区域" -> "area"
        "sort", "排序" -> "sort"
        else -> null
    }
}

internal fun headerFieldValue(rule: MiniAppRule, desc: String): String {
    return when (headerDimension(desc)) {
        "class" -> rule.classUrl
        "year" -> rule.yearUrl
        "area" -> rule.areaUrl
        "sort" -> rule.sortUrl
        else -> ""
    }
}

// hiker://empty#<真实地址>@rule=... 里的真实地址；没有 http 基地址返回 null。
internal fun hikerBaseUrl(url: String): String? {
    if (!url.startsWith("hiker://empty")) return null
    val after = url.removePrefix("hiker://empty").removePrefix("#")
    val base = after.split("@rule=")[0].split("@lazyRule=")[0]
    return base.takeIf { it.startsWith("http") }
}
