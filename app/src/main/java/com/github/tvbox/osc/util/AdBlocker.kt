package com.github.tvbox.osc.util

import android.webkit.WebResourceResponse

import java.io.ByteArrayInputStream
import java.nio.charset.Charset

object AdBlocker {
    private val AD_HOSTS = ArrayList<String>()

    @JvmStatic
    fun clear() {
        AD_HOSTS.clear()
    }

    @JvmStatic
    fun isEmpty(): Boolean {
        return AD_HOSTS.isEmpty()
    }

    @JvmStatic
    fun addAdHost(host: String) {
        AD_HOSTS.add(host)
    }

    @JvmStatic
    fun hasHost(host: String): Boolean {
        return AD_HOSTS.contains(host)
    }

    /** 广告主机表快照（RuleConfig 的 ads 与这里同源，不另存一份）。 */
    @JvmStatic
    fun getHosts(): List<String> {
        return ArrayList(AD_HOSTS)
    }

    @JvmStatic
    fun isAd(url: String): Boolean {
        // 修复：之前用全 URL 子串匹配（lower.contains(adHost)），误伤严重：
        // 广告域名串出现在路径/参数里、或是更大域名的一部分（如 myad.com 含 ad.com）
        // 都会整页拦掉。改成只按 host、按域名边界匹配：host 等于广告域，或以 .广告域 结尾。
        val host = try {
            android.net.Uri.parse(url).host?.lowercase()
        } catch (ignored: Throwable) {
            null
        } ?: return false
        for (adHost in AD_HOSTS) {
            val h = adHost.lowercase().trim()
            if (h.isEmpty()) continue
            if (host == h || host.endsWith(".$h")) {
                return true
            }
        }
        try {
            if (AdDomains.isAdDomain(host)) {
                return true
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    @JvmStatic
    fun createEmptyResource(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray(Charset.defaultCharset())))
    }

}
