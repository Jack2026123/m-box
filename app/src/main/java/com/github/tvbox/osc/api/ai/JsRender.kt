package com.github.tvbox.osc.api.ai

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 2.41 无头 WebView 渲染器（浏览器渲染沙箱）。
 *
 * 病根：reconSite 只用 OkHttp 抓原始 HTML，不执行 JavaScript。
 * 对 Vue/React/Nuxt 等 SPA 站，OkHttp 拿到的是空壳 <div id="app"></div>，
 * Agent 看不到真实列表，于是选择器全写「未知」，真跑 0 条。
 *
 * 本对象用离屏 WebView 真正加载页面、执行 JS、等渲染完成后再取
 * document.documentElement.outerHTML，让 Agent 看到和浏览器一样的 DOM。
 *
 * 线程约束：WebView 必须在主线程创建与使用，reconSite 跑在
 * Dispatchers.Default，所以内部先 withContext(Main) 再 suspendCancellableCoroutine。
 * 不持有 Activity：用 Application context 建离屏 WebView，用完 destroy()。
 */
object JsRender {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 站点是否疑似 JS 渲染（SPA 空壳）。满足任一即建议走渲染路径。 */
    fun looksLikeSpa(html: String): Boolean {
        if (html.isEmpty()) return false
        val low = html.lowercase()
        val hasEmptyRoot = Regex("""<div[^>]+id="(app|root|__next|app-mount)"[^>]*>\s*</div>""").containsMatchIn(low)
        val hasFramework = low.contains("__nuxt_data__") || low.contains("__next_f") ||
            low.contains("id=\"app\"") || low.contains("id=\"root\"") ||
            low.contains("vite_base_url") || low.contains("window.__initial")
        val shortBody = html.length < 1200
        // 空壳根节点，或有框架痕迹但正文很短，都判为需要渲染
        return hasEmptyRoot || (hasFramework && shortBody)
    }

    /** 渲染 URL 并返回最终 HTML（outerHTML）。失败返回空串。 */
    suspend fun render(url: String, timeoutMs: Long = 25000L): String {
        val ctx: Application = try {
            requireNotNull(com.github.tvbox.osc.base.App.getInstance())
        } catch (ignored: Throwable) {
            return ""
        }
        return withContext(Dispatchers.Main) {
            renderInternal(ctx, url, timeoutMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderInternal(ctx: Application, url: String, timeoutMs: Long): String {
        return try {
            suspendCancellableCoroutine { cont ->
                var web: WebView? = null
                var done = false
                lateinit var timeout: Runnable
                // 只恢复一次：清掉超时回调、回结果、销毁 WebView。
                fun finish(html: String) {
                    if (done) return
                    done = true
                    mainHandler.removeCallbacks(timeout)
                    if (cont.isActive) cont.resume(html)
                    try { web?.destroy() } catch (ignored: Throwable) {}
                }
                // evaluateJavascript 是异步回调取结果，不能当返回值用：
                // 在回调里 finish；回调 5 秒不回则放空兜底，避免永久挂起。
                fun evalAndFinish(w: WebView) {
                    try {
                        w.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                            finish(raw?.removeSurrounding("\"")?.unescapeJs() ?: "")
                        }
                        mainHandler.postDelayed({ finish("") }, 5000L)
                    } catch (ignored: Throwable) {
                        finish("")
                    }
                }
                // 超时兜底：到点就取当前 DOM（SPA 通常已挂载），避免永久挂起
                timeout = Runnable {
                    if (done) return@Runnable
                    web?.let { evalAndFinish(it) } ?: finish("")
                }
                mainHandler.postDelayed(timeout, timeoutMs)
                try {
                    val w = WebView(ctx)
                    web = w
                    w.settings.javaScriptEnabled = true
                    w.settings.domStorageEnabled = true
                    w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    w.settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    w.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            // 主文档加载完后再让 JS 跑一拍（SPA 数据请求+渲染）
                            mainHandler.postDelayed({
                                if (done) return@postDelayed
                                evalAndFinish(view)
                            }, 1500L)
                        }
                    }
                    w.loadUrl(url)
                } catch (e: Throwable) {
                    finish("")
                }
                cont.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeout)
                    try { web?.destroy() } catch (ignored: Throwable) {}
                }
            }
        } catch (ignored: Throwable) {
            ""
        }
    }

    /** WebView 回传的 JS 字符串带 \" 转义，还原成真实 HTML。 */
    private fun String.unescapeJs(): String {
        var s = this
        s = s.replace("\\\\", "\u0000")   // 先保护真实反斜杠
        s = s.replace("\\\"", "\"")
        s = s.replace("\\/", "/")
        s = s.replace("\\n", "\n")
        s = s.replace("\\r", "\r")
        s = s.replace("\\t", "\t")
        s = s.replace("\u0000", "\\")
        return s
    }
}