package com.github.tvbox.osc.miniapp.engine

import androidx.test.core.app.ApplicationProvider
import com.github.tvbox.osc.reading.NoBootApplication
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mini-app rule engine (logtest45): pure-Kotlin logic only.
 *
 * Deliberate boundary: Robolectric runs on an x86 JVM where the arm64
 * QuickJS JNI cannot load, so `.js:` suffix evaluation and QuickJS
 * bindings are NOT covered here — they are true-device items. Every
 * test below uses selector rules without `.js:` so jsEval is never
 * invoked. Fixtures are synthetic; no network, no real rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = NoBootApplication::class)
class MiniAppEngineTest {

    private fun engine(): MiniAppEngine =
        MiniAppEngine(ApplicationProvider.getApplicationContext())

    private fun listHtml(): String = """
        <div class="list">
          <div class="item"><a href="/v/1"><img src="/i/1.jpg"><span class="t">Alpha</span><span class="d">D1</span></a></div>
          <div class="item"><a href="/v/2"><img src="/i/2.jpg"><span class="t">Beta</span><span class="d">D2</span></a></div>
        </div>
    """.trimIndent()

    // ---- Selector semantics ----

    @Test
    fun getTrueElement_singleSelector() {
        val doc = Jsoup.parse(listHtml())
        val first = MiniAppSelector.getTrueElement("div.item", doc)
        assertEquals("div", first?.tagName())
        assertEquals("Alpha", first?.select("span.t")?.text())
    }

    @Test
    fun getTrueElement_orFallback_firstNonEmptyWins() {
        val doc = Jsoup.parse(listHtml())
        val el = MiniAppSelector.getTrueElement("span.missing||div.item", doc)
        assertTrue(el != null)
        assertEquals("div", el?.tagName())
    }

    @Test
    fun selectElements_rangeAndNoColonSwallow() {
        val doc = Jsoup.parse(listHtml())
        val list = MiniAppSelector.getTrueElement("div.list", doc)
        assertEquals(2, MiniAppSelector.selectElements(list, "div.item,0:2").size)
        assertEquals(1, MiniAppSelector.selectElements(list, "div.item,1:2").size)
        // No ":" after "," throws inside selectElementsWithoutOr and is
        // swallowed per segment by selectElements (original behaviour).
        assertEquals(0, MiniAppSelector.selectElements(list, "div.item,1").size)
    }

    @Test
    fun getTextByRule_textAndQuotedLiteral() {
        val doc = Jsoup.parse(listHtml())
        val first = MiniAppSelector.getTrueElement("div.item", doc)
        assertEquals("Alpha", MiniAppSelector.getTextByRule(first, "a&&span.t&&Text"))
        assertEquals("Hello", MiniAppSelector.getTextByRule(first, "\"Hello\""))
        assertEquals("", MiniAppSelector.getTextByRule(first, ""))
        assertEquals("", MiniAppSelector.getTextByRule(first, "*"))
    }

    @Test
    fun getUrl_absoluteAndRelativeAndMagnet() {
        val doc = Jsoup.parse(listHtml())
        val item = MiniAppSelector.getTrueElement("div.item", doc)
        val a = MiniAppSelector.getTrueElement("a", item)
        val movieRule = MiniMovieRule()
        movieRule.chapterUrl = "https://site.com/list"
        assertEquals(
            "https://site.com/v/1",
            MiniAppSelector.getUrl(a, "href", movieRule, "https://site.com/list"),
        )
        // The join base is the chapterUrl argument, not the rule's base.
        assertEquals(
            "https://other.com/v/1",
            MiniAppSelector.getUrl(a, "href", movieRule, "https://other.com/x"),
        )
        val magnetDoc = Jsoup.parse("<a href=\"magnet:?xt=urn:btih:abc\">M</a>")
        val m = MiniAppSelector.getTrueElement("a", magnetDoc)
        assertEquals(
            "magnet:?xt=urn:btih:abc",
            MiniAppSelector.getUrl(m, "href", movieRule, "https://site.com/list"),
        )
    }

