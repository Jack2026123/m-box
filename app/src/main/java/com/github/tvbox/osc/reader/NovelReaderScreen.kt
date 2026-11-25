package com.github.tvbox.osc.reader

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.drawText
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.ui.isLandscapeOrientation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

// 1.96：首行缩进拿掉（改空）：缩进本身就是左右不对称——每段首行左边多两个字，
// 要两边边距一样就得拿掉；想要回来把这里改回两个全角空格即可。
private const val INDENT = ""

@Composable
fun NovelReaderScreen(
    session: ReaderSession,
    onBack: () -> Unit,
    // 1.93：路由开关回调（当前章，目标路由）。设置弹框里的开关拨动时
    // 由 activity 接手：存偏好后用当前章重进另一个阅读器。
    onSwitchReader: ((Int, Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val episodes = session.episodes
    val totalChapters = max(1, episodes.size)

    var settings by remember { mutableStateOf(ReaderStore.novelSettings()) }
    // 1.97：捏合改字号：起手记基准字号，累计缩放比换算，松手保存。
    var pinchBaseFontSize by remember { mutableFloatStateOf(0f) }
    fun pinchFontStart() {
        pinchBaseFontSize = settings.fontSizeSp
    }
    fun pinchFontZoom(factor: Float) {
        val size = (pinchBaseFontSize * factor).coerceIn(13f, 30f)
        if (size != settings.fontSizeSp) settings = settings.copy(fontSizeSp = size)
    }
    fun pinchFontEnd() {
        ReaderStore.saveNovelSettings(settings)
    }
    var chapterIndex by remember {
        mutableIntStateOf(
            session.startIndex.coerceIn(0, totalChapters - 1)
        )
    }
    var bodyText by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // The reader opens immersive: clean text, no bars. The bars are
    // summoned only by a long-press (and come back when auto-read
    // ends naturally); nothing at entry may raise them.
    var barsVisible by remember { mutableStateOf(false) }
    var autoRead by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(ReaderStore.bookmarks(session.bookKey)) }
    var collected by remember { mutableStateOf(false) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(1) }
    var loadTick by remember { mutableIntStateOf(0) }
    // Bumped by every interaction with the bars themselves, so the
    // idle auto-hide below only fires after real inactivity.
    var barsTouchTick by remember { mutableIntStateOf(0) }
    // Set when TTS narration flows into the next chapter by itself:
    // the load below must not stop the engine, and starts speaking
    // the new chapter once its text is in.
    var pendingTtsChapter by remember { mutableStateOf(false) }
    // Chapter whose scroll body should open at its end (crossing
    // backwards past the top of the current chapter); -1 = open at top.
    var landAtEndFor by remember { mutableIntStateOf(-1) }
    // 2.54 修：上下模式本章里人往下翻过没有。手指翻到顶跨章的开关——刚开章
    // 就在顶上，没往下翻过就不跨，不然一开章就跳回上一章。
    var scrolledDownInChapter by remember { mutableStateOf(false) }
    // Chapter whose PAGED body should land on its last page (paging
    // backwards across a chapter edge); -1 = land normally.
    var landLastPageForChapter by remember { mutableIntStateOf(-1) }
    // Chapter the current pageCount belongs to, so the cross-chapter
    // landing below can never fire on a stale count from the
    // previous chapter.
    var pageCountForChapter by remember { mutableIntStateOf(-1) }
    // Vertical-scroll chapter window (logtest25): extra chapters
    // appended after the anchor chapter in the scroll list, so
    // finger scrolling crosses chapter boundaries without a reload
    // or a position jump. Each entry is (chapterIndex, paragraphs);
    // indices are strictly ascending and each chapter appears at
    // most once, so the scroll list's text is exactly the
    // concatenation of the loaded chapters' paragraphs, in order,
    // with no gap and no overlap — the completeness invariant for
    // this mode (the list body is paragraph-keyed, there is no
    // per-page boundary in it at all).
    var scrollChunks by remember { mutableStateOf<List<Pair<Int, List<String>>>>(emptyList()) }
    var scrollAppendBusy by remember { mutableStateOf(false) }
    // Chapter the top of the viewport is currently inside while
    // scrolling (vertical mode only; reported by the scroll body).
    var scrollHeadChapter by remember { mutableIntStateOf(chapterIndex) }

    val theme = NovelThemes.at(settings.themeIndex)
    val bgColor = Color(theme.background)
    val textColor = Color(theme.text)
    val accent = MaterialTheme.colorScheme.primary
    // The chapter the reader is actually looking at: the anchor
    // chapter everywhere, except in vertical mode where the viewport
    // may sit several appended chapters past the anchor.
    val effectiveChapter = if (settings.verticalMode) scrollHeadChapter else chapterIndex
    val chapterName = episodes.getOrNull(effectiveChapter)?.name ?: ""

    val listState = rememberLazyListState()
    val ttsCtl = rememberTtsController(context)

    // Speech-rate changes apply to the running narration (next chunk on).
    LaunchedEffect(settings.ttsRate) { ttsCtl.applyRate(settings.ttsRate) }

    // Leaving the reader leaves nothing running behind: narration and
    // auto-read stop with the screen (loads/pagination are composition
    // scoped and cancel on dispose as well).
    DisposableEffect(Unit) {
        onDispose {
            ttsCtl.stop()
            autoRead = false
        }
    }

    // No in-app volume control (the user removed the top-bar volume
    // row): loudness is the phone's media volume, adjusted with the
    // hardware keys / system panel. Narration follows it on both
    // channels — the system engine renders on STREAM_MUSIC via its
    // audio attributes, and the network player keeps its own volume
    // pinned at full so the system volume is the only control.

    LaunchedEffect(session.bookKey, session.vodId) {
        collected = try {
            AppGraph.collectRepository.isVodCollect(session.sourceKey, session.vodId)
        } catch (th: Throwable) {
            false
        }
    }

    // Chapter loading; restoring the saved page happens after pagination.
    LaunchedEffect(chapterIndex, loadTick) {
        loading = true
        loadError = null
        // The previous chapter's text is NOT cleared while the next
        // one loads (logtest25): the body stays on screen and the new
        // text swaps in atomically when it lands. The old flow nulled
        // bodyText first, so every chapter crossing — page turn at a
        // chapter edge included — flashed the full-screen spinner
        // even when the chapter was already in the loader's caches.
        // A spinner now only ever shows when there is no text at all
        // (the very first load); the paged bodies pre-paginate and
        // pre-render the next chapter's first page from the loader
        // prefetch (see NovelPageBody's lookahead), so a crossing in
        // the paged/curl modes lands on a ready page the moment the
        // swap happens, with no loading state in between.
        if (!pendingTtsChapter) ttsCtl.stop()
        try {
            val content = ReaderLoader.loadChapter(session, chapterIndex)
            val novel = content as? ReaderContent.Novel
            val text = novel?.inlineText
            if (text.isNullOrBlank()) {
                if (bodyText == null) {
                    loadError = "本章没有可读正文" // i18n: keep
                } else {
                    Toast.makeText(context, "本章没有可读正文", Toast.LENGTH_SHORT).show() // i18n: keep
                }
            } else {
                bodyText = text
                val restore = ReaderStore.progress(session.bookKey)
                pageIndex = if (restore != null && restore.chapterIndex == chapterIndex) {
                    restore.page
                } else {
                    0
                }
                if (pendingTtsChapter) {
                    pendingTtsChapter = false
                    ttsCtl.startNarration(text, settings.ttsRate, settings.ttsChannel)
                }
            }
        } catch (th: Throwable) {
            if (bodyText == null) {
                loadError = th.message ?: "加载失败" // i18n: keep
            } else {
                Toast.makeText(context, th.message ?: "加载失败", Toast.LENGTH_SHORT).show() // i18n: keep
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(effectiveChapter, pageIndex) {
        ReaderStore.saveProgress(
            session.bookKey,
            ReaderProgress(effectiveChapter, pageIndex.coerceAtLeast(0), 0),
        )
    }

    // Detail-page highlight follows the reader: the detail page
    // highlights the shelf record, and the novel reader never wrote
    // it before — it only kept its own ReaderStore progress — so the
    // detail page kept highlighting the chapter last tapped there
    // while the reader had moved on. Sync on chapter change only
    // (not on every page turn); a book not on the shelf is a no-op
    // inside updateProgress, and the reader's own progress record
    // stays the resume authority.
    LaunchedEffect(effectiveChapter) {
        session.legadoBookKey?.let { key ->
            com.github.tvbox.osc.reading.ReadingStore.updateProgress(
                key,
                effectiveChapter,
                chapterName,
            )
        }
    }

    // Any anchor change (chapter buttons, catalog, bookmark jump,
    // mode flows) resets the vertical scroll window: the appended
    // chapters belong to the old anchor and must not leak into the
    // new chapter's list.
    LaunchedEffect(chapterIndex) {
        scrollChunks = emptyList()
        scrollAppendBusy = false
        if (scrollHeadChapter != chapterIndex) scrollHeadChapter = chapterIndex
    }

    // Backwards chapter crossing in a paged mode lands on the
    // previous chapter's LAST page. The landing waits until the new
    // chapter's own page count has been reported (pageCountForChapter
    // matches), so it can never consume the old chapter's count.
    LaunchedEffect(chapterIndex, pageCount, pageCountForChapter, landLastPageForChapter, loading) {
        if (landLastPageForChapter == chapterIndex &&
            pageCountForChapter == chapterIndex && !loading && pageCount > 0
        ) {
            pageIndex = pageCount - 1
            landLastPageForChapter = -1
        }
    }

    // A finger scroll hides the bars (settled spec). Only real drag
    // interactions count: programmatic scrolls from the prev/next
    // page buttons or auto-read must not hide the bars the user is
    // looking at.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            if (interaction is DragInteraction.Start) {
                barsVisible = false
            }
        }
    }

    fun goChapter(target: Int) {
        if (target in 0 until totalChapters) {
            // Auto-read is stopped only by its own button (settled
            // spec), so chapter changes — manual or auto-read's own —
            // leave it running; the scroll body repositions itself.
            chapterIndex = target
            pageIndex = 0
            // The vertical scroll window belongs to the old anchor.
            scrollChunks = emptyList()
            scrollAppendBusy = false
            scrollHeadChapter = target
        }
    }

    // Appends the chapter after the last loaded one to the vertical
    // scroll window (logtest25). The scroll body asks for this when
    // its end comes into view; auto-read and the scroll-step button
    // ask too. Text comes through the loader's normal path, so an
    // appended chapter is usually already in the memory/disk caches
    // the prefetch keeps around the reading position — the append
    // itself only extends the list; the LazyColumn position does not
    // move, and nothing is cleared, so the crossing is seamless: no
    // spinner, no black/white flash, no jump.
    fun appendNextChunk() {
        if (scrollAppendBusy) return
        val anchor = chapterIndex
        val lastLoaded = scrollChunks.lastOrNull()?.first ?: anchor
        if (lastLoaded >= totalChapters - 1) return
        scrollAppendBusy = true
        scope.launch {
            try {
                val content = ReaderLoader.loadChapter(session, lastLoaded + 1)
                val text = (content as? ReaderContent.Novel)?.inlineText
                if (!text.isNullOrBlank() && chapterIndex == anchor) {
                    val ps = ReaderText.splitParagraphs(text).map { INDENT + it }
                    // Append only the immediate successor of the last
                    // loaded chapter: indices stay strictly ascending
                    // with no duplicates (the completeness invariant).
                    if (ps.isNotEmpty() &&
                        (scrollChunks.lastOrNull()?.first ?: anchor) == lastLoaded
                    ) {
                        scrollChunks = scrollChunks + (lastLoaded + 1 to ps)
                    }
                }
            } catch (th: Throwable) {
                // Silent: the chapter buttons / catalog remain the
                // fallback, and approaching the end again retries.
            } finally {
                scrollAppendBusy = false
            }
        }
    }

    // Idle auto-hide (new interaction rules): bars summoned by a tap
    // disappear again after a few seconds without any interaction.
    // Any open dialog/sheet suspends the timer, as does auto-read
    // (which has its own show/hide rules).
    LaunchedEffect(barsVisible, barsTouchTick, showCatalog, showBookmarks, showSettings, showThemes, autoRead) {
        if (!barsVisible || autoRead || showCatalog || showBookmarks || showSettings || showThemes) {
            return@LaunchedEffect
        }
        delay(4000)
        barsVisible = false
    }

    // Double-tap on the text drops a bookmark at the current reading
    // position (new interaction rules); the bookmark list itself is
    // unchanged and still opens from the top bar.
    fun addBookmarkHere() {
        val exists = bookmarks.any { it.chapterIndex == effectiveChapter && it.page == pageIndex }
        if (exists) {
            Toast.makeText(context, "此处已有书签", Toast.LENGTH_SHORT).show() // i18n: keep
            return
        }
        val excerpt = bodyText?.take(60)?.replace("\n", " ") ?: ""
        bookmarks = ReaderStore.addBookmark(
            session.bookKey,
            ReaderBookmark(effectiveChapter, pageIndex, excerpt, System.currentTimeMillis()),
        )
        Toast.makeText(context, "已添加书签", Toast.LENGTH_SHORT).show() // i18n: keep
    }

    fun pageForward() {
        if (settings.verticalMode) return
        if (pageIndex < pageCount - 1) {
            pageIndex += 1
        } else if (chapterIndex < totalChapters - 1) {
            goChapter(chapterIndex + 1)
        }
    }

    fun pageBack() {
        if (settings.verticalMode) return
        if (pageIndex > 0) {
            pageIndex -= 1
        } else if (chapterIndex > 0) {
            // Cross into the previous chapter at its last page; the
            // landing effect applies it once that chapter paginates.
            landLastPageForChapter = chapterIndex - 1
            goChapter(chapterIndex - 1)
        }
    }

    // Drag-driven chapter crossing from the paged bodies (pager edge
    // swipe / curl edge drag): forward opens the next chapter at its
    // first page, backward lands on the previous chapter's last page.
    fun crossChapter(forward: Boolean) {
        if (forward) {
            if (chapterIndex < totalChapters - 1) goChapter(chapterIndex + 1)
        } else if (chapterIndex > 0) {
            landLastPageForChapter = chapterIndex - 1
            goChapter(chapterIndex - 1)
        }
    }

    // Vertical mode: the prev/next page buttons scroll one viewport
    // of text; pressed while already at the very top/bottom they
    // cross into the neighbour chapter (landing at its end when
    // going backwards). Finger scrolling is unaffected.
    fun scrollStep(forward: Boolean) {
        scope.launch {
            val viewport = listState.layoutInfo.viewportSize.height.toFloat()
            if (viewport <= 0f) return@launch
            val consumed = runCatching {
                listState.animateScrollBy(if (forward) viewport else -viewport)
            }.getOrDefault(0f)
            if (forward && consumed < 1f) {
                // At the end of the loaded window: grow it in place
                // and step into the appended chapter (logtest25);
                // only when nothing more can be appended does the
                // button fall back to a plain chapter jump.
                val lastLoaded = scrollChunks.lastOrNull()?.first ?: chapterIndex
                if (lastLoaded < totalChapters - 1) {
                    val before = listState.layoutInfo.totalItemsCount
                    appendNextChunk()
                    delay(600)
                    if (listState.layoutInfo.totalItemsCount > before) {
                        runCatching { listState.animateScrollBy(viewport) }
                    } else if (chapterIndex < totalChapters - 1) {
                        goChapter(chapterIndex + 1)
                    }
                } else if (chapterIndex < totalChapters - 1) {
                    goChapter(chapterIndex + 1)
                }
            } else if (!forward && consumed > -1f && chapterIndex > 0) {
                landAtEndFor = chapterIndex - 1
                goChapter(chapterIndex - 1)
            }
        }
    }

    // 2.54 修：上下模式手指翻到顶也要跨章——原来只有上一页按钮能往回跨，
    // 手指翻到顶是死的。跟漫画上下模式同款：停稳在顶上 350ms 才跨，落到
    // 上一章末尾（landAtEndFor）；scrolledDownInChapter 挡着刚打开那一下
    // （开章就在顶上，没往下翻过就不跨，不然一开章就跳回上一章）。
    LaunchedEffect(chapterIndex) { scrolledDownInChapter = false }
    LaunchedEffect(listState, chapterIndex, settings.verticalMode) {
        if (!settings.verticalMode) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { idx ->
            if (idx > 0) scrolledDownInChapter = true
        }
    }
    LaunchedEffect(listState, chapterIndex, settings.verticalMode) {
        if (!settings.verticalMode) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()
            first != null && first.index == 0 && first.offset >= 0 &&
                !listState.isScrollInProgress && scrolledDownInChapter
        }.collectLatest { atTop ->
            if (atTop && chapterIndex > 0) {
                delay(350)
                val layout = listState.layoutInfo
                val first = layout.visibleItemsInfo.firstOrNull()
                if (first != null && first.index == 0 && first.offset >= 0 &&
                    !listState.isScrollInProgress && scrolledDownInChapter
                ) {
                    landAtEndFor = chapterIndex - 1
                    goChapter(chapterIndex - 1)
                }
            }
        }
    }

    // TTS narration reaching a chapter end flows into the next
    // chapter by itself; the load effect picks it up from there
    // (pendingTtsChapter) and keeps speaking.
    val latestChapterIndex by rememberUpdatedState(chapterIndex)
    LaunchedEffect(ttsCtl) {
        ttsCtl.onNarrationEnd = {
            if (latestChapterIndex in 0 until totalChapters - 1) {
                pendingTtsChapter = true
                goChapter(latestChapterIndex + 1)
            }
        }
    }

    fun endAutoReadNaturally() {
        autoRead = false
        barsVisible = true
    }

    // Auto-read engine: smooth scroll in vertical mode, page stepping
    // in page mode; chapter end flows into the next chapter; the very
    // end restores the bars (settled spec). pageIndex/pageCount are
    // deliberately NOT effect keys (read through updated-state
    // instead): the scroll body's progress callback rewrites pageIndex
    // as the text moves, and keying on it cancelled and restarted
    // this loop faster than it could ever advance — on device the
    // engine looked completely dead.
    val latestPageIndex by rememberUpdatedState(pageIndex)
    val latestPageCount by rememberUpdatedState(pageCount)
    LaunchedEffect(autoRead, loading, settings.pageTurnMode, chapterIndex) {
        if (!autoRead || loading || bodyText == null) return@LaunchedEffect
        if (settings.pageTurnMode == ReaderPageTurnModes.VERTICAL) {
            while (true) {
                delay(50)
                // Seconds-per-page pace translated to a steady
                // scroll: one viewport ("page") of text per
                // autoSecondsPerPage. The viewport is re-read each
                // step so a late first layout cannot skew the pace.
                val viewportPx = listState.layoutInfo.viewportSize.height.toFloat()
                val pxPerSecond = (if (viewportPx > 0f) viewportPx else 1000f) /
                    settings.autoSecondsPerPage
                val consumed = runCatching { listState.scrollBy(pxPerSecond * 0.05f) }.getOrDefault(0f)
                if (consumed == 0f) {
                    // Confirm the end on the layout itself before
                    // crossing chapters; a zero step mid-layout is
                    // not the end of the chapter.
                    delay(300)
                    val layout = listState.layoutInfo
                    val lastVisible = layout.visibleItemsInfo.lastOrNull()
                    val atEnd = lastVisible != null &&
                        lastVisible.index >= layout.totalItemsCount - 1 &&
                        lastVisible.offset + lastVisible.size <= layout.viewportEndOffset + 2
                    if (atEnd) {
                        // End of the LOADED window (logtest25): if
                        // chapters exist beyond it, append the next
                        // one in place and keep scrolling into it —
                        // auto-read no longer reloads at every chapter
                        // edge. Only a failed append (or the true end
                        // of the book) falls back to the old paths.
                        val lastLoaded = scrollChunks.lastOrNull()?.first ?: chapterIndex
                        if (lastLoaded < totalChapters - 1) {
                            val before = layout.totalItemsCount
                            appendNextChunk()
                            delay(600)
                            if (listState.layoutInfo.totalItemsCount > before) {
                                continue
                            }
                        }
                        if (chapterIndex < totalChapters - 1) {
                            goChapter(chapterIndex + 1)
                        } else {
                            endAutoReadNaturally()
                        }
                        return@LaunchedEffect
                    }
                }
            }
        } else {
            val intervalMs = (settings.autoSecondsPerPage * 1000f).toLong().coerceIn(2_000L, 60_000L)
            while (true) {
                delay(intervalMs)
                when {
                    latestPageIndex < latestPageCount - 1 -> pageIndex = latestPageIndex + 1
                    chapterIndex < totalChapters - 1 -> {
                        goChapter(chapterIndex + 1)
                        return@LaunchedEffect
                    }
                    else -> {
                        endAutoReadNaturally()
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    // Landscape is the TV form: the reader root takes focus so a
    // remote can turn pages with the dpad and open the bars with OK.
    // Portrait (touch) is untouched. The turn itself goes through
    // the exact same actions as the bottom bar's prev/next buttons,
    // so every mode (vertical scroll / horizontal / curl) and every
    // chapter-edge rule behaves identically for keys and touch.
    val landscape = isLandscapeOrientation()
    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(landscape) {
        if (landscape) runCatching { rootFocusRequester.requestFocus() }
    }

    fun readerTurn(forward: Boolean) {
        if (settings.verticalMode) {
            scrollStep(forward)
        } else if (forward) {
            pageForward()
        } else {
            pageBack()
        }
        barsVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .then(
                if (landscape) {
                    Modifier
                        .focusRequester(rootFocusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.DirectionRight, Key.DirectionDown, Key.PageDown -> {
                                        readerTurn(true)
                                        true
                                    }
                                    Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> {
                                        readerTurn(false)
                                        true
                                    }
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                        barsVisible = !barsVisible
                                        barsTouchTick++
                                        true
                                    }
                                    else -> false
                                }
                            }
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        // The bars OVERLAY the reading body instead of sharing a
        // Column with it: the body always owns the full screen, so
        // showing or hiding the bars can no longer resize it and
        // re-paginate the chapter under the reader — the page count
        // used to jump between the bars-closed and bars-open heights
        // (the same chapter showed 9 pages one moment, 15 the next)
        // and the reading position jumped with it. The bars still
        // glide in and out, floating over the text.
            Box(modifier = Modifier.fillMaxSize()) {
                ReaderChapterFade(chapterKey = chapterIndex, modifier = Modifier.fillMaxSize(), instant = autoRead) {
                when {
                    loading && bodyText == null -> {
                        CircularProgressIndicator(
                            color = accent,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    loadError != null && bodyText == null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        ) {
                            Text(text = loadError!!, color = textColor)
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { loadTick += 1 }) {
                                Text(text = "重试", color = accent) // i18n: keep
                            }
                        }
                    }
                    bodyText != null -> {
                        val paragraphs = remember(bodyText) {
                            ReaderText.splitParagraphs(bodyText!!).map { INDENT + it }
                        }
                        // Exactly one turn path is in the tree at a
                        // time: a mode switch disposes the previous
                        // body outright — its layers, bitmap windows
                        // and gesture handlers leave with it, so no
                        // residue of one path can ever paint or
                        // gesture inside another.
                        when (settings.pageTurnMode) {
                            ReaderPageTurnModes.VERTICAL -> {
                            NovelScrollBody(
                                chunks = listOf(chapterIndex to paragraphs) + scrollChunks,
                                settings = settings,
                                textColor = textColor,
                                listState = listState,
                                chapterKey = chapterIndex,
                                landAtEndFor = landAtEndFor,
                                onPositioned = { landAtEndFor = -1 },
                                onLongPress = {
                                    barsVisible = !barsVisible
                                    barsTouchTick++
                                },
                                onDoubleTapBookmark = { addBookmarkHere() },
                                onNeedMore = { appendNextChunk() },
                                onHeadChapter = { head ->
                                    if (head != scrollHeadChapter) scrollHeadChapter = head
                                },
                                onProgress = { fraction, count ->
                                    // Write only on a real change: this
                                    // callback fires at scroll rate, and
                                    // unguarded writes recomposed the
                                    // whole screen (fade layer and all)
                                    // on every 50 ms auto-read step and
                                    // every manual scroll frame.
                                    if (count != pageCount) pageCount = count
                                    val idx = (fraction * count).roundToInt()
                                        .coerceIn(0, max(0, count - 1))
                                    if (idx != pageIndex) pageIndex = idx
                                },
                                onPinchStart = { pinchFontStart() },
                                onPinchZoom = { factor -> pinchFontZoom(factor) },
                                onPinchEnd = { pinchFontEnd() },
                            )
                            }
                            else -> {
                            NovelPageBody(
                                paragraphs = paragraphs,
                                settings = settings,
                                textColor = textColor,
                                chapterKey = chapterIndex,
                                pageIndex = pageIndex,
                                instantTurns = autoRead,
                                onPageIndexChange = { pageIndex = it },
                                onPageCount = { count ->
                                    pageCount = count
                                    pageCountForChapter = chapterIndex
                                    if (pageIndex > count - 1) pageIndex = max(0, count - 1)
                                },
                                onTapPrevPage = { pageBack() },
                                onTapNextPage = { pageForward() },
                                onLongPressMenu = {
                                    barsVisible = !barsVisible
                                    barsTouchTick++
                                },
                                onChapterEdge = { forward -> crossChapter(forward) },
                                onDoubleTapBookmark = { addBookmarkHere() },
                                onUserScroll = { barsVisible = false },
                                onPrepareNextChapter = {
                                    // Next chapter's paragraphs for
                                    // the lookahead (content step):
                                    // through the loader, so it lands
                                    // in / comes from the same caches
                                    // the crossing itself will use.
                                    if (chapterIndex + 1 < totalChapters) {
                                        try {
                                            val content = ReaderLoader.loadChapter(
                                                session, chapterIndex + 1,
                                            )
                                            val text =
                                                (content as? ReaderContent.Novel)?.inlineText
                                            if (text.isNullOrBlank()) {
                                                null
                                            } else {
                                                ReaderText.splitParagraphs(text)
                                                    .map { INDENT + it }
                                            }
                                        } catch (th: Throwable) {
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                },
                                onPinchStart = { pinchFontStart() },
                                onPinchZoom = { factor -> pinchFontZoom(factor) },
                                onPinchEnd = { pinchFontEnd() },
                            )
                        }
                        }
                    }
                }
                }
            }

        // Top bar, composed AFTER the body on purpose (logtest25):
        // in a Box, later children draw over earlier ones, and the
        // paged/curl bodies paint opaque full-screen pages — with
        // the old order (bar first, body second) the summoned top
        // bar was composed but painted UNDER the page, so a
        // long-press appeared to raise only the bottom bar. Down
        // here the bar floats over the page like the bottom stack;
        // it still takes no part in the body's layout, so pagination
        // is unaffected.
        AnimatedVisibility(
            visible = barsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(160)),
        ) {
            NovelTopBar(
                chapterName = chapterName,
                bookmarked = bookmarks.any { it.chapterIndex == effectiveChapter && it.page == pageIndex },
                collected = collected,
                accent = accent,
                onBack = onBack,
                onOpenBookmarks = { showBookmarks = true },
                onToggleCollect = {
                    scope.launch {
                        try {
                            val vod = com.github.tvbox.osc.bean.VodInfo().apply {
                                id = session.vodId
                                name = session.title
                                pic = session.picture
                                sourceKey = session.sourceKey
                            }
                            if (collected) {
                                AppGraph.collectRepository.deleteVodCollect(session.sourceKey, vod)
                            } else {
                                AppGraph.collectRepository.insertVodCollect(session.sourceKey, vod)
                            }
                            collected = !collected
                        } catch (th: Throwable) {
                            // collect is best-effort from the reader
                        }
                    }
                },
                onInteract = { barsTouchTick++ },
            )
        }

        // Bottom overlay stack: the always-visible page footer and,
        // beneath it, the control bar when summoned. Both float over
        // the body; neither takes part in its layout, so the reading
        // area — and with it the pagination — never changes size.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            // Page progress footer (current page / total pages of chapter).
            Text(
                text = if (pageCount > 0) "${(pageIndex + 1).coerceAtMost(pageCount)} / $pageCount" else "",
                color = textColor.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 2.dp),
            )

            AnimatedVisibility(
                visible = barsVisible,
                enter = slideInVertically(tween(200)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
            ) {
                NovelBottomBar(
                    chapterIndex = effectiveChapter,
                    totalChapters = totalChapters,
                    autoRead = autoRead,
                    speaking = ttsCtl.speaking,
                    pageTurnMode = settings.pageTurnMode,
                    accent = accent,
                    onOpenCatalog = { showCatalog = true },
                    onChapterSeek = { target -> goChapter(target) },
                    onTurnModeChange = { mode ->
                        val updated = settings.copy(pageTurnMode = mode)
                        settings = updated
                        ReaderStore.saveNovelSettings(updated)
                    },
                    onPrevChapter = { if (effectiveChapter > 0) goChapter(effectiveChapter - 1) },
                    onNextChapter = { if (effectiveChapter < totalChapters - 1) goChapter(effectiveChapter + 1) },
                    onPrev = {
                        if (settings.verticalMode) scrollStep(false) else pageBack()
                    },
                    onNext = {
                        if (settings.verticalMode) scrollStep(true) else pageForward()
                    },
                    onToggleAuto = {
                        autoRead = !autoRead
                        if (autoRead) barsVisible = false
                    },
                    onToggleTts = { ttsCtl.toggle(bodyText, settings.ttsRate, settings.ttsChannel) },
                    onOpenThemes = { showThemes = true },
                    onOpenSettings = { showSettings = true },
                    onInteract = { barsTouchTick++ },
                )
            }
        }
    }

    if (showCatalog) {
        ReaderCatalogDialog(
            title = session.title,
            episodes = episodes,
            currentIndex = effectiveChapter,
            accent = accent,
            onPick = { index ->
                showCatalog = false
                goChapter(index)
            },
            onDismiss = { showCatalog = false },
        )
    }

    if (showBookmarks) {
        BookmarkDialog(
            bookmarks = bookmarks,
            episodes = episodes,
            currentBookmarked = bookmarks.any {
                it.chapterIndex == effectiveChapter && it.page == pageIndex
            },
            onAddCurrent = {
                val excerpt = bodyText?.take(60)?.replace("\n", " ") ?: ""
                bookmarks = ReaderStore.addBookmark(
                    session.bookKey,
                    ReaderBookmark(effectiveChapter, pageIndex, excerpt, System.currentTimeMillis()),
                )
            },
            onJump = { mark ->
                showBookmarks = false
                if (mark.chapterIndex == chapterIndex) {
                    pageIndex = mark.page
                } else {
                    chapterIndex = mark.chapterIndex
                    pageIndex = mark.page
                }
            },
            onDelete = { mark ->
                bookmarks = ReaderStore.removeBookmark(session.bookKey, mark)
            },
            onDismiss = { showBookmarks = false },
        )
    }

    if (showSettings) {
        NovelSettingsDialog(
            settings = settings,
            onChange = { updated ->
                val channelChanged = updated.ttsChannel != settings.ttsChannel
                settings = updated
                ReaderStore.saveNovelSettings(updated)
                // A channel switch while narration is running takes
                // effect immediately on the newly chosen channel
                // instead of silently applying to the next session.
                if (channelChanged && ttsCtl.speaking) {
                    ttsCtl.stop()
                    ttsCtl.startNarration(bodyText, updated.ttsRate, updated.ttsChannel)
                }
            },
            onWebReaderToggle = { enabled ->
                onSwitchReader?.invoke(effectiveChapter, enabled)
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showThemes) {
        AlertDialog(
            onDismissRequest = { showThemes = false },
            title = { Text(text = "主题", style = MaterialTheme.typography.titleMedium) }, // i18n: keep
            text = {
                HideStatusBarInDialogWindow()
                NovelThemeGrid(
                    selected = settings.themeIndex,
                    onPick = { index ->
                        val updated = settings.copy(themeIndex = index)
                        settings = updated
                        ReaderStore.saveNovelSettings(updated)
                        showThemes = false
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { showThemes = false }) { Text(text = "关闭") } // i18n: keep
            },
        )
    }
}

@Composable
private fun NovelTopBar(
    chapterName: String,
    bookmarked: Boolean,
    collected: Boolean,
    accent: Color,
    onBack: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onToggleCollect: () -> Unit,
    onInteract: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        // statusBarsPadding: when the system bar transiently appears
        // over the immersive reader, the bar's content slides below
        // it instead of being drawn under it; while the bar is
        // hidden the inset is zero and the bar hugs the top edge as
        // before.
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") // i18n: keep
                }
                Text(
                    text = chapterName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // The bookmark icon opens the bookmark list (settled
                // spec); adding a bookmark for the current page lives
                // at the top of that list.
                IconButton(onClick = onOpenBookmarks) {
                    Icon(
                        if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = "书签列表", // i18n: keep
                        tint = accent,
                    )
                }
                IconButton(onClick = onToggleCollect) {
                    Icon(
                        if (collected) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "收藏", // i18n: keep
                        tint = accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelBottomBar(
    chapterIndex: Int,
    totalChapters: Int,
    autoRead: Boolean,
    speaking: Boolean,
    pageTurnMode: Int,
    accent: Color,
    onOpenCatalog: () -> Unit,
    onChapterSeek: (Int) -> Unit,
    onTurnModeChange: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleAuto: () -> Unit,
    onToggleTts: () -> Unit,
    onOpenThemes: () -> Unit,
    onOpenSettings: () -> Unit,
    onInteract: () -> Unit,
) {
    // Drag position of the chapter slider; follows the real chapter
    // whenever the user is not dragging.
    var seekValue by remember(chapterIndex) { mutableFloatStateOf(chapterIndex.toFloat()) }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            // Row 1: chapter progress slider — drag to jump chapters.
            ReaderSlider(
                value = seekValue,
                onValueChange = {
                    onInteract()
                    seekValue = it
                },
                onValueChangeFinished = {
                    onChapterSeek(seekValue.roundToInt().coerceIn(0, totalChapters - 1))
                },
                valueRange = 0f..(totalChapters - 1).coerceAtLeast(0).toFloat(),
                steps = if (totalChapters > 2) totalChapters - 2 else 0,
                modifier = Modifier.fillMaxWidth(),
            )
            // Row 2: previous chapter | "chapter X / Y" (tap opens
            // the catalog) | next chapter.
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onPrevChapter, enabled = chapterIndex > 0) {
                    Text(text = "上一章") // i18n: keep
                }
                Text(
                    text = "第 ${chapterIndex + 1} / 共 $totalChapters 章", // i18n: keep
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onOpenCatalog() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                TextButton(onClick = onNextChapter, enabled = chapterIndex < totalChapters - 1) {
                    Text(text = "下一章") // i18n: keep
                }
            }
            // Row 3: previous page | the single page-turn three-way
            // capsules | next page. This row is the ONE place the
            // turn mode is chosen (the settings sheet no longer
            // carries it); it writes the single persisted "turn" key.
            // The five pieces split the row into five EQUAL slots:
            // the previous build used SpaceBetween with the three
            // capsules grouped in a nested row, and the group's
            // intrinsic width (~470dp on the test phone) overflowed
            // the screen — the last piece, "next page", was laid out
            // past the right edge and could not be seen or tapped.
            // Weighted slots measure each piece inside its own fifth
            // of the width, so all five are always fully visible.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onPrev, modifier = Modifier.weight(1f)) {
                    Text(text = "上一页", maxLines = 1) // i18n: keep
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ReaderModeChip(
                        label = "上下翻页", // i18n: keep
                        selected = pageTurnMode == ReaderPageTurnModes.VERTICAL,
                        onClick = { onTurnModeChange(ReaderPageTurnModes.VERTICAL) },
                    )
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ReaderModeChip(
                        label = "左右翻页", // i18n: keep
                        selected = pageTurnMode == ReaderPageTurnModes.HORIZONTAL,
                        onClick = { onTurnModeChange(ReaderPageTurnModes.HORIZONTAL) },
                    )
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ReaderModeChip(
                        label = "仿真翻页", // i18n: keep
                        selected = pageTurnMode == ReaderPageTurnModes.CURL,
                        onClick = { onTurnModeChange(ReaderPageTurnModes.CURL) },
                    )
                }
                TextButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text(text = "下一页", maxLines = 1) // i18n: keep
                }
            }
            // Row 4: auto-read | narration | theme | settings.
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onToggleAuto) {
                    Text(
                        text = if (autoRead) "停止自动阅读" else "自动阅读", // i18n: keep
                        color = if (autoRead) accent else MaterialTheme.colorScheme.onSurface,
                    )
                }
                TextButton(onClick = onToggleTts) {
                    Icon(
                        if (speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(text = if (speaking) "停止朗读" else "朗读") // i18n: keep
                }
                TextButton(onClick = onOpenThemes) {
                    Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(text = "主题") // i18n: keep
                }
                TextButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(text = "设置") // i18n: keep
                }
            }
        }
    }
}
/**
 * The readers' single gesture arbiter for tap / long-press /
 * double-tap, shared by the novel bodies and the comic image.
 *
 * It is a pure OBSERVER: it awaits the first down with
 * requireUnconsumed = false and never consumes a single change.
 * That is the whole point. The previous build layered the content
 * with full-screen detectTapGestures overlays, and
 * detectTapGestures CONSUMES the down change on the topmost hit
 * layer for its press scope; in the vertical novel body that
 * overlay sits as a top sibling ON the LazyColumn, so the column's
 * draggable — which awaits an unconsumed down — starved on every
 * press and finger scrolling died (paging and curl keep their own
 * owners and were unaffected, which is why only the vertical mode
 * broke). An observer cannot starve anything: the content's own
 * scroller/pager/curl claims drags exactly as if this layer were
 * not there.
 *
 * Arbitration, one owner, by priority:
 *  - the pointer travels past the touch slop -> the press is a
 *    drag; it belongs to the content, and the release fires
 *    NOTHING here (no tap trails a scroll or a page drag);
 *  - the press stays near-still for the long-press timeout ->
 *    the long-press fires once and the press is swallowed (its
 *    release fires no tap either); the first real movement
 *    cancels the long-press before it can fire;
 *  - otherwise the release is a tap, fired immediately (no 300 ms
 *    parking); a second tap inside the double-tap window and
 *    radius fires the double-tap instead and its own tap action
 *    is suppressed — the first tap already acted, so a double-tap
 *    in a turn zone steps once and bookmarks/zooms, and in the
 *    inert centre it is a pure double-tap.
 *
 * Callbacks receive the x of the press as a fraction of the
 * observed width, which is all the hosts' zone logic needs.
 */
internal fun Modifier.readerTapArbiter(
    key: Any = Unit,
    onTap: (Float) -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: (Float) -> Unit,
): Modifier = pointerInput(key) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis.toLong()
    var lastTapUptime = 0L
    var lastTapPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        var longFired = false
        var released = false
        var upPos = Offset.Zero
        var upUptime = 0L
        while (!released) {
            val event = if (!moved && !longFired) {
                val remaining = longPressMs -
                    (SystemClock.uptimeMillis() - down.uptimeMillis)
                if (remaining > 0L) {
                    val arrived = withTimeoutOrNull(remaining) {
                        awaitPointerEvent()
                    }
                    if (arrived == null) {
                        // Still pressed, near-still, deadline hit:
                        // the long-press wins this press outright.
                        longFired = true
                        onLongPress()
                        continue
                    }
                    arrived
                } else {
                    awaitPointerEvent()
                }
            } else {
                awaitPointerEvent()
            }
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: event.changes.firstOrNull() ?: break
            if ((change.position - down.position).getDistance() > slop) {
                moved = true
            }
            if (event.changes.none { it.pressed }) {
                released = true
                upPos = change.position
                upUptime = change.uptimeMillis
            }
        }
        if (released && !moved && !longFired) {
            if (upUptime - lastTapUptime <= 300L &&
                (upPos - lastTapPos).getDistance() <= 120f
            ) {
                lastTapUptime = 0L
                onDoubleTap(if (size.width > 0) upPos.x / size.width else 0.5f)
            } else {
                lastTapUptime = upUptime
                lastTapPos = upPos
                onTap(if (size.width > 0) upPos.x / size.width else 0.5f)
            }
        }
    }
}

/**
 * 1.97：小说捏合改字号。只在捏合时消费：单指点按/滚动一律不消费
 * （翻页、滚动、长按出栏、双击书签都不受影响），双指张合才消费并上报
 * 累计缩放比；手势结束回调一次，供调用方保存字号。
 */
private fun Modifier.novelPinchFontSize(
    onPinchStart: () -> Unit,
    onPinchZoom: (Float) -> Unit,
    onPinchEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinching = false
        var factor = 1f
        var started = false
        try {
            do {
                val event = awaitPointerEvent()
                if (event.changes.none { it.isConsumed }) {
                    val pressedCount = event.changes.count { it.pressed }
                    val zoomChange = event.calculateZoom()
                    if (!pinching && (pressedCount >= 2 || zoomChange != 1f)) {
                        pinching = true
                    }
                    if (pinching) {
                        if (!started) {
                            started = true
                            onPinchStart()
                        }
                        factor *= zoomChange
                        onPinchZoom(factor)
                        event.changes.forEach { change ->
                            if (change.positionChanged()) change.consume()
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        } finally {
            if (started) onPinchEnd()
        }
    }
}

@Composable
private fun NovelScrollBody(
    chunks: List<Pair<Int, List<String>>>,
    settings: NovelSettings,
    textColor: Color,
    listState: androidx.compose.foundation.lazy.LazyListState,
    chapterKey: Int,
    landAtEndFor: Int,
    onPositioned: () -> Unit,
    onLongPress: () -> Unit,
    onDoubleTapBookmark: () -> Unit,
    onNeedMore: () -> Unit,
    onHeadChapter: (Int) -> Unit,
    onProgress: (Float, Int) -> Unit,
    onPinchStart: () -> Unit,
    onPinchZoom: (Float) -> Unit,
    onPinchEnd: () -> Unit,
) {
    val style = TextStyle(
        color = textColor,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * settings.lineHeightMult).sp,
        // 1.95：纸书感，正文改衬线（两路统一）。
        fontFamily = FontFamily.Serif,
    )
    // The flat item space of the whole window: chapter k's paragraphs
    // occupy one contiguous run, in order. Boundary-ownership rule:
    // paragraph i of chapter k belongs to exactly one item, keyed
    // "c{k}:p{i}" — keys are globally unique across chapters, so the
    // LazyColumn can never drop or merge an item on a key collision,
    // and concatenating the items in order reproduces the loaded
    // chapters' text exactly (no gap, no overlap, no page boundary
    // exists in this mode at all).
    val flatKeys = remember(chunks) {
        val out = ArrayList<String>()
        for ((ch, ps) in chunks) {
            for (i in ps.indices) out.add("c$ch:p$i")
        }
        out
    }
    val flatTexts = remember(chunks) {
        val out = ArrayList<String>()
        for ((_, ps) in chunks) out.addAll(ps)
        out
    }
    // Flat index at which each chunk starts (parallel to chunks).
    val chunkStarts = remember(chunks) {
        val out = ArrayList<Int>(chunks.size)
        var acc = 0
        for ((_, ps) in chunks) {
            out.add(acc)
            acc += ps.size
        }
        out
    }
    // Chapter (re)positioning: a fresh anchor opens at its top,
    // except when the reader crossed into it backwards past the
    // current chapter's top — then it opens at its end. Keyed on the
    // anchor identity only, with the window shape checked inside:
    // appended chunks must never re-trigger positioning (that would
    // yank the reader back to the top mid-scroll), and a stale
    // window from the previous anchor must not be landed on — the
    // pass simply waits until the window is the anchor's alone.
    LaunchedEffect(chapterKey, chunks) {
        val anchorReady = chunks.isNotEmpty() && chunks.first().first == chapterKey
        if (anchorReady && flatTexts.isNotEmpty()) {
            if (landAtEndFor == chapterKey) {
                if (chunks.size == 1) {
                    listState.scrollToItem(flatTexts.lastIndex)
                    runCatching { listState.scrollBy(1_000_000f) }
                    onPositioned()
                }
            } else {
                listState.scrollToItem(0)
                onPositioned()
            }
        }
    }
    val progress by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            if (total <= 0) {
                0f to 1
            } else {
                val approxPages = max(1, total / 6)
                val first = listState.firstVisibleItemIndex
                (first.toFloat() / total.toFloat()).coerceIn(0f, 1f) to approxPages
            }
        }
    }
    LaunchedEffect(progress) { onProgress(progress.first, progress.second) }
    // Reading-frontier signals: which chapter the viewport top sits
    // in (drives the top bar / progress), and whether the loaded
    // window's end is about to come into view (drives the append).
    val needMoreState = rememberUpdatedState(onNeedMore)
    val headChapterState = rememberUpdatedState(onHeadChapter)
    val chunksState = rememberUpdatedState(chunks)
    val chunkStartsState = rememberUpdatedState(chunkStarts)
    val chapterKeyState = rememberUpdatedState(chapterKey)
    val frontier by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()
            val last = layout.visibleItemsInfo.lastOrNull()
            val curChunks = chunksState.value
            val curStarts = chunkStartsState.value
            var head = chapterKeyState.value
            if (first != null && curChunks.isNotEmpty()) {
                var idx = curStarts.size - 1
                while (idx > 0 && curStarts[idx] > first.index) idx--
                head = curChunks[idx].first
            }
            val nearEnd = last != null && layout.totalItemsCount > 0 &&
                last.index >= layout.totalItemsCount - 3
            head to nearEnd
        }
    }
    LaunchedEffect(frontier) {
        headChapterState.value(frontier.first)
        if (frontier.second) needMoreState.value()
    }
    // The gesture arbiter sits on the body itself and observes
    // presses WITHOUT consuming anything, so the LazyColumn keeps
    // its native finger scrolling. What this replaces was a
    // full-screen detectTapGestures overlay composed as a top
    // sibling OVER the column: detectTapGestures consumes the down
    // change on the topmost hit layer, and the column's draggable
    // underneath — which awaits an unconsumed down — starved on
    // every press. Finger scrolling in this mode was dead for
    // exactly that reason; the paged and curl bodies keep their
    // tap layers on a PARENT of the drag owner (hit-tested after
    // it), which is why they never showed it.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .readerTapArbiter(
                onTap = { },
                onLongPress = onLongPress,
                onDoubleTap = { onDoubleTapBookmark() },
            ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = if (isLandscapeOrientation()) 56.dp else 20.dp, vertical = 12.dp).novelPinchFontSize(onPinchStart, onPinchZoom, onPinchEnd),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Globally unique per-paragraph keys ("c{chapter}:p{i}"):
            // identity survives appends and recompositions, so no
            // paragraph is ever re-placed, dropped or duplicated
            // when the window grows mid-scroll.
            items(flatTexts.size, key = { flatKeys[it] }) { index ->
                Text(text = flatTexts[index], style = style)
            }
        }
    }
}

