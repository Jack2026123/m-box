package com.github.tvbox.osc.miniapp.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniAppSelectorXpathTest {

    private val html =
        "<html><body><div class=\"box\"><a href=\"/p1\">第一</a><a href=\"/p2\">第二</a></div><p id=\"t\">正文</p></body></html>"

    @Test
    fun xpath_firstText() {
        assertEquals("第一", MiniAppSelector.xpath(html, "//div[@class='box']/a[1]/text()"))
    }

    @Test
    fun xpath_attribute() {
        assertEquals("/p2", MiniAppSelector.xpath(html, "//div[@class='box']/a[2]/@href"))
    }

    @Test
    fun xpathArray_allTexts() {
        assertEquals(listOf("第一", "第二"), MiniAppSelector.xpathArray(html, "//div[@class='box']/a/text()"))
    }

    @Test
    fun xpath_badExpr_returnsEmpty() {
        assertEquals("", MiniAppSelector.xpath(html, "///[broken"))
        assertEquals(emptyList<String>(), MiniAppSelector.xpathArray(html, "///[broken"))
    }
}