    @Test
    fun getUrlByRule_starReturnsBase() {
        val doc = Jsoup.parse(listHtml())
        val el = MiniAppSelector.getTrueElement("div.item", doc)
        assertEquals(
            "https://site.com/list",
            MiniAppSelector.getUrlByRule("https://site.com/list", MiniMovieRule(), el, "*"),
        )
    }

    // ---- Home findList (smali-corrected order: list;title;pic;desc;link) ----

    @Test
    fun findListHome_correctedFieldOrder() {
        val rule = MiniAppRule()
        rule.url = "https://site.com/list"
        rule.findRule = "div.list&&div.item;a&&span.t&&Text;img&&src;span.d&&Text;a&&href"
        val items = engine().findListHome(rule, listHtml(), "https://site.com/list")
        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("Alpha", first.title)
        assertEquals("https://site.com/i/1.jpg", first.pic)
        assertEquals("D1", first.desc)
        assertEquals("https://site.com/v/1", first.url)
        assertEquals("movie_3", first.type)
        assertEquals("Beta", items[1].title)
    }

    @Test
    fun findListHome_starPicWithMovie1_becomesText1() {
        val rule = MiniAppRule()
        rule.url = "https://site.com/list"
        rule.colType = "movie_1"
        rule.findRule = "div.list&&div.item;a&&span.t&&Text;*;span.d&&Text;a&&href"
        val items = engine().findListHome(rule, listHtml(), "https://site.com/list")
        assertEquals(2, items.size)
        assertEquals("*", items[0].pic)
        assertEquals("text_1", items[0].type)
    }

    @Test
    fun findListHome_ruleSuffixFyIndex_perItem() {
        val rule = MiniAppRule()
        rule.url = "https://site.com/list"
        rule.findRule = "div.list&&div.item;a&&span.t&&Text;img&&src;span.d&&Text;a&&href==>page?index=fyIndex"
        val items = engine().findListHome(rule, listHtml(), "https://site.com/list")
        assertEquals(2, items.size)
        assertTrue(items[0].url.endsWith("@rule=page?index=0"))
        assertTrue(items[1].url.endsWith("@rule=page?index=1"))
    }

    @Test
    fun findListHome_missingDescSegment_skipsItem() {
        val rule = MiniAppRule()
        rule.url = "https://site.com/list"
        rule.findRule = "div.list&&div.item;a&&span.t&&Text;img&&src"
        val items = engine().findListHome(rule, listHtml(), "https://site.com/list")
        assertTrue(items.isEmpty())
    }

    // ---- Search findList (list;title;link;descMore;content;pic) ----

    @Test
    fun findListSearch_fieldOrderAndDisplayDesc() {
        val html = """
            <div class="list">
              <div class="item"><a href="/s/1">One</a><span class="d">DM</span><div class="c">CT</div><img src="/p/1.png"></div>
            </div>
        """.trimIndent()
        val rule = MiniAppRule()
        rule.title = "RuleTitle"
        rule.displayName = "DisplayName"
        rule.url = "https://site.com/search"
        rule.searchFind = "div.list&&div.item;a&&Text;a&&href;span.d&&Text;div.c&&Text;img&&src"
        val movieRule = MiniMovieRule()
        movieRule.chapterUrl = "https://site.com/search"
        val items = engine().findListSearch(movieRule, rule, html, "https://site.com/search")
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("One", item.title)
        assertEquals("https://site.com/s/1", item.url)
        assertEquals("DM", item.descMore)
        assertEquals("CT", item.content)
        assertEquals("https://site.com/p/1.png", item.pic)
        assertEquals("video", item.type)
        assertEquals("DisplayName", item.desc)
    }

    // ---- Lazy split (no network, no JS) ----