@Composable
private fun NovelPageBody(
    paragraphs: List<String>,
    settings: NovelSettings,
    textColor: Color,
    chapterKey: Int,
    pageIndex: Int,
    instantTurns: Boolean,
    onPageIndexChange: (Int) -> Unit,
    onPageCount: (Int) -> Unit,
    onTapPrevPage: () -> Unit,
    onTapNextPage: () -> Unit,
    onLongPressMenu: () -> Unit,
    onChapterEdge: (Boolean) -> Unit,
    onDoubleTapBookmark: () -> Unit,
    onUserScroll: () -> Unit,
    onPrepareNextChapter: suspend () -> List<String>?,
    onPinchStart: () -> Unit,
    onPinchZoom: (Float) -> Unit,
    onPinchEnd: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(
        color = textColor,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * settings.lineHeightMult).sp,
        // 1.95：纸书感，正文改衬线（两路统一）。
        fontFamily = FontFamily.Serif,
    )
    // The bottom inset is a constant 30dp (not the usual 12): the
    // floating page-number footer overlays the body's bottom-right
    // corner now that the bars float, and the pagination area must
    // keep the last line clear of it. Being constant, it cannot
    // re-paginate when the bars or the status bar come and go.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(start = if (isLandscapeOrientation()) 56.dp else settings.marginDp.dp, end = if (isLandscapeOrientation()) 56.dp else settings.marginDp.dp, top = settings.marginDp.dp, bottom = settings.marginDp.dp).novelPinchFontSize(onPinchStart, onPinchZoom, onPinchEnd)) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val pages = remember(paragraphs, settings, widthPx, heightPx) {
            paginate(paragraphs, style, measurer, widthPx, heightPx)
        }
        // 2.54 修：上报页数要跟章节走——原来只盯 pages.size，前后两章页数一样时
        // 尺寸不变、这个 effect 不重跑，onPageCount 就不报新章，pageCountForChapter
        // 一直是旧章，往回跨章的落点条件永远对不上，落不到上一章末页。
        // 加上 chapterKey，换章必报一次，新章页数再报上来，落点才能等到。
        LaunchedEffect(chapterKey, pages.size) { onPageCount(pages.size) }
        // One full-page bitmap build, shared by the curl surface's
        // snapshot provider and the chapter-lookahead below: paper
        // color first, then the page's text — identical inputs, so
        // work done through either path warms the same caches.
        val buildPageBitmap: (AnnotatedString, Int, Int) -> Bitmap? = { text, w, h ->
            if (w <= 0 || h <= 0) {
                null
            } else {
                try {
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val nativeCanvas = android.graphics.Canvas(bitmap)
                    CanvasDrawScope().draw(
                        density, layoutDirection, GraphicsCanvas(nativeCanvas),
                        Size(w.toFloat(), h.toFloat()),
                    ) {
                        drawRect(color = Color(NovelThemes.at(settings.themeIndex).background))
                        drawText(
                            textMeasurer = measurer,
                            text = text,
                            style = style,
                            topLeft = Offset.Zero,
                            size = Size(w.toFloat(), h.toFloat()),
                        )
                    }
                    bitmap
                } catch (th: Throwable) {
                    null
                }
            }
        }
        // Chapter-crossing lookahead (logtest25): once the reader is
        // within two pages of the chapter's end, the NEXT chapter is
        // fully prepared — (1) its text is loaded through the loader
        // (usually already in the prefetch caches), (2) it is
        // paginated on this body's exact geometry and style, which
        // fills the measurer's layout cache so the pagination after
        // the swap is a cache hit, and (3) in curl mode its first
        // page's bitmap is built once (and recycled), so the
        // surface's own first build runs on warm platform text/font
        // caches. The prepared artifacts themselves are discarded on
        // purpose: after the swap the body recomputes them from the
        // warmed caches, and the body never blanks meanwhile (the
        // host keeps the old chapter's text on screen until the new
        // text lands — no spinner state exists on this path).
        LaunchedEffect(chapterKey, pageIndex, pages.size) {
            if (pages.isNotEmpty() && pageIndex >= pages.size - 2) {
                val nextParagraphs = onPrepareNextChapter() ?: return@LaunchedEffect
                val nextPages = paginate(nextParagraphs, style, measurer, widthPx, heightPx)
                if (settings.pageTurnMode == ReaderPageTurnModes.CURL) {
                    nextPages.firstOrNull()?.let { first ->
                        buildPageBitmap(first, widthPx, heightPx)?.recycle()
                    }
                }
            }
        }
        // Tap observation layer shared by both turn paths, run by
        // the single gesture arbiter: single taps turn pages by
        // zone (left third = previous page, right third = next
        // page, centre inert) and NEVER raise the bars; a
        // long-press toggles the bars; a double-tap drops a
        // bookmark at the current page. The arbiter consumes
        // nothing, so each turn path underneath owns its drags
        // outright: a drag past the slop cancels both the tap and
        // the pending long-press, and its release fires no tap.
        val tapObserveModifier = Modifier
            .fillMaxSize()
            .readerTapArbiter(
                onTap = { fraction ->
                    when {
                        fraction < 1f / 3f -> onTapPrevPage()
                        fraction > 2f / 3f -> onTapNextPage()
                    }
                },
                onLongPress = onLongPressMenu,
                onDoubleTap = { onDoubleTapBookmark() },
            )
        if (settings.pageTurnMode == ReaderPageTurnModes.HORIZONTAL) {
            // Strict one-page presentation: the chapter is pre-paginated
            // into page data above, and the pager below is the single draw
            // surface per page. The pager owns the lanes — at rest it
            // composes only the current page; mid-drag the current page
            // plus at most one neighbour, each clipped into its own
            // page-width slot. No hand-drawn offsets, no second gesture
            // path, so pages can never bleed over one another.
            key(chapterKey, pages) {
                val pagerState = rememberPagerState(
                    initialPage = pageIndex.coerceIn(0, max(0, pages.size - 1)),
                ) { pages.size }
                // External page changes (bottom-bar buttons, auto-read,
                // bookmark jumps) drive the pager to the same page.
                // Auto-read turns are instant cuts, not slides: at
                // machine pace the reader always shows exactly one whole
                // page, never two half-pages in flight.
                LaunchedEffect(pageIndex, instantTurns) {
                    val target = pageIndex.coerceIn(0, max(0, pages.size - 1))
                    if (pages.isNotEmpty() && target != pagerState.currentPage && !pagerState.isScrollInProgress) {
                        if (instantTurns) {
                            pagerState.scrollToPage(target)
                        } else {
                            pagerState.animateScrollToPage(target)
                        }
                    }
                }
                // Finger turns report back, so the footer page number,
                // bookmarks and the saved progress track the real page.
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }.collectLatest { page ->
                        onPageIndexChange(page)
                    }
                }
                // A finger drag hides the bars; programmatic page steps
                // (buttons / auto-read) are not drag interactions and
                // leave the bars alone.
                LaunchedEffect(pagerState) {
                    pagerState.interactionSource.interactions.collectLatest { interaction ->
                        if (interaction is DragInteraction.Start) onUserScroll()
                    }
                }
                // Chapter-edge crossing: on the chapter's first/last
                // page the pager itself cannot move any further, so a
                // committed drag there (a quarter of the width) is
                // observed — never consumed — and reported as a
                // chapter step. The start page is latched at press
                // time: a normal turn INTO the edge page mid-gesture
                // must not chain into a chapter jump.
                val edgeSwipeModifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pages.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startPage = pagerState.currentPage
                            var lastX = down.position.x
                            var up = false
                            while (!up) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    up = true
                                } else {
                                    lastX = pressed.first().position.x
                                }
                            }
                            val dxTotal = lastX - down.position.x
                            when {
                                startPage >= pages.size - 1 &&
                                    dxTotal < -size.width * 0.25f -> onChapterEdge(true)
                                startPage <= 0 &&
                                    dxTotal > size.width * 0.25f -> onChapterEdge(false)
                            }
                        }
                    }
                Box(modifier = tapObserveModifier.then(edgeSwipeModifier)) {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 0,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        Text(
                            text = pages.getOrElse(page) { AnnotatedString("") },
                            style = style,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        } else {
            // Paper-curl turns: pages render from the same paginated
            // data, drawn off-screen into bitmaps by the turn surface
            // (one bitmap per page, windowed to the current page and
            // its neighbours).
            key(chapterKey, pages, settings.themeIndex) {
                val themeBgArgb = NovelThemes.at(settings.themeIndex).background.toInt()
                val themeBgColor = Color(NovelThemes.at(settings.themeIndex).background)
                val snapshotProvider: suspend (Int, Int, Int) -> Bitmap? =
                    { page, w, h ->
                        val text = pages.getOrNull(page)
                        if (text == null) null else buildPageBitmap(text, w, h)
                    }
                Box(modifier = tapObserveModifier) {
                    PageTurnSurface(
                        contentKey = pages,
                        pageCount = pages.size,
                        pageIndex = pageIndex,
                        backColorArgb = themeBgArgb,
                        // Auto-read in curl mode performs REAL
                        // programmatic curls (user-settled override of
                        // the instant-cut rule): the surface reports a
                        // page only once its curl has fully landed, so
                        // the engine's seconds-per-page dwell always
                        // runs on a settled, fully opaque page and the
                        // animation time sits inside the pace instead
                        // of stacking on top of it. The paged bitmaps
                        // are opaque full pages — unlike the old
                        // translucent scroll ghosting, a curl can never
                        // strand a half-transparent layer.
                        instantTurns = false,
                        handleTaps = false,
                        zoomEnabled = false,
                        snapshotProvider = snapshotProvider,
                        onPageSettled = onPageIndexChange,
                        onDragStart = onUserScroll,
                        onTap = { _ -> },
                        onDoubleTap = {},
                        onLongPress = {},
                        onChapterEdge = onChapterEdge,
                        fallback = { page ->
                            Text(
                                text = pages.getOrElse(page) { AnnotatedString("") },
                                style = style,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Real pagination: the whole chapter is measured once with the current
 * type settings, then cut into pages of the lines that fit the visible
 * height. Each page keeps the measured line breaks so what is rendered
 * is exactly what was measured.
 */
private fun paginate(
    paragraphs: List<String>,
    style: TextStyle,
    measurer: TextMeasurer,
    widthPx: Int,
    heightPx: Int,
): List<AnnotatedString> {
    if (widthPx <= 0 || heightPx <= 0 || paragraphs.isEmpty()) return listOf(AnnotatedString(""))
    val fullText = paragraphs.joinToString("\n\n")
    val result: TextLayoutResult = measurer.measure(
        text = AnnotatedString(fullText),
        style = style,
        constraints = Constraints(maxWidth = widthPx),
    )
    val lineCount = result.lineCount
    if (lineCount == 0) return listOf(AnnotatedString(""))
    // Cut pages by accumulating each line's PITCH (top of this line
    // to top of the next) — exactly how far the page's own standalone
    // layout advances per line. The previous build summed
    // (bottom − top) per line, which omits each line's share of the
    // line gap; over a full page the under-count grows to more than a
    // whole line, and the +1f admission slack then let one extra line
    // onto the page — drawn by the page surface but clipped in half
    // at its bottom edge (logtest25 screenshots). With pitch sums a
    // page's standalone layout can never exceed heightPx, so every
    // page holds only whole lines; the −1f bias errs toward one line
    // of bottom whitespace instead of a half line. Ranges stay
    // disjoint and cover every line, so the three turn modes share
    // one complete pagination.
    val pages = ArrayList<AnnotatedString>()
    var line = 0
    var lastUsedPx = 0f
    var lastLinePitch = 0f
    while (line < lineCount) {
        var usedPx = 0f
        var endLine = line
        while (endLine < lineCount) {
            val linePitch = if (endLine + 1 < lineCount) {
                result.getLineTop(endLine + 1) - result.getLineTop(endLine)
            } else {
                result.getLineBottom(endLine) - result.getLineTop(endLine)
            }
            if (endLine > line && usedPx + linePitch > heightPx - 1f) break
            usedPx += linePitch
            lastLinePitch = linePitch
            endLine++
        }
        if (endLine == line) endLine = line + 1
        lastUsedPx = usedPx
        var firstLine = line
        while (firstLine < endLine && result.getLineEnd(firstLine, visibleEnd = true) <= result.getLineStart(firstLine)) {
            firstLine++
        }
        if (firstLine == endLine) firstLine = endLine - 1
        val sb = StringBuilder()
        for (l in firstLine until endLine) {
            val start = result.getLineStart(l)
            val end = result.getLineEnd(l, visibleEnd = true)
            if (end > start) sb.append(fullText.substring(start, end))
            if (l < endLine - 1) sb.append('\n')
        }
        pages.add(AnnotatedString(sb.toString()))
        line = endLine
    }
    if (pages.isNotEmpty() && lastUsedPx + lastLinePitch <= heightPx - 1f) {
        val base = pages.last()
        val marker = "— 本章完 —"
        val text = base.text + "\n" + marker
        pages[pages.size - 1] = AnnotatedString(
            text = text,
            paragraphStyles = listOf(
                AnnotatedString.Range(
                    ParagraphStyle(textAlign = TextAlign.Center),
                    base.text.length + 1,
                    text.length,
                ),
            ),
        )
    }
    return pages
}

@Composable
private fun BookmarkDialog(
    bookmarks: List<ReaderBookmark>,
    episodes: List<ReaderEpisode>,
    currentBookmarked: Boolean,
    onAddCurrent: () -> Unit,
    onJump: (ReaderBookmark) -> Unit,
    onDelete: (ReaderBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "书签", style = MaterialTheme.typography.titleMedium) }, // i18n: keep
        text = {
            HideStatusBarInDialogWindow()
            Column {
            if (!currentBookmarked) {
                TextButton(onClick = onAddCurrent) {
                    Text(text = "＋ 添加当前页书签") // i18n: keep
                }
            }
            if (bookmarks.isEmpty()) {
                Text(text = "还没有书签，点上方按钮把当前页加进来。", style = MaterialTheme.typography.bodyMedium) // i18n: keep
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(bookmarks) { mark ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJump(mark) }
                                .padding(vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = episodes.getOrNull(mark.chapterIndex)?.name ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    text = mark.excerpt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            TextButton(onClick = { onDelete(mark) }) {
                                Text(text = "删除") // i18n: keep
                            }
                        }
                    }
                }
            }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "关闭") } // i18n: keep
        },
    )
}

/** The 8 reader themes as selectable swatches (settings + theme dialog). */
@Composable
private fun NovelThemeGrid(selected: Int, onPick: (Int) -> Unit) {
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

/**
 * Reader slider with a conventional look — thin track, round thumb.
 * The stock Material3 slider in this app's version renders a thick
 * track with a vertical-bar thumb, which reads as broken on device;
 * the custom thumb/track keep the same drag behaviour with the shape
 * every other reader (and the user) expects.
 */
@Composable
internal fun ReaderSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    // In this Material3 version only the state-based Slider overload
    // accepts custom thumb/track lambdas, so drive one SliderState
    // and mirror the caller's value through it. While the user drags,
    // the caller mirrors the same value straight back, so the sync
    // effect below only ever moves the thumb for external changes
    // (chapter jumps, restored settings) and never fights a gesture.
    val sliderState = remember(valueRange, steps) {
        // Positional on purpose: this Material3 alpha renamed the
        // range parameter (it surfaces as trackRange), so named
        // arguments for it do not resolve.
        SliderState(value, steps, valueRange)
    }
    LaunchedEffect(value) {
        if (sliderState.value != value) sliderState.value = value
    }
    Slider(
        state = sliderState,
        onValueChange = { newValue ->
            sliderState.value = newValue
            onValueChange(newValue)
        },
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(primary),
            )
        },
        track = { state: SliderState ->
            val fraction = state.coercedValueAsFraction.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(inactive),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(primary),
                )
            }
        },
    )
}

