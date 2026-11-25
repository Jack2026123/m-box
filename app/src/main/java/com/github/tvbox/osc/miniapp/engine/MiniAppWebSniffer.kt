package com.github.tvbox.osc.miniapp.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 小程序 WebView 嗅探：加载页面，从网络请求中抓取 m3u8/mp4 等真实地址。
 * WebView 以 1px 挂到 Activity decorView，不打断当前 UI。
 */
class MiniAppWebSniffer(
    private val activity: Activity,
    private val onFound: (url: String, headers: Map<String, String>) -> Unit,
    private val onFail: (message: String) -> Unit,
    private val timeoutMs: Long = 15_000L,
) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var finished = false
    private var timeoutRunnable: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun start(pageUrl: String, headers: Map<String, String> = emptyMap()) {
        if (pageUrl.isBlank()) {
            onFail("嗅探地址为空")
            return
        }
        main.post {
            if (finished) return@post
            destroyInternal()
            val w = WebView(activity)
            web = w
            val s = w.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.blockNetworkImage = true
            s.mediaPlaybackRequiresUserGesture = false
            s.javaScriptCanOpenWindowsAutomatically = true
            s.loadsImagesAutomatically = false
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            s.cacheMode = WebSettings.LOAD_DEFAULT
            if (headers["User-Agent"].isNullOrEmpty().not()) {
                s.userAgentString = headers["User-Agent"]
            }

            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val u = request.url?.toString() ?: return null
                    if (!isVideoUrl(u)) return null
                    val h = HashMap<String, String>()
                    request.requestHeaders?.let { h.putAll(it) }
                    if (!h.keys.any { it.equals("Referer", true) }) {
                        h["Referer"] = pageUrl
                    }
                    // 保留调用方传入的常用头
                    headers.forEach { (k, v) ->
                        if (!h.containsKey(k)) h[k] = v
                    }
                    main.post { completeFound(u, h) }
                    return null
                }

                @Deprecated("Deprecated in Java")
                override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
                    if (isVideoUrl(url)) {
                        val h = HashMap<String, String>()
                        if (!headers.containsKey("Referer")) h["Referer"] = pageUrl
                        headers.forEach { (k, v) -> h[k] = v }
                        main.post { completeFound(url, h) }
                    }
                    return null
                }
            }

            // 1px 挂载，部分站点依赖 WebView 真正 attach
            try {
                val decor = activity.window.decorView as ViewGroup
                val lp = FrameLayout.LayoutParams(1, 1)
                w.layoutParams = lp
                decor.addView(w)
            } catch (_: Throwable) {
            }

            val headerMap = HashMap<String, String>()
            headers.forEach { (k, v) -> headerMap[k] = v }
            if (headerMap.isNotEmpty()) {
                w.loadUrl(pageUrl, headerMap)
            } else {
                w.loadUrl(pageUrl)
            }

            val task = Runnable {
                if (!finished) {
                    finished = true
                    destroyInternal()
                    onFail("嗅探超时")
                }
            }
            timeoutRunnable = task
            main.postDelayed(task, timeoutMs)
        }
    }

    fun cancel() {
        main.post {
            finished = true
            destroyInternal()
        }
    }

    private fun completeFound(url: String, headers: Map<String, String>) {
        if (finished) return
        finished = true
        timeoutRunnable?.let { main.removeCallbacks(it) }
        destroyInternal()
        onFound(url, headers)
    }

    private fun destroyInternal() {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = null
        val w = web ?: return
        web = null
        try {
            w.stopLoading()
            w.loadUrl("about:blank")
            (w.parent as? ViewGroup)?.removeView(w)
            w.removeAllViews()
            w.destroy()
        } catch (_: Throwable) {
        }
    }

    companion object {
        fun isVideoUrl(url: String): Boolean {
            val u = url.lowercase()
            if (u.startsWith("data:") || u.startsWith("blob:")) return false
            // 排除静态资源
            if (u.contains(".js") || u.contains(".css") || u.contains(".png") ||
                u.contains(".jpg") || u.contains(".jpeg") || u.contains(".gif") ||
                u.contains(".webp") || u.contains(".ico") || u.contains(".woff") ||
                u.contains(".html") || u.contains(".htm?") || u.endsWith(".htm")
            ) {
                // 仍允许 path 里带 html 但 query 是 m3u8 的情况
                if (!(u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv"))) {
                    return false
                }
            }
            return u.contains(".m3u8") ||
                u.contains(".mp4") ||
                u.contains(".flv") ||
                u.contains(".mkv") ||
                u.contains("application/vnd.apple.mpegurl") ||
                u.contains("application/x-mpegurl") ||
                u.contains("video/mp4") ||
                u.contains("video/mpeg")
        }
    }
}
