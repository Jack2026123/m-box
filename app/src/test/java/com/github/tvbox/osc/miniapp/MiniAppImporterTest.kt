package com.github.tvbox.osc.miniapp

import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.github.tvbox.osc.reading.NoBootApplication

/**
 * Mini-app import parsing (logtest43): the live 6-code response
 * captured in the probe (pasteme api/getContent, 118,436 bytes)
 * is the fixture — its `data` field is a share header + `@@` +
 * base64 rule JSON. The live-network test at the bottom re-fetches
 * the same code and skips (does not fail) when the sandbox has no
 * direct network; the fixture tests prove the parse path either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = NoBootApplication::class)
class MiniAppImporterTest {

    private fun fixtureData(): String {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("miniapp/tyr6api.json")
            ?: error("fixture miniapp/tyr6api.json missing from test resources")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .let { JSONObject(it).getString("data") }
    }

    @Test
    fun parseShareText_fixture6Code_resolvesTitle() {
        val result = MiniAppImporter.parseShareText(fixtureData(), "云6oooole/apyitlousom9tcao")
        assertTrue(result is MiniAppImporter.Result.Ok)
        val item = (result as MiniAppImporter.Result.Ok).item
        assertEquals("150部午夜擦边短剧大合集", item.name)
        assertTrue(item.icon.isBlank())
        assertTrue(item.ruleJson.isNotBlank())
        assertEquals("云6oooole/apyitlousom9tcao", item.source)
    }

    @Test
    fun parseRuleJson_directJson_ok() {
        val result = MiniAppImporter.parseRuleJson(
            """{"title":"T","icon":"https://example.com/i.png"}""",
            "paste",
        )
        assertTrue(result is MiniAppImporter.Result.Ok)
        val item = (result as MiniAppImporter.Result.Ok).item
        assertEquals("T", item.name)
        assertEquals("https://example.com/i.png", item.icon)
    }

    @Test
    fun parseRuleJson_missingTitle_titleEmpty() {
        val result = MiniAppImporter.parseRuleJson("""{"data":{}}""", "paste")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.TITLE_EMPTY,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    @Test
    fun parseShareText_garbage_parseError() {
        val result = MiniAppImporter.parseShareText("not a code, not json", "paste")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.PARSE,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    // logtest44 precise algorithm (AutoImportHelper.realCheckAutoText):
    // top-level parsing is ￥ segmentation, not @@. home_rule/v2 content
    // is either base64://@name@payload (split("@")[2], NO_WRAP) or raw
    // JSON; a plain base64 content without the base64:// prefix is NOT
    // JSON and is rejected. Unknown identifiers and <3 segments are
    // rejected; @@ only counts inside home_sub/page_detail content.
    private fun syntheticRuleJson(): String =
        """{"title":"合成测试小程序","url":"https://example.com/list","type":"video"}"""

    private fun b64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun assertSyntheticOk(result: MiniAppImporter.Result) {
        assertTrue("expected Ok, got $result", result is MiniAppImporter.Result.Ok)
        val item = (result as MiniAppImporter.Result.Ok).item
        assertEquals("合成测试小程序", item.name)
        assertEquals(syntheticRuleJson(), item.ruleJson)
    }

    @Test
    fun parseShareText_sloganBase64Wrapper_ok() {
        val slogan = "海阔视界，合成测试小程序￥home_rule_v2￥base64://@合成测试小程序@${b64(syntheticRuleJson())}"
        assertSyntheticOk(MiniAppImporter.parseShareText(slogan, "slogan"))
    }

    @Test
    fun parseShareText_sloganPlainBase64_parseError() {
        // Strict home_rule/v2 rule: content without base64:// must be
        // JSON; a bare base64 payload is not JSON.
        val slogan = "海阔视界，合成测试小程序￥home_rule￥${b64(syntheticRuleJson())}"
        val result = MiniAppImporter.parseShareText(slogan, "slogan")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.PARSE,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    @Test
    fun parseShareText_sloganRawJson_ok() {
        val slogan = "海阔视界，合成测试小程序￥home_rule￥${syntheticRuleJson()}"
        assertSyntheticOk(MiniAppImporter.parseShareText(slogan, "slogan"))
    }

    @Test
    fun parseShareText_sloganNonJsonPayload_parseError() {
        val slogan = "海阔视界，合成测试小程序￥home_rule￥${b64("hello world")}"
        val result = MiniAppImporter.parseShareText(slogan, "slogan")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.PARSE,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    @Test
    fun parseShareText_sloganEmptyContent_parseError() {
        val result = MiniAppImporter.parseShareText("海阔视界，合成测试小程序￥home_rule￥", "slogan")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.PARSE,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    @Test
    fun parseShareText_sloganUnknownId_parseError() {
        val slogan = "海阔视界，合成测试小程序￥not_a_rule￥${syntheticRuleJson()}"
        val result = MiniAppImporter.parseShareText(slogan, "slogan")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.PARSE,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    @Test
    fun parseShareText_sloganTooFewSegments_parseError() {
        val result = MiniAppImporter.parseShareText("海阔视界￥home_rule", "slogan")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.PARSE,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    @Test
    fun parseShareText_topLevelAtAt_parseError() {
        // @@ with no ￥ is no longer accepted at the top level.
        val text = "说明@@${b64(syntheticRuleJson())}"
        val result = MiniAppImporter.parseShareText(text, "mixed")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.PARSE,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    @Test
    fun parseShareText_sloganV2BadBase64_parseError() {
        val slogan = "海阔视界，合成测试小程序￥home_rule_v2￥base64://@合成测试小程序@!!!"
        val result = MiniAppImporter.parseShareText(slogan, "slogan")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.PARSE,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    @Test
    fun parseShareText_sloganV2EmptyTitle_titleEmpty() {
        val emptyTitle = """{"title":"","url":"https://example.com/list"}"""
        val slogan = "海阔视界，合成测试小程序￥home_rule_v2￥base64://@合成测试小程序@${b64(emptyTitle)}"
        val result = MiniAppImporter.parseShareText(slogan, "slogan")
        assertTrue(result is MiniAppImporter.Result.Err)
        assertEquals(
            MiniAppImporter.Kind.TITLE_EMPTY,
            (result as MiniAppImporter.Result.Err).kind,
        )
    }

    @Test
    fun parseShareText_bareJsonWithColTypeFindRule_wrappedOk() {
        val json = """{"title":"合成测试小程序","col_type":"movie_3","find_rule":"body&&a"}"""
        val result = MiniAppImporter.parseShareText(json, "paste")
        assertTrue("expected Ok, got $result", result is MiniAppImporter.Result.Ok)
        val item = (result as MiniAppImporter.Result.Ok).item
        assertEquals("合成测试小程序", item.name)
        assertEquals(json, item.ruleJson)
    }

    @Test
    fun resolve_ruleScheme_ok() = runBlocking {
        val slogan = "海阔视界，合成测试小程序￥home_rule_v2￥base64://@合成测试小程序@${b64(syntheticRuleJson())}"
        val result = MiniAppImporter.resolve("rule://${b64(slogan)}")
        assertSyntheticOk(result)
    }

    @Test
    fun resolve_pastedSlogan_ok() = runBlocking {
        val slogan = "海阔视界，合成测试小程序￥home_rule￥${syntheticRuleJson()}"
        val result = MiniAppImporter.resolve(slogan)
        assertSyntheticOk(result)
    }

    @Test
    fun resolve_bareJsonWrapped_ok() = runBlocking {
        val json = """{"title":"合成测试小程序","col_type":"movie_3","find_rule":"body&&a"}"""
        val result = MiniAppImporter.resolve(json)
        assertTrue("expected Ok, got $result", result is MiniAppImporter.Result.Ok)
        assertEquals("合成测试小程序", (result as MiniAppImporter.Result.Ok).item.name)
    }

    @Test
    fun resolve_live6Code_endToEnd() = runBlocking {
        val result = MiniAppImporter.resolve("云6oooole/apyitlousom9tcao")
        if (result is MiniAppImporter.Result.Err &&
            result.kind == MiniAppImporter.Kind.NETWORK
        ) {
            assumeTrue("live network unavailable in this sandbox, skipping", false)
        }
        assertTrue("expected Ok, got $result", result is MiniAppImporter.Result.Ok)
        val item = (result as MiniAppImporter.Result.Ok).item
        assertEquals("150部午夜擦边短剧大合集", item.name)
    }
}