    @Test
    fun resolveLazy_noLazyTag_playsOriginal() {
        val result = engine().resolveLazy(MiniAppRule(), "https://cdn.example.com/v.mp4")
        assertTrue(result is MiniAppEngine.LazyResult.Play)
        assertEquals(
            "https://cdn.example.com/v.mp4",
            (result as MiniAppEngine.LazyResult.Play).url,
        )
    }

    @Test
    fun actionForItem_routes() {
        val eng = engine()
        val rule = MiniAppRule()
        val pageItem = MiniAppListItem(url = "hiker://page/detail")
        assertTrue(eng.actionForItem(rule, pageItem, 1) is MiniAppEngine.ItemAction.OpenPage)
        val videoItem = MiniAppListItem(url = "video://play/1")
        val play = eng.actionForItem(rule, videoItem, 1)
        assertTrue(play is MiniAppEngine.ItemAction.Play)
        val emptyItem = MiniAppListItem(url = "")
        assertTrue(eng.actionForItem(rule, emptyItem, 1) is MiniAppEngine.ItemAction.Nothing)
    }

    // ---- Pagination template ----

    @Test
    fun parsePageClassUrl_templates() {
        val rule = MiniAppRule()
        rule.classUrl = "action"
        assertEquals(
            "https://s.com/list?page=2",
            MiniAppSelector.parsePageClassUrl("https://s.com/list?page=fypage", 2, rule, null),
        )
        assertEquals(
            "https://s.com/p/5.html",
            MiniAppSelector.parsePageClassUrl("https://s.com/p/fypage@+2@.html", 3, rule, null),
        )
        assertEquals(
            "https://s.com/first",
            MiniAppSelector.parsePageClassUrl(
                "https://s.com/list[firstPage=https://s.com/first]",
                1,
                rule,
                null,
            ),
        )
        assertEquals(
            "https://s.com/list?page=2;post",
            MiniAppSelector.parsePageClassUrl("https://s.com/list?page=fypage;post", 2, rule, null),
        )
        assertEquals(
            "https://s.com/c/action",
            MiniAppSelector.parsePageClassUrl("https://s.com/c/fyclass", 1, rule, null),
        )
    }

    // ---- Rule JSON ----

    @Test
    fun ruleJson_roundTripAndPageList() {
        val rule = MiniAppRule()
        rule.title = "T"
        rule.url = "https://s.com/"
        rule.findRule = "div&&a"
        rule.colType = "movie_3"
        rule.pages = "[{\"path\":\"detail\",\"rule\":\"div&&a\",\"col_type\":\"movie_1\"}]"
        val parsed = MiniAppRule.fromJson(rule.toRuleJson().toString())
        assertTrue(parsed != null)
        assertEquals("T", parsed?.title)
        assertEquals("div&&a", parsed?.findRule)
        assertEquals(1, parsed?.pageList?.size)
        assertEquals("detail", parsed?.pageList?.get(0)?.path)
        assertEquals("movie_1", parsed?.pageList?.get(0)?.colType)

        val noBracket = MiniAppRule()
        noBracket.pages = "not-json"
        assertTrue(noBracket.pageList.isEmpty())
        assertTrue(MiniAppRule.fromJson("not json") == null)
    }

    @Test
    fun copyForDetail_level2UsesSdetail() {
        val rule = MiniAppRule()
        rule.title = "T"
        rule.url = "https://s.com/"
        rule.detailFindRule = "div&&a"
        rule.sdetailFindRule = "span&&a"
        rule.params = "old"
        val item = MiniAppListItem(url = "https://s.com/v/1", extra = "{\"id\":\"7\"}")
        val l1 = rule.copyForDetail(item, 1)
        assertEquals("div&&a", l1.findRule)
        assertEquals("https://s.com/v/1", l1.url)
        assertEquals("{\"id\":\"7\"}", l1.params)
        assertEquals("", l1.detailFindRule)
        assertEquals("", l1.sdetailFindRule)
        val l2 = rule.copyForDetail(item, 2)
        assertEquals("span&&a", l2.findRule)
        val plain = MiniAppListItem(url = "https://s.com/v/1", extra = "not-json")
        assertEquals("old", rule.copyForDetail(plain, 1).params)
    }

