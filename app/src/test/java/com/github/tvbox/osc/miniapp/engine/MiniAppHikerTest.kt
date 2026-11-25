package com.github.tvbox.osc.miniapp.engine

import com.github.tvbox.osc.reading.NoBootApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// pageList 解析走 org.json.JSONArray（Android 框架类），须跑 Robolectric；
// QuickJS 的 .js: 求值不在此覆盖（x86 JVM 载不了 arm64 QuickJS JNI，真机验）。
@RunWith(RobolectricTestRunner::class)
@Config(application = NoBootApplication::class)
class MiniAppHikerTest {

    @Test
    fun parseErrorUrl() {
        val link = parseHikerRuleUrl(
            "hiker://emptywen-zhi-zhuang-tai#immersiveTheme##gameTheme##autoCache#@rule=js:\$.require(\"erji\")",
        )
        assertEquals("emptywen-zhi-zhuang-tai", link.path)
        assertEquals(listOf("immersiveTheme", "gameTheme", "autoCache"), link.flags)
        assertEquals("js:\$.require(\"erji\")", link.ruleCode)
    }

    @Test
    fun parseFlagsOnly() {
        val link = parseHikerRuleUrl("hiker://abc#immersiveTheme#")
        assertEquals("abc", link.path)
        assertEquals(listOf("immersiveTheme"), link.flags)
        assertNull(link.ruleCode)
    }

    @Test
    fun parsePlain() {
        val link = parseHikerRuleUrl("hiker://abc")
        assertEquals("abc", link.path)
        assertEquals(emptyList<String>(), link.flags)
        assertNull(link.ruleCode)
    }

    @Test
    fun parseLiteralRule() {
        val link = parseHikerRuleUrl("hiker://x#@rule=body&&a")
        assertEquals("x", link.path)
        assertEquals("body&&a", link.ruleCode)
    }

    @Test
    fun parseRuleJsKeepsHashInside() {
        val link = parseHikerRuleUrl("hiker://x#@rule=js:\"a#b\"")
        assertEquals("js:\"a#b\"", link.ruleCode)
    }

    @Test
    fun parseVirtualPageUrl() {
        val link = parseHikerRuleUrl("hiker://empty##fypage")
        assertEquals("empty", link.path)
        assertNull(link.ruleCode)
    }

    @Test
    fun detailBranchApplies() {
        assertEquals(true, detailBranchApplies("hiker://empty##fypage", "body&&a"))
        assertEquals(true, detailBranchApplies("hiker://empty##fypage", "js:foo"))
        assertEquals(true, detailBranchApplies("https://example.com/vod/1", "body&&a"))
        assertEquals(false, detailBranchApplies("https://example.com/vod/1", ""))
        assertEquals(false, detailBranchApplies("video://abc", "js:foo"))
        assertEquals(false, detailBranchApplies("video://abc", "body&&a"))
    }

    @Test
    fun hikerBaseUrl() {
        assertEquals(
            "http://a.com",
            hikerBaseUrl("hiker://empty#http://a.com@rule=js:fetch(MY_URL.split('#')[1],{})"),
        )
        assertNull(hikerBaseUrl("hiker://empty##fypage"))
        assertNull(hikerBaseUrl("hiker://emptywen-zhi-zhuang-tai#immersiveTheme##gameTheme##autoCache#@rule=js:\$.require(\"erji\")"))
        assertNull(hikerBaseUrl("https://example.com/vod/1"))
    }

    private fun ruleWithPages(): MiniAppRule {
        val rule = MiniAppRule()
        rule.pages = "[{\"path\":\"erji\",\"name\":\"二级\",\"rule\":\"js:foo\",\"col_type\":\"movie_3\"}," +
            "{\"path\":\"other\",\"name\":\"named\",\"rule\":\"body&&a\",\"col_type\":\"\"}]"
        return rule
    }

    @Test
    fun findRequirePageByPath() {
        assertEquals("js:foo", findRequirePage(ruleWithPages(), "erji")?.rule)
    }

    @Test
    fun findRequirePageByName() {
        assertEquals("body&&a", findRequirePage(ruleWithPages(), "named")?.rule)
    }

    @Test
    fun findRequirePageMiss() {
        assertNull(findRequirePage(ruleWithPages(), "missing"))
        assertNull(findRequirePage(ruleWithPages(), ""))
    }
}
