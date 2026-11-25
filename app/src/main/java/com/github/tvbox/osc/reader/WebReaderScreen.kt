package com.github.tvbox.osc.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.github.tvbox.osc.util.FloatLog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class WebChapter(
    val title: String,
    val html: String,
    val headers: Map<String, String>,
)

private class HeaderWebViewClient(
    private val headersProvider: () -> Map<String, String>,
    private val pendingRestoreProvider: () -> Int,
) : WebViewClient() {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val headers = headersProvider()
        if (headers.isEmpty()) return null
        val url = request.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return runCatching {
            val builder = Request.Builder().url(url)
            for ((key, value) in headers) builder.header(key, value)
            val response = httpClient.newCall(builder.build()).execute()
            val body = response.body ?: run { response.close(); return null }
            val contentType = response.header("Content-Type") ?: "application/octet-stream"
            val mime = contentType.substringBefore(';').trim()
            val charset = contentType.substringAfter(';', "").substringAfter('=', "").trim()
                .ifEmpty { "utf-8" }
            val bytes = body.bytes()
            response.close()
            WebResourceResponse(mime, charset, ByteArrayInputStream(bytes))
        }.getOrNull()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        val restore = pendingRestoreProvider()
        if (restore > 0) view.post { view.scrollTo(0, restore) }
    }
}

/**
 * Tiny JS bridge: the paged HTML reports its own column ("page") count once the
 * browser has laid the chapter out. Kotlin never measures text itself — the
 * browser already knows exactly where the lines break, so asking it is both
 * cheaper and more accurate than re-implementing pagination natively.
 */
private class ReaderBridge(
    val onPageCount: (Int) -> Unit,
    val onJsLog: (String) -> Unit = {},
) {
    @JavascriptInterface
    fun pageCount(count: Int) {
        onPageCount(count)
    }

    // 1.86 诊断：分页 JS 的分阶段日志经此写进悬浮日志。
    @JavascriptInterface
    fun jsLog(msg: String) {
        onJsLog(msg)
    }
}

/**
 * Comic-only bridge: the comic page reports when every image in the chapter
 * is complete. Comic images are lazy-loaded with no preset height, so the
 * document keeps growing while reading; the bottom-crossing gate must not
 * fire at a "bottom" that later grows past it.
 */