    @Test
    fun actionForItem_virtualVideoGoesToDetail() {
        val engine = MiniAppEngine(ApplicationProvider.getApplicationContext())
        val rule = MiniAppRule()
        rule.title = "T"
        rule.detailFindRule = "js:detail"
        rule.detailColType = "movie_1"
        val item = MiniAppListItem(url = "hiker://empty##fypage", title = "V")
        val action = engine.actionForItem(rule, item, 1)
        assertTrue(action is MiniAppEngine.ItemAction.OpenList)
        val detail = (action as MiniAppEngine.ItemAction.OpenList).rule
        assertEquals("js:detail", detail.findRule)
        assertEquals("hiker://empty##fypage", detail.url)
    }

    @Test
    fun actionForItem_hikerRuleBeforeDetail() {
        val eng = engine()
        val rule = MiniAppRule()
        rule.detailFindRule = "js:detail"
        val item = MiniAppListItem(
            url = "hiker://empty##1@rule=div&&a",
            title = "V",
            extra = "{\"id\":\"7\"}",
        )
        val action = eng.actionForItem(rule, item, 1)
        assertTrue(action is MiniAppEngine.ItemAction.OpenList)
        val opened = (action as MiniAppEngine.ItemAction.OpenList).rule
        assertEquals("div&&a", opened.findRule)
        assertEquals("hiker://empty##1", opened.url)
        assertEquals("{\"id\":\"7\"}", opened.params)
    }

    @Test
    fun actionForItem_hikerRuleJsPassthrough() {
        val eng = engine()
        val rule = MiniAppRule()
        rule.detailFindRule = "js:detail"
        val item = MiniAppListItem(
            url = "hiker://empty##1@rule=js:detailCode",
            title = "V",
            extra = "{\"id\":\"7\"}",
        )
        val action = eng.actionForItem(rule, item, 1)
        assertTrue(action is MiniAppEngine.ItemAction.OpenList)
        val opened = (action as MiniAppEngine.ItemAction.OpenList).rule
        assertEquals("js:detailCode", opened.findRule)
        assertEquals("hiker://empty##1", opened.url)
        assertEquals("{\"id\":\"7\"}", opened.params)
    }

    @Test
    fun actionForItem_hikerRuleSuffixAndColtype() {
        val eng = engine()
        val rule = MiniAppRule()
        rule.colType = "movie_3"
        val item = MiniAppListItem(
            url = "hiker://empty##1@rule=js:a;b;c;d;e;movie_1==>suffixRule",
            title = "V",
        )
        val action = eng.actionForItem(rule, item, 1)
        assertTrue(action is MiniAppEngine.ItemAction.OpenList)
        val opened = (action as MiniAppEngine.ItemAction.OpenList).rule
        assertEquals("js:a;b;c;d;e==>suffixRule", opened.findRule)
        assertEquals("movie_1", opened.colType)
        assertEquals("hiker://empty##1", opened.url)
    }

    @Test
    fun classifyLazyReturn_branches() {
        val eng = engine()
        assertTrue(eng.classifyLazyReturn("") is MiniAppEngine.LazyResult.Refresh)
        assertTrue(eng.classifyLazyReturn("undefined") is MiniAppEngine.LazyResult.Refresh)
        assertTrue(eng.classifyLazyReturn("null") is MiniAppEngine.LazyResult.Refresh)
        val toast = eng.classifyLazyReturn("toast://hi")
        assertTrue(toast is MiniAppEngine.LazyResult.Toast)
        assertEquals("hi", (toast as MiniAppEngine.LazyResult.Toast).message)
        assertTrue(eng.classifyLazyReturn("hiker://empty") is MiniAppEngine.LazyResult.Refresh)
        assertTrue(eng.classifyLazyReturn("hiker://empty##1") is MiniAppEngine.LazyResult.Refresh)
        val route = eng.classifyLazyReturn("hiker://empty##1@rule=js:x")
        assertTrue(route is MiniAppEngine.LazyResult.Route)
        assertEquals("hiker://empty##1@rule=js:x", (route as MiniAppEngine.LazyResult.Route).url)
        assertTrue(eng.classifyLazyReturn("hiker://other") is MiniAppEngine.LazyResult.Route)
    }