/**
 * Fades chapter content in on a MANUAL chapter change (open / next /
 * jump). Two hard rules keep this from ever painting a ghost:
 *
 *  - Auto-read-driven changes pass [instant]: no animation and no
 *    layer at all, the new chapter is simply there, fully opaque —
 *    a clean cut at machine pace can never strand a half-faded page
 *    under the next one.
 *  - The graphics layer exists ONLY while a manual fade is actually
 *    running. The moment the fade lands on alpha 1 the layer leaves
 *    the tree (settled), so during all steady-state reading —
 *    including every scroll, manual or automatic — the body is NOT
 *    inside an offscreen layer whose stale recording could replay
 *    under the live frame. The old build kept the layer forever,
 *    and the scroll body lived its whole life inside it.
 */
@Composable
internal fun ReaderChapterFade(
    chapterKey: Int,
    modifier: Modifier = Modifier,
    instant: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (instant) {
        Box(modifier = modifier) { content() }
        return
    }
    val alpha = remember(chapterKey) { Animatable(0f) }
    var settled by remember(chapterKey) { mutableStateOf(false) }
    LaunchedEffect(chapterKey) {
        alpha.animateTo(1f, tween(180))
        settled = true
    }
    val layerModifier = if (settled) {
        modifier
    } else {
        modifier.graphicsLayer { this.alpha = alpha.value }
    }
    Box(modifier = layerModifier) {
        content()
    }
}