private class ComicBridge(val onReady: () -> Unit) {
    @JavascriptInterface
    fun imagesReady() {
        onReady()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebReaderScreen(
    session: ReaderSession,
    startIndex: Int,
    loadChapter: suspend (index: Int) -> WebChapter,
    readerSettings: NovelSettings? = null,
    onSettingsChange: ((NovelSettings) -> Unit)? = null,
    novelStyle: Boolean = false,
    initialPaged: Boolean = false,
    onPagedChange: ((Boolean) -> Unit)? = null,
    onBack: () -> Unit,
    // 1.93：路由开关回调（当前章节，目标路由）。面板里的开关拨动时
    // 由 activity 接手：存偏好后用当前章节重进另一个阅读器。
    onSwitchReader: ((Int, Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("web_reader", Context.MODE_PRIVATE) }
    var chapterIndex by remember { mutableStateOf(startIndex) }
    var chapter by remember { mutableStateOf<WebChapter?>(null) }
    var loading by remember { mutableStateOf(true) }
    var lastGood by remember { mutableStateOf(startIndex) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pendingRestore by remember { mutableStateOf(0) }
    var showAdjust by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    var lastRenderedChapterIndex by remember { mutableStateOf(-1) }

    // Paged mode (horizontal page turns) vs the original vertical scroll.
    // Only meaningful for novels: a comic is one long image strip and has no
    // pages to cut, so the toggle is hidden for comics and the reader stays on
    // scrolling there.
    // The page-turn mode is owned by the caller (it decides the HTML shape
    // before this screen is composed), but the settings panel can flip it, so
    // this screen mirrors it and reports changes back through the same pref.
    var paged by remember { mutableStateOf(initialPaged) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(1) }
    // 1.95：滚动进度（滚动模式）：滑杆拖动跳位置，滚动监听里实时更新。
    var scrollFraction by remember { mutableFloatStateOf(0f) }
    var maxScrollPx by remember { mutableIntStateOf(0) }
    // 1.86 诊断：当前左右文档是否经桥上报过页数；看门狗 2 秒内没见
    // 上报就判定 JS 没跑起来，自动回落到上下滚动（不改记忆的模式偏好，
    // 下次进书仍先试左右），并在悬浮日志留一行。
    var pageCountReported by remember { mutableStateOf(false) }
    var jsWatchdog by remember { mutableStateOf<Runnable?>(null) }
    // Scroll-mode crossing is armed only by a real user touch: the restore
    // scrollTo on chapter load also fires scroll events, and arming on load
    // would auto-cross a chapter the user merely reopened at its bottom.
    var crossArmed by remember { mutableStateOf(false) }
    // Comic gate: true once the comic page reports every image complete.
    // A comic chapter is only truly finished when its last image has
    // arrived; without this gate a half-loaded chapter would cross early.
    var comicImagesReady by remember { mutableStateOf(false) }
    var atBottom by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun scrollKey(index: Int): String = "scroll_" + session.bookKey + "#" + index

    // Page position is stored per chapter alongside the scroll anchor: the two
    // modes have different coordinate systems and a book can be opened in
    // either, so both are kept and each mode resumes from its own.
    fun pageKey(index: Int): String = "page_" + session.bookKey + "#" + index

    fun saveScroll() {
        val wv = webViewRef ?: return
        if (!paged) prefs.edit().putInt(scrollKey(chapterIndex), wv.scrollY).apply()
    }

    fun savePage(index: Int, page: Int) {
        prefs.edit().putInt(pageKey(index), page).apply()
    }

    DisposableEffect(Unit) {
        onDispose {
            jsWatchdog?.let { mainHandler.removeCallbacks(it) }
            saveScroll()
        }
    }

    // ---- Page turning -------------------------------------------------
    // Paged chapters are JS-paginated (WebReaderHtml): the document
    // measures its own paragraphs and renders one viewport-sized page
    // at a time, so there is no longer a hundreds-of-viewports-wide
    // column strip for the compositor to choke on (that strip was the
    // paged-blank cause in 1.81-1.83). Turning a page calls the
    // document's __goPage directly; the old #c/#vp scrollLeft path
    // stays only as a fallback for documents without __goPage.
    // Driven through loadUrl("javascript:") because that is
    // order-independent with the document lifecycle — it queues
    // until the page exists, unlike evaluateJavascript on a reloading view.
    fun showPage(page: Int) {
        val wv = webViewRef ?: return
        wv.loadUrl(
            "javascript:(function(){if(window.__goPage){window.__goPage(" + page + ");return;}" +
                "var c=document.getElementById('c');" +
                "var v=document.getElementById('vp');" +
                "if(c&&v){var w=c.clientWidth||1;v.scrollLeft=" + page + "*w;}})()"
        )
    }

    fun seekPage(page: Int) {
        val target = page.coerceIn(0, maxOf(0, pageCount - 1))
        if (target == pageIndex) return
        pageIndex = target
        showPage(target)
        savePage(chapterIndex, target)
    }

    val settingsKey = readerSettings?.let {
        it.themeIndex.toString() + "|" + it.fontSizeSp + "|" + it.lineHeightMult
    } ?: ""

    val currentPaged by rememberUpdatedState(paged)

    // Shared by the factory and by update(): toggling paged at runtime reloads
    // the document, and settings applied only in the factory would not follow
    // (a scroll-mode start leaves JS off and no bridge behind).
    val onBridgePageCount: (Int) -> Unit = { count ->
        // 上报本身就是 JS 活着的证据：哪怕页数没变也要记下来，好让
        // 看门狗区分“JS 死了”和“JS 活着但画不出来”。
        pageCountReported = true
        FloatLog.append("app", null, "paged-bridge pageCount=" + count)
        if (count > 0 && count != pageCount) {
            pageCount = count
            pageIndex = pageIndex.coerceIn(0, count - 1)
            showPage(pageIndex)
        }
    }

    LaunchedEffect(chapterIndex, settingsKey, currentPaged) {
        loading = true
        crossArmed = false
        comicImagesReady = false
        atBottom = false
        try {
            chapter = loadChapter(chapterIndex)
            lastGood = chapterIndex
            // A fresh document means the old page count and offset are gone;
            // resume from the stored page for this chapter once the new
            // document reports its own width.
            pageCount = 1
            pageIndex = prefs.getInt(pageKey(chapterIndex), 0)
        } catch (th: Throwable) {
            Toast.makeText(context, "加载失败", Toast.LENGTH_SHORT).show()
            if (chapterIndex != lastGood) chapterIndex = lastGood
        } finally {
            loading = false
        }
    }

    val currentChapter = chapter
    val episodeName = session.episodes.getOrNull(chapterIndex)?.name?.takeIf { it.isNotBlank() }
    val title = currentChapter?.title ?: episodeName ?: session.title
    val canShowSettings = readerSettings != null && onSettingsChange != null
    val hasPrevChapter = chapterIndex > 0
    val hasNextChapter = chapterIndex < session.episodes.size - 1

    // Worksheet-level handlers so the buttons and the swipe share one path.
    fun gotoPrevPage() {
        if (!paged) return
        if (pageIndex > 0) {
            seekPage(pageIndex - 1)
        } else if (hasPrevChapter) {
            savePage(chapterIndex, 0)
            chapterIndex -= 1
        }
    }

    fun gotoNextPage() {
        if (!paged) return
        if (pageIndex < pageCount - 1) {
            seekPage(pageIndex + 1)
        } else if (hasNextChapter) {
            savePage(chapterIndex, 0)
            chapterIndex += 1
        }
    }

    // Scroll mode: reaching the bottom advances to the next chapter, so
    // reading onward never dead-ends. Paged mode already crossed at its
    // last page; scroll mode had no equivalent until now. One bottom-reach
    // advances exactly one chapter: crossing disarms until the next touch,
    // so a single fling can never chain through several chapters.
    fun crossToNextFromScroll() {
        if (chapterIndex >= session.episodes.size - 1) return
        crossArmed = false
        saveScroll()
        chapterIndex += 1
    }

    fun onScrollChanged(scrollY: Int, viewportHeight: Int, contentHeightPx: Int) {
        if (paged || loading || !crossArmed) return
        val threshold = (2 * context.resources.displayMetrics.density).toInt() + 1
        atBottom = scrollY + viewportHeight >= contentHeightPx - threshold
        if (!atBottom) return
        // Novels lay out once, so arrival at the bottom is final. Comics
        // grow while images load, so a comic crosses only once its page
        // reports every image complete; otherwise the last image could be
        // skipped by a jump from a half-grown bottom.
        if (!novelStyle && !comicImagesReady) return
        crossToNextFromScroll()
    }

    // The comic bridge fires on a background thread; state writes and the
    // crossing decision stay on the main thread. If the user is already
    // sitting at the bottom when the last image lands, the chapter turns
    // without needing another scroll event.
    fun onComicImagesReady() {
        comicImagesReady = true
        if (paged || loading || !crossArmed || !atBottom) return
        crossToNextFromScroll()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { saveScroll(); onBack() }) {
                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // Top bar keeps only back and title: navigation and adjustments
        // live in the bottom bar, where thumbs reach them.
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        // 1.96：滚动条改覆盖式：竖向滚动条不再占布局宽度，
                        // 否则正文右边实际边距 = padding + 滚动条宽，比左边宽一截。
                        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                        // JS is needed for the paged layout to report its column
                        // count and to accept the page offset, and for a comic
                        // page to report when its images are all complete.
                        // Only app-generated HTML runs here (loadDataWithBaseURL,
                        // no remote page loads), so the usual WebView JS caution
                        // applies less.
                        settings.javaScriptEnabled = paged || !novelStyle
                        settings.domStorageEnabled = false
                        settings.loadsImagesAutomatically = true
                        // 1.97：开内置缩放（捏合手势），不显示加减按钮：
                        // 之前 builtInZoomControls 关着，页面 viewport 再允许捏也没用。
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        webViewClient = HeaderWebViewClient(
                            headersProvider = { chapter?.headers ?: emptyMap() },
                            pendingRestoreProvider = { pendingRestore },
                        )
                        if (paged) {
                            addJavascriptInterface(
                                ReaderBridge(onBridgePageCount) { msg ->
                                    FloatLog.append("app", null, msg)
                                },
                                "AVReader",
                            )
                        }
                        if (!novelStyle) {
                            addJavascriptInterface(
                                ComicBridge({ mainHandler.post { onComicImagesReady() } }),
                                "ComicBridge",
                            )
                        }
                        // Swipe left/right turns a page in paged mode. Registered
                        // on the view so it sees the gesture before the WebView
                        // consumes it as a drag; in scroll mode it does nothing
                        // and the view is left alone.
                        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                            // onDown must return true: a touch listener that
                            // returns false for DOWN never receives the rest
                            // of the gesture, which is why tap zones and
                            // flings were dead in paged mode. The cost is
                            // that the WebView sees no touch in paged mode,
                            // so long-press text selection is gone there —
                            // the same trade the native reader already makes.
                            override fun onDown(e: MotionEvent): Boolean = true
                            // Tap zones, like the native reader: left third
                            // back, right third forward, middle does nothing
                            // (the bottom bar holds chapters and settings).
                            // Only in paged mode; scrolling keeps its taps.
                            override fun onSingleTapUp(e: MotionEvent): Boolean {
                                if (!currentPaged) return false
                                val wv = webViewRef ?: return false
                                val w = wv.width
                                if (w <= 0) return false
                                return when {
                                    e.x < w / 3f -> { gotoPrevPage(); true }
                                    e.x > w * 2f / 3f -> { gotoNextPage(); true }
                                    else -> false
                                }
                            }
                            override fun onFling(
                                e1: MotionEvent?,
                                e2: MotionEvent,
                                velocityX: Float,
                                velocityY: Float,
                            ): Boolean {
                                if (!currentPaged) return false
                                val dx = (e2.x - (e1?.x ?: return false))
                                val dy = (e2.y - (e1?.y ?: return false))
                                if (kotlin.math.abs(dx) < 40 || kotlin.math.abs(dx) < kotlin.math.abs(dy)) {
                                    return false
                                }
                                if (dx < 0) gotoNextPage() else gotoPrevPage()
                                return true
                            }
                        })
                        setOnTouchListener { _, ev ->
                            if (ev.action == MotionEvent.ACTION_DOWN) crossArmed = true
                            if (currentPaged) detector.onTouchEvent(ev) else false
                        }
                        // Scroll-mode bottom crossing: contentHeight is CSS
                        // pixels, so it is scaled to device pixels before it
                        // is compared with the device-pixel scroll position.
                        setOnScrollChangeListener { v, _, scrollY, _, _ ->
                            if (!currentPaged) {
                                val wv = v as WebView
                                val contentPx = (wv.contentHeight * wv.scale).toInt()
                                maxScrollPx = (contentPx - wv.height).coerceAtLeast(0)
                                scrollFraction = if (maxScrollPx > 0) {
                                    (scrollY.toFloat() / maxScrollPx).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                onScrollChanged(scrollY, wv.height, contentPx)
                            }
                        }
                        webViewRef = this
                    }
                },
                update = { wv ->
                    val ch = currentChapter
                    if (ch != null && wv.tag != ch) {
                        wv.tag = ch
                        val newHtml = ch.html
                        val sameChapterRerender = lastRenderedChapterIndex == chapterIndex
                        lastRenderedChapterIndex = chapterIndex
                        // Settings and the bridge follow the document, not just
                        // the factory: toggling paged at runtime reloads here,
                        // and a scroll-mode start would otherwise leave JS off
                        // and no bridge behind for the paged document.
                        wv.settings.javaScriptEnabled = paged || !novelStyle
                        if (paged) {
                            wv.addJavascriptInterface(
                                ReaderBridge(onBridgePageCount) { msg ->
                                    FloatLog.append("app", null, msg)
                                },
                                "AVReader",
                            )
                        }
                        if (!novelStyle) {
                            wv.addJavascriptInterface(
                                ComicBridge({ mainHandler.post { onComicImagesReady() } }),
                                "ComicBridge",
                            )
                        }
                        if (paged) {
                            // Column layout: the page offset is re-applied when
                            // the document reports its width and count, so no
                            // scroll restore is needed here. A new chapter
                            // resumes from its stored page (the loader already
                            // restored it); resetting to 0 here would wipe it.
                            pageIndex = if (sameChapterRerender) pageIndex else prefs.getInt(pageKey(chapterIndex), 0)
                            FloatLog.append("app", null, "paged-doc load htmlLen=" + newHtml.length + " js=" + wv.settings.javaScriptEnabled)
                            // 1.86 看门狗：2 秒内没见桥上报页数，JS 就是没跑
                            // 起来——自动回落到上下滚动（只改本次显示，不改
                            // 记忆的模式偏好，下次进书仍先试左右）。
                            pageCountReported = false
                            jsWatchdog?.let { mainHandler.removeCallbacks(it) }
                            val loadedDoc = ch
                            val watchdog = Runnable {
                                if (paged && !pageCountReported && webViewRef?.tag == loadedDoc) {
                                    FloatLog.append("app", null, "paged-js watchdog: no pageCount in 2s, fallback to scroll")
                                    Toast.makeText(context, "左右分页没跑起来，先按上下显示", Toast.LENGTH_SHORT).show()
                                    paged = false
                                    onPagedChange?.invoke(false)
                                }
                            }
                            jsWatchdog = watchdog
                            mainHandler.postDelayed(watchdog, 2000)
                            wv.loadDataWithBaseURL(null, newHtml, "text/html", "utf-8", null)
                        } else {
                            pendingRestore = if (sameChapterRerender) {
                                webViewRef?.scrollY ?: 0
                            } else {
                                prefs.getInt(scrollKey(chapterIndex), 0)
                            }
                            wv.loadDataWithBaseURL(null, newHtml, "text/html", "utf-8", null)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
        if (showAdjust && readerSettings != null && onSettingsChange != null) {
            WebReaderAdjustPanel(
                settings = readerSettings,
                novelStyle = novelStyle,
                paged = paged,
                onTogglePaged = { on ->
                    paged = on
                    pageIndex = 0
                    pageCount = 1
                    prefs.edit().putBoolean("paged_" + session.bookKey, on).apply()
                    onPagedChange?.invoke(on)
                },
                onWebReaderToggle = { enabled ->
                    onSwitchReader?.invoke(chapterIndex, enabled)
                },
                onChange = onSettingsChange,
                onDismiss = { showAdjust = false },
            )
        }
        // Bottom bar, identical in both modes: chapter buttons plus a
        // tappable chapter indicator that opens the catalog. Page
        // buttons are gone on purpose: in paged mode pages still turn
        // by tapping the left/right thirds and by swiping, and chapters
        // still cross at the edges; the bar no longer mixes two units
        // (pages and chapters), which read as the same thing.
        // 1.95：滚动进度滑杆，拖动跳位置（滚动模式；左右模式无滚动，不显示）。
        if (!paged) {
            ReaderSlider(
                value = scrollFraction,
                onValueChange = { f ->
                    scrollFraction = f
                    webViewRef?.scrollTo(0, (f * maxScrollPx).toInt())
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = hasPrevChapter,
                onClick = {
                    if (paged) {
                        savePage(chapterIndex, 0)
                        chapterIndex -= 1
                    } else {
                        saveScroll()
                        chapterIndex -= 1
                    }
                },
            ) { Text(text = "上一章") }
            TextButton(
                enabled = session.episodes.isNotEmpty(),
                onClick = { showCatalog = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (session.episodes.isNotEmpty()) {
                        "第 " + (chapterIndex + 1) + " / 共 " + session.episodes.size + " 章"
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
            TextButton(
                enabled = hasNextChapter,
                onClick = {
                    if (paged) {
                        savePage(chapterIndex, 0)
                        chapterIndex += 1
                    } else {
                        saveScroll()
                        chapterIndex += 1
                    }
                },
            ) { Text(text = "下一章") }
            if (canShowSettings) {
                TextButton(onClick = { showAdjust = !showAdjust }) {
                    Text(text = "设置")
                }
            }
        }
        if (showCatalog) {
            WebReaderCatalogDialog(
                episodes = session.episodes,
                currentIndex = chapterIndex,
                onJump = { index ->
                    showCatalog = false
                    if (index != chapterIndex) {
                        if (paged) savePage(chapterIndex, 0) else saveScroll()
                        chapterIndex = index
                    }
                },
                onDismiss = { showCatalog = false },
            )
        }
    }
}

/**
 * Chapter catalog for the WebView readers: the full episode list, opened
 * at the current chapter, tap to jump. Jumping saves the departing
 * chapter exactly like the chapter buttons do, so the two paths stay
 * consistent; the new chapter resumes from its stored page or scroll
 * anchor through the normal chapter-load path.
 */
@Composable
private fun WebReaderCatalogDialog(
    episodes: List<ReaderEpisode>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceIn(0, maxOf(0, episodes.size - 1)),
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f),
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Text(
                        text = "章节", // i18n: keep
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(text = "关闭") // i18n: keep
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    itemsIndexed(episodes) { index, episode ->
                        val name = episode.name.takeIf { it.isNotBlank() }
                            ?: ("第 " + (index + 1) + " 章")
                        val isCurrent = index == currentIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onJump(index) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Adjustments panel for the WebView readers, docked above the bottom bar
 * instead of a full-screen dialog. Same content as the native reader's
 * NovelSettingsDialog — same theme swatches, same sliders, same ranges,
 * same labels — so switching between the two readers does not feel like
 * learning a second app. The page-turn switch is WebView-only: the native
 * reader has three turn modes, this one has scrolling and page turns.
 */
@Composable
private fun WebReaderAdjustPanel(
    settings: NovelSettings,
    novelStyle: Boolean,
    paged: Boolean,
    onTogglePaged: (Boolean) -> Unit,
    onWebReaderToggle: ((Boolean) -> Unit)? = null,
    onChange: (NovelSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "阅读设置", // i18n: keep
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(text = "完成") // i18n: keep
                    }
                }

                // 1.93：阅读器路由开关，小说漫画共用。Web 面板里它是开的，
                // 拨关就切到自带阅读器；自带面板里它是关的，拨开就切回 Web。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Web 阅读器", // i18n: keep
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = ReaderStore.useWebReader(),
                        onCheckedChange = { onWebReaderToggle?.invoke(it) },
                    )
                }

                Text(text = "主题", style = MaterialTheme.typography.labelLarge) // i18n: keep
                WebNovelThemeGrid(
                    selected = settings.themeIndex,
                    onPick = { index ->
                        onChange(settings.copy(themeIndex = index))
                    },
                )

                if (novelStyle) {
                    // 1.93：Web 里翻页方式整行拿掉（小说漫画都只有上下滚动），
                    // 字号行距以下不动。
                    Text(
                        text = "字号 ${settings.fontSizeSp.roundToInt()}sp", // i18n: keep
                        style = MaterialTheme.typography.labelLarge,
                    )
                    ReaderSlider(
                        value = settings.fontSizeSp,
                        onValueChange = { onChange(settings.copy(fontSizeSp = it)) },
                        valueRange = 13f..20f,
                    )
                    Text(
                        text = "行距 ${"%.1f".format(settings.lineHeightMult)}", // i18n: keep
                        style = MaterialTheme.typography.labelLarge,
                    )
                    ReaderSlider(
                        value = settings.lineHeightMult,
                        onValueChange = { onChange(settings.copy(lineHeightMult = it)) },
                        valueRange = 1.2f..2.0f,
                    )
                    Text(
                        text = "改动即时生效", // i18n: keep
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(2.dp))
            }
    }
}

/**
 * The native reader's NovelThemeGrid is private to NovelReaderScreen, so the
 * WebView panel carries its own copy. Same swatches, same 44dp circles, same
 * first-glyph label — keep the two in sync if the theme list ever changes.
 */
@Composable
private fun WebNovelThemeGrid(selected: Int, onPick: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NovelThemes.ALL.forEachIndexed { index, theme ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(theme.background))
                    .clickable { onPick(index) }
                    .then(
                        if (isSelected) {
                            Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = theme.name.take(1),
                    color = Color(theme.text),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}