    @Test
    fun expandPureRequire_cases() {
        val eng = engine()
        val rule = MiniAppRule()
        rule.pages = "[{\"path\":\"erji\",\"rule\":\"js:erjiCode\"}," +
            "{\"path\":\"htmlpage\",\"rule\":\"div&&a\"}]"
        assertEquals("js:erjiCode", eng.expandPureRequire(rule, "js:\$.require(\"erji\")"))
        assertEquals("js:erjiCode", eng.expandPureRequire(rule, "js:\$.require('erji');"))
        assertEquals("div&&a", eng.expandPureRequire(rule, "js:\$.require(\"htmlpage\")"))
        assertTrue(eng.expandPureRequire(rule, "js:foo();\$.require(\"erji\")") == null)
        assertTrue(eng.expandPureRequire(rule, "js:\$.require(\"missing\")") == null)
        assertTrue(eng.expandPureRequire(rule, "div&&a") == null)
    }

    @Test
    fun actionForItem_hikerEmptyRefresh() {
        val eng = engine()
        val rule = MiniAppRule()
        val item = MiniAppListItem(url = "hiker://empty##1", title = "V")
        val action = eng.actionForItem(rule, item, 1)
        assertTrue(action is MiniAppEngine.ItemAction.Refresh)
    }

    @Test
    fun sniffActionFromUrl_cases() {
        val eng = engine()
        val rule = MiniAppRule()
        val sniff = eng.sniffActionFromUrl("webview://https://s.com/p")
        assertTrue(sniff is MiniAppEngine.ItemAction.SniffPlay)
        assertEquals("https://s.com/p", (sniff as MiniAppEngine.ItemAction.SniffPlay).pageUrl)
        val direct = eng.sniffActionFromUrl("webview://https://s.com/v.m3u8")
        assertTrue(direct is MiniAppEngine.ItemAction.Play)
        assertEquals("https://s.com/v.m3u8", (direct as MiniAppEngine.ItemAction.Play).url)
        val x5 = eng.sniffActionFromUrl("x5://https://s.com/p#sniff")
        assertTrue(x5 is MiniAppEngine.ItemAction.SniffPlay)
        assertEquals("https://s.com/p", (x5 as MiniAppEngine.ItemAction.SniffPlay).pageUrl)
        val lazy = eng.sniffActionFromUrl("webview://https://s.com/p@lazyRule=js:x")
        assertTrue(lazy is MiniAppEngine.ItemAction.SniffPlay)
        assertEquals("https://s.com/p", (lazy as MiniAppEngine.ItemAction.SniffPlay).pageUrl)
        assertTrue(eng.sniffActionFromUrl("webview://") is MiniAppEngine.ItemAction.Nothing)
        val routed = eng.actionForItem(rule, MiniAppListItem(url = "webview://https://s.com/p", title = "V"), 1)
        assertTrue(routed is MiniAppEngine.ItemAction.SniffPlay)
    }

    @Test
    fun webSniffer_isVideoUrl_cases() {
        assertTrue(MiniAppWebSniffer.isVideoUrl("https://s.com/v.m3u8"))
        assertTrue(MiniAppWebSniffer.isVideoUrl("https://s.com/v.mp4?x=1"))
        assertTrue(MiniAppWebSniffer.isVideoUrl("https://s.com/v.flv"))
        assertTrue(MiniAppWebSniffer.isVideoUrl("https://s.com/p.html?u=x.m3u8"))
        assertTrue(!MiniAppWebSniffer.isVideoUrl("https://s.com/a.js"))
        assertTrue(!MiniAppWebSniffer.isVideoUrl("https://s.com/a.png"))
        assertTrue(!MiniAppWebSniffer.isVideoUrl("data:text/plain,hi"))
        assertTrue(!MiniAppWebSniffer.isVideoUrl("blob:https://s.com/x"))
    }