/**
 * The reader settings panel as a real modal layer: a Dialog window
 * with its own gesture domain. The previous ModalBottomSheet build
 * was dead on device — its sheet drag / nested-scroll machinery sat
 * between the user and the content, so only the plain click chips
 * survived and every slider, theme swatch and channel chip ignored
 * touches. Inside a Dialog nothing else competes for the gestures:
 * every control below is bound straight to the live settings state,
 * applies immediately and persists through the same store keys.
 */
@Composable
private fun NovelSettingsDialog(
    settings: NovelSettings,
    onChange: (NovelSettings) -> Unit,
    onDismiss: () -> Unit,
    onWebReaderToggle: ((Boolean) -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        HideStatusBarInDialogWindow()
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                Text(text = "主题", style = MaterialTheme.typography.labelLarge) // i18n: keep
                NovelThemeGrid(
                    selected = settings.themeIndex,
                    onPick = { index -> onChange(settings.copy(themeIndex = index)) },
                )
                Text(text = "字号 ${settings.fontSizeSp.roundToInt()}sp", style = MaterialTheme.typography.labelLarge) // i18n: keep
                ReaderSlider(
                    value = settings.fontSizeSp,
                    onValueChange = { onChange(settings.copy(fontSizeSp = it)) },
                    valueRange = 13f..30f,
                )
                Text(text = "行距 ${"%.1f".format(settings.lineHeightMult)}", style = MaterialTheme.typography.labelLarge) // i18n: keep
                ReaderSlider(
                    value = settings.lineHeightMult,
                    onValueChange = { onChange(settings.copy(lineHeightMult = it)) },
                    valueRange = 1.2f..2.4f,
                )
                // 2.48：页边距（上下左右统一）。16..32，步长 2（9 档），随选随改并重排。
                Text(text = "页边距 ${settings.marginDp}dp", style = MaterialTheme.typography.labelLarge) // i18n: keep
                ReaderSlider(
                    value = settings.marginDp.toFloat(),
                    onValueChange = { onChange(settings.copy(marginDp = it.roundToInt().coerceIn(16, 32))) },
                    valueRange = 16f..32f,
                    steps = 7,
                )
                Text(text = "自动阅读速度 ${settings.autoSecondsPerPage.roundToInt()} 秒/页", style = MaterialTheme.typography.labelLarge) // i18n: keep
                ReaderSlider(
                    value = settings.autoSecondsPerPage,
                    onValueChange = { onChange(settings.copy(autoSecondsPerPage = it)) },
                    valueRange = 2f..60f,
                )
                Text(text = "朗读语速 ${"%.1f".format(settings.ttsRate)}x", style = MaterialTheme.typography.labelLarge) // i18n: keep
                ReaderSlider(
                    value = settings.ttsRate,
                    onValueChange = { onChange(settings.copy(ttsRate = it)) },
                    valueRange = 0.5f..2f,
                )
                Text(text = "朗读通道", style = MaterialTheme.typography.labelLarge) // i18n: keep
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReaderModeChip(
                        label = "自动", // i18n: keep
                        selected = settings.ttsChannel == 0,
                        onClick = { onChange(settings.copy(ttsChannel = 0)) },
                    )
                    ReaderModeChip(
                        label = "系统", // i18n: keep
                        selected = settings.ttsChannel == 1,
                        onClick = { onChange(settings.copy(ttsChannel = 1)) },
                    )
                    ReaderModeChip(
                        label = "网络", // i18n: keep
                        selected = settings.ttsChannel == 2,
                        onClick = { onChange(settings.copy(ttsChannel = 2)) },
                    )
                }
                // 1.93：阅读器路由开关。自带面板里它是关的，拨开就切回 Web。
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
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
internal fun ReaderModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 8.dp),
    ) {
        Text(
            // One type size down (logtest25): at labelLarge the three
            // middle capsules of the five-slot turn row ("上一页 /
            // 上下翻页 / 左右翻页 / 仿真翻页 / 下一页", equal weights)
            // clipped their fourth character ("上下翻"). labelMedium
            // plus tighter padding fits all four glyphs in the slot
            // with room to spare; maxLines stays 1 and no ellipsis is
            // ever acceptable here. Shared by both readers' rows.
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Narration controller with two channels, run decoupled from the UI
 * the way standalone readers (e.g. Legado) run narration as its own
 * service:
 *
 *  - system channel: the platform TextToSpeech engine. Nothing is
 *    spoken before the engine reports ready; a request made during
 *    init is queued and starts by itself once init lands. Chinese
 *    voice support is validated up front and every failure (no
 *    engine, init failure, missing Chinese data, utterance error)
 *    carries its reason.
 *  - network channel: [EdgeTtsPlayer] synthesises speech through the
 *    public Edge read-aloud service and plays it with the app's
 *    media pipeline — this is what makes narration work on phones
 *    that ship without a usable system TTS engine.
 *
 * Channel modes (persisted in the reader settings): 0 = auto — the
 * system channel when it is usable, otherwise the network channel;
 * 1 = system preferred; 2 = network only. A speak request made
 * while the system engine is still initialising waits briefly for
 * it (bounded by a watchdog: with no TTS service on the device the
 * init callback may never arrive at all). Whenever the system
 * channel turns out unusable — failed init, no engine, no Chinese
 * voice, a rejected or dying utterance — the reason is announced
 * and the chapter continues on the network channel, on the auto
 * and the system channel alike: narration never just goes silent.
 * Narration holds transient audio focus and flows into the next
 * chapter at a chapter end via [onNarrationEnd].
 */
private class TtsController(private val appContext: Context) {

    private var engine: TextToSpeech? = null
    private val network = EdgeTtsPlayer(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false

    var ready by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var speaking by mutableStateOf(false)
        private set

    /** Fired when narration finishes a chapter by itself (not via stop). */
    var onNarrationEnd: (() -> Unit)? = null

    /** 0 = idle, 1 = system channel, 2 = network channel. */
    private var channel = 0
    private var lastText: String? = null
    private var lastRate: Float = 1f
    private var lastUtteranceId: String? = null
    private var endCallbackArmed = false

    private var pendingText: String? = null
    private var pendingRate: Float = 1f

    /**
     * Resolves a narration request that was queued while the system
     * engine was still initialising. onInit normally resolves it
     * first; this watchdog exists because on a device with no TTS
     * service at all the init callback may simply never arrive —
     * without it the request would sit behind the "starting soon"
     * toast forever and the system channel would be pure silence.
     */
    private val initWatchdogRunnable = Runnable {
        val text = pendingText ?: return@Runnable
        val rate = pendingRate
        pendingText = null
        when {
            ready -> startSystem(text, rate)
            else -> fallBackToNetwork(
                text,
                rate,
                failure ?: "未检测到可用的系统语音引擎", // i18n: keep
            )
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            stop()
        }
    }

    init {
        network.onChapterEnd = {
            if (channel == 2) narrationFinished()
        }
        network.onError = { reason ->
            if (channel == 2 && speaking) {
                speaking = false
                channel = 0
                endCallbackArmed = false
                toast("网络朗读失败：$reason") // i18n: keep
            }
        }
    }

    fun attach(engine: TextToSpeech) {
        this.engine = engine
    }

    fun shutdown() {
        stop()
        network.shutdown()
    }

    // --- system engine lifecycle --------------------------------------

    fun onInit(status: Int) {
        val eng = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            failure = "朗读引擎初始化失败，请确认系统语音引擎可用" // i18n: keep
        } else {
            val engines = runCatching { eng.engines }.getOrNull().orEmpty()
            if (engines.isEmpty()) {
                failure = "未安装语音引擎，无法朗读（请到系统设置-文字转语音中启用）" // i18n: keep
            } else {
                runCatching {
                    eng.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                if (applyChinese(eng)) {
                    ready = true
                } else {
                    failure = "语音引擎缺少中文语音，请到系统设置-文字转语音中下载中文语音包" // i18n: keep
                }
            }
        }
        // Resolve a request that was queued while initialising.
        val pending = pendingText
        if (pending != null) {
            pendingText = null
            mainHandler.removeCallbacks(initWatchdogRunnable)
            when {
                ready -> startSystem(pending, pendingRate)
                else -> fallBackToNetwork(
                    pending,
                    pendingRate,
                    failure ?: "未检测到可用的系统语音引擎", // i18n: keep
                )
            }
        }
    }

    private fun applyChinese(eng: TextToSpeech): Boolean {
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE)) {
            val result = runCatching { eng.setLanguage(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                return true
            }
        }
        return false
    }

    // --- public control -------------------------------------------------

    fun toggle(fullText: String?, rate: Float, channelMode: Int) {
        if (speaking) {
            stop()
        } else {
            startNarration(fullText, rate, channelMode)
        }
    }

    fun startNarration(text: String?, rate: Float, channelMode: Int) {
        val content = text?.takeIf { it.isNotBlank() }
        if (content == null) {
            toast("正文还没有加载好") // i18n: keep
            return
        }
        lastText = content
        lastRate = rate
        when (channelMode) {
            1 -> startSystem(content, rate)
            2 -> startNetwork(content, rate)
            else -> when {
                ready -> startSystem(content, rate)
                failure != null -> fallBackToNetwork(content, rate, failure!!)
                else -> {
                    // System engine still initialising: queue the
                    // request; onInit resolves it if it lands in time,
                    // the watchdog takes the network channel (with
                    // the reason announced) if it never does.
                    pendingText = content
                    pendingRate = rate
                    mainHandler.removeCallbacks(initWatchdogRunnable)
                    mainHandler.postDelayed(initWatchdogRunnable, INIT_WATCHDOG_MS)
                    toast("朗读引擎初始化中，马上开始") // i18n: keep
                }
            }
        }
    }

    fun stop() {
        endCallbackArmed = false
        pendingText = null
        mainHandler.removeCallbacks(initWatchdogRunnable)
        runCatching { engine?.stop() }
        network.stop()
        channel = 0
        speaking = false
        abandonFocus()
    }

    fun applyRate(rate: Float) {
        lastRate = rate
        pendingRate = rate
        if (ready) runCatching { engine?.setSpeechRate(rate) }
        network.setRate(rate)
    }

    // --- channels -------------------------------------------------------

    private fun startSystem(content: String, rate: Float) {
        val eng = engine
        if (eng == null || !ready) {
            val reason = failure
            if (reason != null) {
                // Known-unusable system channel (init failed, no
                // engine installed, no Chinese voice): announce why
                // and hand over to the network channel — a tap on
                // 朗读 must never end in unexplained silence, not
                // even on the forced-system channel.
                fallBackToNetwork(content, rate, reason)
            } else {
                // Still initialising: queue the request. onInit
                // resolves it when it lands; the watchdog resolves
                // it if onInit never lands at all.
                pendingText = content
                pendingRate = rate
                mainHandler.removeCallbacks(initWatchdogRunnable)
                mainHandler.postDelayed(initWatchdogRunnable, INIT_WATCHDOG_MS)
                toast("朗读引擎初始化中，马上开始") // i18n: keep
            }
            return
        }
        val chunks = chunkForTts(content, 2800)
        if (chunks.isEmpty()) return
        channel = 1
        requestFocus()
        eng.setSpeechRate(rate)
        lastUtteranceId = "novel-tts-" + (chunks.size - 1)
        endCallbackArmed = true
        speaking = true
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = runCatching {
                eng.speak(chunk, mode, null, "novel-tts-" + index)
            }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.ERROR) {
                speaking = false
                channel = 0
                endCallbackArmed = false
                abandonFocus()
                if (index == 0) {
                    fallBackToNetwork(content, rate, "系统朗读启动失败") // i18n: keep
                }
                return
            }
        }
    }

    /**
     * The system channel cannot speak: say why (long toast, so the
     * reason can actually be read) and continue the same chapter on
     * the network channel instead of going silent.
     */
    private fun fallBackToNetwork(content: String, rate: Float, reason: String) {
        Toast.makeText(appContext, "$reason，已改用网络朗读", Toast.LENGTH_LONG).show() // i18n: keep
        startNetwork(content, rate)
    }

    private fun startNetwork(content: String, rate: Float) {
        channel = 2
        endCallbackArmed = true
        speaking = true
        network.speakChapter(content, rate)
    }

    private fun narrationFinished() {
        speaking = false
        channel = 0
        abandonFocus()
        if (endCallbackArmed) {
            endCallbackArmed = false
            onNarrationEnd?.invoke()
        }
    }

    fun onUtteranceDone(utteranceId: String?) {
        if (utteranceId != null && utteranceId == lastUtteranceId && channel == 1) {
            narrationFinished()
        }
    }

    fun onUtteranceError(utteranceId: String?) {
        if (!speaking || channel != 1) return
        val content = lastText
        val rate = lastRate
        speaking = false
        channel = 0
        endCallbackArmed = false
        abandonFocus()
        runCatching { engine?.stop() }
        // The system engine died mid-chapter: on the auto AND the
        // forced-system channel alike, hand the chapter to the
        // network channel and announce it — an utterance error must
        // never surface as unexplained silence.
        if (content != null) {
            fallBackToNetwork(content, rate, "系统朗读出错") // i18n: keep
        } else {
            toast("系统朗读出错，已停止") // i18n: keep
        }
    }

    // --- audio focus (system channel) -----------------------------------

    private fun requestFocus() {
        val manager = audioManager
            ?: (appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.also { audioManager = it }
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            focusHeld = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            focusHeld = manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.abandonAudioFocus(focusListener) }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** How long a queued narration request waits for onInit before the network channel takes over. */
        const val INIT_WATCHDOG_MS = 3000L
    }
}

