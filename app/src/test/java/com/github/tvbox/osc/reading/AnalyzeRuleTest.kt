package com.github.tvbox.osc.reading

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real App class boots MMKV/native probes; rule tests need neither. */
class NoBootApplication : Application()

/**
 * Rule-engine semantics against constructs copied from the real sample
 * pack (喜马拉雅 / 懒人 / 悦听吧 shapes). Non-JS paths only — QuickJS is
 * native and cannot run on the JVM; JS-dependent rules are covered by
 * the on-device verification in the delivery report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = NoBootApplication::class)
class AnalyzeRuleTest {

    private val host = object : LegadoJs.RuleHost {
        override fun evalRuleString(rule: String): String = ""
        override fun httpGet(url: String): String = ""
        override fun httpPost(url: String, body: String, headers: Map<String, String>): String = ""
    }

    private fun sourceOf(json: String): BookSource =
        BookSource.parsePack("[$json]").single()

    private val plainSource = sourceOf(
        """{"bookSourceName":"T","bookSourceUrl":"https://example.com","bookSourceType":0}""",
    )

    private fun ruleFor(content: Any?, baseUrl: String = "https://example.com/"): AnalyzeRule {
        val js = LegadoJs(plainSource, host)
        return AnalyzeRule(plainSource, js, content, baseUrl)
    }

    // ------------------------------------------------------------ JsonPath

    @Test
    fun jsonPathDefiniteAndIndex() {
        val root = LenientJson.parseAny("""{"data":{"list":[{"title":"a"},{"title":"b"}]}}""")
        assertEquals("a", LenientJson.valueToString(JsonPath.read(root, "$.data.list[0].title")))
        assertEquals("b", LenientJson.valueToString(JsonPath.read(root, "$.data.list[-1].title")))
    }

    @Test
    fun jsonPathWildcardAndRecursiveDescent() {
        val root = LenientJson.parseAny(
            """{"info":{"Datas":[{"id":1},{"id":2}],"docs":[{"id":3}]}}""",
        )
        val ids = JsonPath.read(root, "$..id") as List<*>
        assertEquals(listOf("1", "2", "3"), ids.map { LenientJson.valueToString(it) })
        val datas = JsonPath.read(root, "$.info.Datas[*]") as List<*>
        assertEquals(2, datas.size)
    }

    @Test
    fun jsonPathMissingReturnsNull() {
        val root = LenientJson.parseAny("""{"a":1}""")
        assertEquals(null, JsonPath.read(root, "$.b.c"))
    }

    // ------------------------------------------------------------ lenient JSON

    @Test
    fun lenientObjectSmartQuotesAndStringHeaders() {
        val obj = LenientJson.parseObjectLenient(
            """{"headers":"{\"User-Agent\": \"X\"}","webView":“true”}""",
        )
        assertEquals("true", obj.optString("webView"))
        val headers = LenientJson.parseObjectLenient(obj.optString("headers"))
        assertEquals("X", headers.optString("User-Agent"))
    }

    // ------------------------------------------------------------ AnalyzeRule: JSON

    @Test
    fun bareKeyOnJsonContentReadsField() {
        // 喜马拉雅 chapterName style: bare `title` against a JSON item.
        val item = LenientJson.parseAny("""{"title":"第1集 开场（上）","trackId":9}""")
        val rule = ruleFor(item)
        assertEquals("第1集 开场（上）", rule.getString("title"))
    }

    @Test
    fun purificationStripsBracketSuffix() {
        // The exact 喜马拉雅 chapterName rule shape.
        val item = LenientJson.parseAny("""{"title":"第1集 开场（特别版）"}""")
        val rule = ruleFor(item)
        assertEquals("第1集 开场", rule.getString("title##[\\(|（].*?[\\)|）]##"))
    }

    @Test
    fun orFallbackPicksFirstNonEmpty() {
        val item = LenientJson.parseAny("""{"info":{"sound":{"intro":"hi"}}}""")
        val rule = ruleFor(item)
        assertEquals("hi", rule.getString("$.missing||$.info.sound.intro"))
    }

    @Test
    fun listToStringNewlineJoinThenPurify() {
        // 懒人-style: list joined with \n, then `##\n##,` merges lines.
        val root = LenientJson.parseAny("""{"classify":[{"name":"玄幻"},{"name":"有声"}]}""")
        val rule = ruleFor(root)
        // In source JSON the rule carries a literal backslash-n here.
        assertEquals("玄幻,有声", rule.getString("$.classify[*].name##\\n##,"))
    }

    @Test
    fun putAndGetVariables() {
        val root = LenientJson.parseAny("""{"sections":["s1"],"entityType":3}""")
        val rule = ruleFor(root)
        val url = rule.getString("@put:{\"t\":$.entityType}https://x/y?t=@get:{t}&s={{$.sections[0]}}")
        assertEquals("https://x/y?t=3&s=s1", url)
    }

    @Test
    fun templateRuleExpressionInUrlField() {
        // 荔枝-style chapterUrl fragment: templates evaluated as rules.
        val item = LenientJson.parseAny("""{"id":64460,"name":"斗破苍穹"}""")
        val rule = ruleFor(item)
        assertEquals(
            "https://api/item?id=64460",
            rule.makeUpUrl("https://api/item?id={{$.id}}"),
        )
    }

    // ------------------------------------------------------------ AnalyzeRule: JSoup

    private val html = """
        <html><body>
        <div class="section-box-list-item">
          <a href="/book/101.html"><img data-src="/c1.jpg"></a>
          <div class="title">书甲</div>
        </div>
        <div class="section-box-list-item">
          <a href="/book/102.html"><img data-src="/c2.jpg"></a>
          <div class="title">书乙</div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun jsoupListAndFields() {
        val rule = ruleFor(html, "https://www.51tingbook.com/")
        val elements = rule.getElements(".section-box-list-item")
        assertEquals(2, elements.size)
        val first = rule.child(elements[0])
        assertEquals("书甲", first.getString("class.title@text"))
        assertEquals(
            "https://www.51tingbook.com/book/101.html",
            first.getString("a@href", isUrl = true),
        )
        assertEquals("/c1.jpg", first.getString("img@data-src"))
    }

    @Test
    fun jsoupPositionalIndexSuffix() {
        val rule = ruleFor(html, "https://www.51tingbook.com/")
        assertEquals(
            "书乙",
            rule.getString(".section-box-list-item.1@class.title@text"),
        )
    }

    // ------------------------------------------------------------ URL options

    @Test
    fun splitOptionsPostBody() {
        val raw = """https://api.example.com/search,{"method":"POST","body":"k={{key}}&p=1"}"""
        val (url, options) = AnalyzeUrl.splitOptions(raw)
        assertEquals("https://api.example.com/search", url)
        assertEquals("POST", options.method)
        assertEquals("k={{key}}&p=1", options.body)
    }

    @Test
    fun splitOptionsWebViewSmartQuotes() {
        val raw = """https://qtfm.cn/x,{"webView":“true”}"""
        val (_, options) = AnalyzeUrl.splitOptions(raw)
        assertTrue(options.webView)
    }

    @Test
    fun splitOptionsPlainUrlUntouched() {
        val (url, options) = AnalyzeUrl.splitOptions("https://a.b/c?x=1,2")
        assertEquals("https://a.b/c?x=1,2", url)
        assertEquals("GET", options.method)
    }

    // ------------------------------------------------------------ pack parsing

    @Test
    fun parsePackToleratesCommentUrlAndSingleObject() {
        // 番茄-style: bookSourceUrl carries comment text, not a URL.
        val single = """{"bookSourceName":"番茄听书[官方]","bookSourceUrl":"// 注释文字\nhttps://fanqie","bookSourceType":1}"""
        val sources = BookSource.parsePack(single)
        assertEquals(1, sources.size)
        assertEquals("番茄听书[官方]", sources[0].bookSourceName)
        assertTrue(sources[0].sourceKey.isNotBlank())
    }

    @Test
    fun parsePackDropsNamelessAndKeepsRaw() {
        val pack = """[{"bookSourceUrl":"https://x"},{"bookSourceName":"有名","bookSourceUrl":"https://y","loginUrl":"https://login"}]"""
        val sources = BookSource.parsePack(pack)
        assertEquals(1, sources.size)
        assertEquals("https://login", sources[0].loginUrl)
        assertTrue(sources[0].raw.has("bookSourceName"))
    }

    @Test
    fun javaStyleStringMatchesLegadoMapFormat() {
        val item = JSONObject("""{"id":51060,"catalog_name":"网络小说"}""")
        val text = LenientJson.javaStyleString(item)
        assertTrue(text.contains("id=51060"))
        assertTrue(text.contains("catalog_name=网络小说"))
    }

    // ------------------------------------------------- logtest38 semantics

    @Test
    fun jsonPathFilterExpression() {
        // R7: [?(@.field == 'value')] / != filters.
        val root = LenientJson.parseAny(
            """{"items":[{"t":"1","n":"a"},{"t":"2","n":"b"},{"t":"1","n":"c"}]}""",
        )
        val hits = JsonPath.read(root, "$.items[?(@.t == '1')]") as List<*>
        assertEquals(2, hits.size)
        val misses = JsonPath.read(root, "$.items[?(@.t != '1')]") as List<*>
        assertEquals(1, misses.size)
    }

    @Test
    fun jsonPathSliceAndImplicitChild() {
        val root = LenientJson.parseAny(
            """{"items":[1,2,3,4],"cats":[{"className":"x"},{"className":"y"}]}""",
        )
        val slice = JsonPath.read(root, "$.items[:2]") as List<*>
        assertEquals(2, slice.size)
        val names = JsonPath.read(root, "$.cats[*]className") as List<*>
        assertEquals(listOf("x", "y"), names.map { LenientJson.valueToString(it) })
    }

    @Test
    fun dotLeadingPathOnJsonContentIsJsonPath() {
        // R4(i): `.rows[*]` on JSON content is JsonPath, not a CSS chain.
        val root = LenientJson.parseAny("""{"rows":[{"cn":"甲"},{"cn":"乙"}]}""")
        val rule = ruleFor(root)
        assertEquals(2, rule.getElements(".rows[*]").size)
    }

    @Test
    fun bareKeyWithAtSuffixOnJsonElement() {
        // R4(ii): `cn@text` resolves the key first, then the operator.
        val item = LenientJson.parseAny("""{"cn":"第一章"}""")
        val rule = ruleFor(item)
        assertEquals("第一章", rule.getString("cn@text"))
    }

    @Test
    fun singleBraceTemplateSubstitution() {
        // R5: {$.path} substitutes like {{$.path}} in URL templates.
        val item = LenientJson.parseAny("""{"path":"/abc/1"}""")
        val rule = ruleFor(item)
        assertEquals("http://x/abc/1/end", rule.makeUpUrl("http://x{$.path}/end"))
    }

    @Test
    fun indexExclusionPicksAndRange() {
        // R3: [!a:b] excludes indices, [a:b:c] picks, [a:b] ranges.
        val listHtml =
            "<html><body><ul><li>0</li><li>1</li><li>2</li><li>3</li></ul></body></html>"
        val rule = ruleFor(listHtml)
        fun texts(ruleText: String): List<String> =
            rule.getElements(ruleText).map { (it as org.jsoup.nodes.Element).text() }
        assertEquals(listOf("1", "3"), texts("tag.li[!0:2]"))
        assertEquals(listOf("0", "2", "3"), texts("tag.li[0:2:3]"))
        assertEquals(listOf("1", "2"), texts("tag.li[1:3]"))
        assertEquals(listOf("3"), texts("tag.li[-1]"))
    }

    @Test
    fun cssPrefixIsStripped() {
        // R9: @css: only tags the rule as a CSS chain.
        val rule = ruleFor(html, "https://www.51tingbook.com/")
        assertEquals(2, rule.getElements("@css:.section-box-list-item").size)
    }

    @Test
    fun t2sConvertsCommonChars() {
        assertEquals("这个们来时", T2S.convert("這個們來時"))
        assertEquals("abc", T2S.convert("abc"))
    }
}