    @Test
    fun stripHtml_cases() {
        assertEquals(
            "简介：The fans",
            stripHtml("\"\"<small><font color=\"#098AC1\">简介：The fans"),
        )
        assertEquals("a & b", stripHtml("a &amp; b"))
        assertEquals("clean text", stripHtml("clean text"))
        assertEquals("", stripHtml("<br>"))
        assertEquals("", stripHtml(""))
    }

    @Test
    fun groupIntoBlocks_dropsLocalChipAndEmpty() {
        val items = listOf(
            MiniAppListItem(title = "云播3", type = "header", desc = "from", url = "u1"),
            MiniAppListItem(title = "本地", type = "header", desc = "from", url = "u2"),
            MiniAppListItem(title = "云播1", type = "header", desc = "from", url = "u3"),
            MiniAppListItem(type = "header"),
            MiniAppListItem(title = "本地", type = "movie_3", pic = "http://x/y.jpg", url = "u5"),
        )
        val blocks = groupIntoBlocks(items)
        assertEquals(2, blocks.size)
        val chips = blocks[0] as MiniAppBlock.Chips
        assertEquals(listOf("云播3", "云播1"), chips.items.map { it.title })
        val grid = blocks[1] as MiniAppBlock.Grid
        assertTrue(grid.rows.flatMap { it }.map { it.first.title }.contains("本地"))
    }

    @Test
    fun playOrSniff_directFirstSniffFallback() {
        val engine = MiniAppEngine(ApplicationProvider.getApplicationContext())
        val playM3u8 = engine.playOrSniff("https://s.com/hls/1/master.m3u8?x=1", emptyMap())
        assertTrue(playM3u8 is MiniAppEngine.ItemAction.Play)
        val playMp4 = engine.playOrSniff("https://s.com/v.mp4", mapOf("Referer" to "https://s.com/"))
        assertTrue(playMp4 is MiniAppEngine.ItemAction.Play)
        assertEquals("https://s.com/", (playMp4 as MiniAppEngine.ItemAction.Play).headers["Referer"])
        val sniffPage = engine.playOrSniff("https://site.com/play/123", emptyMap())
        assertTrue(sniffPage is MiniAppEngine.ItemAction.SniffPlay)
        val sniffApi = engine.playOrSniff("https://api.site.com/get?id=1", emptyMap())
        assertTrue(sniffApi is MiniAppEngine.ItemAction.SniffPlay)
        val playMagnet = engine.playOrSniff("magnet:?xt=urn:btih:abc", emptyMap())
        assertTrue(playMagnet is MiniAppEngine.ItemAction.Play)
    }

    @Test
    fun routePage_passesItemExtraAndStripsHash() {
        val engine = MiniAppEngine(ApplicationProvider.getApplicationContext())
        val rule = MiniAppRule()
        rule.pages = "[{\"col_type\":\"*\",\"name\":\"剧集\",\"path\":\"ep\",\"rule\":\"js:ep\"}]"
        rule.params = "{\"parent\":\"1\"}"
        val item = MiniAppListItem(title = "第1集", url = "hiker://page/ep", extra = "{\"id\":\"123\"}")
        val routed = engine.routePage(rule, "hiker://page/ep", item)
        assertNotNull(routed)
        assertEquals("js:ep", routed!!.findRule)
        assertEquals("{\"id\":\"123\"}", routed.params)
        val hashed = engine.routePage(rule, "hiker://page/ep#frag", null)
        assertNotNull(hashed)
        assertEquals("{\"parent\":\"1\"}", hashed!!.params)
        val queried = engine.routePage(rule, "hiker://page/ep?id=99", null)
        assertNotNull(queried)
        assertTrue(queried!!.params.contains("\"id\":\"99\""))
    }