/** Splits chapter text into utterance-sized chunks on paragraph bounds. */
private fun chunkForTts(text: String, maxLen: Int): List<String> {
    val chunks = ArrayList<String>()
    val sb = StringBuilder()
    fun flush() {
        if (sb.isNotEmpty()) {
            chunks.add(sb.toString())
            sb.clear()
        }
    }
    for (paragraph in text.split("\n")) {
        val p = paragraph.trim()
        if (p.isEmpty()) continue
        if (p.length > maxLen) {
            flush()
            var i = 0
            while (i < p.length) {
                chunks.add(p.substring(i, minOf(i + maxLen, p.length)))
                i += maxLen
            }
            continue
        }
        if (sb.length + p.length + 1 > maxLen) flush()
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(p)
    }
    flush()
    return chunks
}

@Composable
private fun rememberTtsController(context: Context): TtsController {
    val appContext = context.applicationContext
    val controller = remember { TtsController(appContext) }
    DisposableEffect(appContext) {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(appContext) { status ->
            controller.onInit(status)
        }
        controller.attach(engine)
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { controller.onUtteranceDone(utteranceId) }
            @Deprecated("deprecated")
            override fun onError(utteranceId: String?) { controller.onUtteranceError(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { controller.onUtteranceError(utteranceId) }
        })
        onDispose {
            controller.shutdown()
            runCatching { engine.shutdown() }
        }
    }
    return controller
}
