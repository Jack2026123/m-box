package com.github.tvbox.osc.miniapp.engine

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniAppCookieJarTest {

    @Test
    fun headerFor_returnsStoredCookies() {
        val jar = MiniAppCookieJar()
        val url = "https://example.com/page".toHttpUrl()
        val cookie = Cookie.Builder().name("sid").value("abc").domain("example.com").build()
        jar.saveFromResponse(url, listOf(cookie))
        assertEquals("sid=abc", jar.headerFor(url))
    }

    @Test
    fun headerFor_ignoresOtherHostsAndExpired() {
        val jar = MiniAppCookieJar()
        val url = "https://example.com/".toHttpUrl()
        val other = "https://other.com/".toHttpUrl()
        jar.saveFromResponse(other, listOf(Cookie.Builder().name("x").value("1").domain("other.com").build()))
        assertEquals("", jar.headerFor(url))
        val expired = Cookie.Builder().name("old").value("9").domain("example.com").expiresAt(1L).build()
        jar.saveFromResponse(url, listOf(expired))
        assertEquals("", jar.headerFor(url))
    }
}