    @Test
    fun routePage_exactPathOnly() {
        val engine = MiniAppEngine(ApplicationProvider.getApplicationContext())
        val rule = MiniAppRule()
        rule.pages = "[{\"col_type\":\"*\",\"name\":\"详情\",\"path\":\"detail\",\"rule\":\"js:d\"}]"
        val byPath = engine.routePage(rule, "hiker://page/detail", null)
        assertNotNull(byPath)
        assertEquals("js:d", byPath!!.findRule)
        val byName = engine.routePage(rule, "hiker://page/详情", null)
        assertTrue(byName == null)
    }

    @Test
    fun actionForItem_httpWithDetailGoesToDetail() {
        val eng = engine()
        val rule = MiniAppRule()
        rule.detailFindRule = "js:detail"
        val item = MiniAppListItem(url = "https://s.com/v/1", title = "V")
        val action = eng.actionForItem(rule, item, 1)
        assertTrue(action is MiniAppEngine.ItemAction.OpenList)
        assertEquals("js:detail", (action as MiniAppEngine.ItemAction.OpenList).rule.findRule)
    }

    @Test
    fun actionForItem_httpWithoutDetailPlays() {
        val eng = engine()
        val rule = MiniAppRule()
        val item = MiniAppListItem(url = "https://s.com/v/1.mp4", title = "V")
        val action = eng.actionForItem(rule, item, 1)
        assertTrue(action is MiniAppEngine.ItemAction.Play)
    }

    @Test
    fun applyHeaderFilter_mappings() {
        val eng = engine()
        val rule = MiniAppRule()
        val classItem = MiniAppListItem(title = "动作片", url = "action", type = "header", desc = "class")
        val filtered = eng.applyHeaderFilter(rule, classItem, -1)
        assertTrue(filtered != null)
        assertEquals("动作片", filtered?.className)
        assertEquals("action", filtered?.classUrl)
        assertEquals("class", filtered?.firstHeader)
        assertTrue(eng.isHeaderFilter(classItem))
        val yearItem = MiniAppListItem(title = "2026", url = "2026", type = "header", desc = "年份")
        val yearFiltered = eng.applyHeaderFilter(rule, yearItem, -1)
        assertEquals("2026", yearFiltered?.yearUrl)
        assertEquals("year", yearFiltered?.firstHeader)
        val tabItem = MiniAppListItem(
            title = "全部&动作片",
            url = "all&action",
            type = "header",
            desc = "class",
        )
        val tabFiltered = eng.applyHeaderFilter(rule, tabItem, 1)
        assertEquals("动作片", tabFiltered?.className)
        assertEquals("action", tabFiltered?.classUrl)
        val unknown = MiniAppListItem(title = "X", url = "x", type = "header", desc = "other")
        assertTrue(eng.applyHeaderFilter(rule, unknown, -1) == null)
        assertTrue(!eng.isHeaderFilter(unknown))
        val ruleItem = MiniAppListItem(
            title = "动作片",
            url = "hiker://empty##1@rule=div&&a",
            type = "header",
            desc = "class",
        )
        assertTrue(!eng.isHeaderFilter(ruleItem))
    }

    // ---- Search URL building ----

    @Test
    fun parseSearchUrl_segments() {
        // encodeUrl passes UTF-8 through unchanged (original behaviour),
        // so a space keyword stays a raw space in the 1-segment URL.
        val one = MiniAppHttp.parseSearchUrlForHtml("key word", "https://s.com/search?q=**", null)
        assertEquals("https://s.com/search?q=key word", one.url)
        assertEquals("GET", one.method)
        val two = MiniAppHttp.parseSearchUrlForHtml("kw", "https://s.com/search?q=**;post", null)
        assertEquals("POST", two.method)
        assertEquals("https://s.com/search?q=kw", two.url)
        val three = MiniAppHttp.parseSearchUrlForHtml(
            "kw",
            "https://s.com/search?q=**;post;GBK",
            null,
        )
        assertEquals("POST", three.method)
        assertEquals("GBK", three.charset)
    }

    // ---- Script assembly (pure functions) ----

    @Test
    fun assembleHome_containsInjectedPiecesAndStripsJsPrefix() {
        val rule = MiniAppRule()
        rule.title = "T"
        rule.findRule = "js:setHomeResult([])"
        val script = MiniAppScript.assembleHome(rule, "https://s.com/", "js:setHomeResult([])", null, "cb-1")
        assertTrue(script.contains("MY_TYPE = 'home'"))
        assertTrue(script.contains("CALLBACK_KEY = 'cb-1'"))
        assertTrue(script.contains("MY_URL = 'https://s.com/'"))
        // The js: strip applies to the executable tail, not to the MY_RULE
        // JSON blob (which still serializes the raw find_rule).
        assertTrue(script.trim().endsWith("setHomeResult([])"))
    }

    @Test
    fun assembleHome_myPageAndClassGlobalsInjected() {
        val rule = MiniAppRule()
        rule.title = "T"
        rule.url = "https://s.com/list"
        rule.className = "Drama"
        rule.classUrl = "https://s.com/list?c=drama"
        rule.findRule = "js:setHomeResult([])"
        val jsInject = HashMap<String, Any?>()
        jsInject["MY_PAGE"] = 1
        MiniAppSelector.parsePageClassUrl(rule.url, 1, rule, jsInject)
        val script = MiniAppScript.assembleHome(rule, "https://s.com/list", "js:setHomeResult([])", jsInject, "cb-1")
        assertTrue(script.contains("var MY_PAGE = 1;"))
        assertTrue(script.contains("var MY_CLASS_NAME = 'Drama';"))
        assertTrue(script.contains("var MY_CLASS_URL = 'https://s.com/list?c=drama';"))
        val script2 = MiniAppScript.assembleHome(rule, "https://s.com/list", "js:setHomeResult([])", mapOf("MY_PAGE" to 2), "cb-1")
        assertTrue(script2.contains("var MY_PAGE = 2;"))
    }

    @Test
    fun assembleLazyEval_doubleEscapesMyUrl() {
        val rule = MiniAppRule()
        rule.title = "T"
        val script = MiniAppScript.assembleLazyEval(
            rule,
            MiniAppText.escapeJsString("https://s.com/v?a='b'"),
            3,
            "setStrResult('x')",
            "https://s.com/base",
            "cb-9",
        )
        assertTrue(script.contains("MY_POSITION"))
        assertTrue(script.contains("setStrResult('x')"))
        assertTrue(script.contains("try{"))
    }

    @Test
    fun appendLineVar_countsHeaderLines() {
        val body = "var MY_TYPE = 'home';\nvar input = 'x';\nsetHomeResult([]);"
        val out = MiniAppScript.appendLineVar(body)
        assertTrue(out != null)
        assertTrue(out!!.startsWith("const MY_PRE_LINE = 2;"))
    }

    // ---- col_type enum ----

    @Test
    fun colType_lookupAndFallback() {
        assertEquals(MiniAppColType.MOVIE_3, MiniAppColType.getByCode("movie_3"))
        assertEquals(MiniAppColType.MOVIE_3, MiniAppColType.getByCode("no_such_code"))
        assertEquals(MiniAppColType.ICON_3_SMALL, MiniAppColType.getByItemType(-3))
        assertEquals(MiniAppColType.MOVIE_3, MiniAppColType.getByItemType(9999))
        val codes = MiniAppColType.getCodeArray()
        assertTrue(!codes.contains("header"))
        assertTrue(!codes.contains("footer"))
        assertTrue(codes.contains("movie_3"))
        assertEquals(57, MiniAppColType.values().size)
    }
}
