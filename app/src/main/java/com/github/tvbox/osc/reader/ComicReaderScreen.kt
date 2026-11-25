package com.github.tvbox.osc.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.isLandscapeOrientation
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun ComicReaderScreen(
    session: ReaderSession,
    onBack: () -> Unit,
    // 1.93：路由开关回调（当前话，目标路由）。底栏里的开关拨动时
    // 由 activity 接手：存偏好后用当前话重进另一个阅读器。
    onSwitchReader: ((Int, Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val episodes = session.episodes
    val totalChapters = maxOf(1, episodes.size)

    var settings by remember { mutableStateOf(ReaderStore.comicSettings()) }
    var chapterIndex by remember {
        mutableIntStateOf(
            session.startIndex.coerceIn(0, totalChapters - 1)
        )
    }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    // 2.54 修：记当前这批图属于哪一章。往回跨章的落点只认本章的图——
    // 原来落点 effect 只看 images 非空，换章那一下 images 还是旧章的，
    // 就提前落在旧章末张、把旗子清了，等于白落一次，新章图到了也不再落。
    var imagesChapter by remember { mutableIntStateOf(-1) }
    // 2.54 修：上下模式往回落点的时间戳。往回落到上一章末张后，底部自动跨章
    // 会把人又送回下一章（落点就在底上）——3 秒内不让底部跨章触发，够人往上翻走。
    var backLandAtMs by remember { mutableStateOf(0L) }
    // 2.54 修：本章里人往下翻过没有。上下模式到顶跨章的开关——刚开章就在顶上，
    // 没往下翻过就不跨，不然一开章就跳回上一章。
    var scrolledDownInChapter by remember { mutableStateOf(false) }
    var imageHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // The reader opens immersive: clean pages, no bars; a long-press
    // is the only way to summon them.
    var barsVisible by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    var currentImage by remember {
        mutableIntStateOf(ReaderStore.progress(session.bookKey)?.imageIndex ?: 0)
    }
    var showOriginal by remember { mutableStateOf(false) }
    var loadTick by remember { mutableIntStateOf(0) }
    var zoomedPage by remember { mutableStateOf<Int?>(null) }
    // Bumped whenever the bars are interacted with, so the idle
    // auto-hide below only fires after real inactivity.
    var barsTouchTick by remember { mutableIntStateOf(0) }

    fun toggleBars() {
        barsVisible = !barsVisible
        barsTouchTick++
    }

    // Idle auto-hide, matching the novel reader's interaction rules:
    // bars summoned by a tap disappear again after a few idle
    // seconds; the catalog suspends the timer.
    LaunchedEffect(barsVisible, barsTouchTick, showCatalog) {
        if (!barsVisible || showCatalog) return@LaunchedEffect
        delay(4000)
        barsVisible = false
    }

    val accent = MaterialTheme.colorScheme.primary
    val chapterName = episodes.getOrNull(chapterIndex)?.name ?: ""
    val listState = rememberLazyListState()

    LaunchedEffect(chapterIndex, loadTick) {
        loading = true
        loadError = null
        // The previous chapter's images stay on screen while the
        // next chapter loads and swap atomically when it lands
        // (logtest25, same rule as the novel reader): clearing them
        // up front made every chapter crossing flash the full-screen
        // spinner even for prefetched chapters.
        showOriginal = false
        try {
            val content = ReaderLoader.loadChapter(session, chapterIndex)
            val comic = content as? ReaderContent.Comic
            if (comic == null || comic.images.isEmpty()) {
                loadError = "本话没有图片" // i18n: keep
            } else {
                imageHeaders = comic.headers
                images = comic.images.map { normalizeImageUrl(it, comic.headers) }
                imagesChapter = chapterIndex
                val restore = ReaderStore.progress(session.bookKey)
                currentImage = if (restore != null && restore.chapterIndex == chapterIndex) {
                    restore.imageIndex.coerceIn(0, comic.images.size - 1)
                } else {
                    0
                }
                if (settings.verticalMode && currentImage > 0) {
                    scope.launch { listState.scrollToItem(currentImage) }
                }
            }
        } catch (th: Throwable) {
            loadError = th.message ?: "加载失败" // i18n: keep
        } finally {
            loading = false
        }
    }

    LaunchedEffect(chapterIndex, currentImage) {
        ReaderStore.saveProgress(
            session.bookKey,
            ReaderProgress(chapterIndex, 0, currentImage.coerceAtLeast(0)),
        )
    }

    // Warm the network/decode cache for neighbouring pages so page turns
    // and fast scrolling do not cold-load every image from scratch.
    LaunchedEffect(currentImage, images, settings.verticalMode) {
        if (images.size < 2) return@LaunchedEffect
        val loader = ReaderImageLoader.get(context, imageHeaders)
        val last = images.size - 1
        val targets = if (settings.verticalMode) {
            (currentImage + 1)..minOf(last, currentImage + 3)
        } else {
            maxOf(0, currentImage - 1)..minOf(last, currentImage + 2)
        }
        for (i in targets) {
            loader.enqueue(comicImageRequest(context, images[i], original = false))
        }
    }

    // Vertical mode: the first fully visible image drives the progress.
    val visibleImage by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()
            first?.index ?: 0
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling && settings.verticalMode) {
                // A finger scroll hides the bars (both modes follow
                // the same rule as the novel reader).
                barsVisible = false
                currentImage = visibleImage
            }
        }
    }

    // 2.54 修：本章里人往下翻过没有（供上下模式到顶跨章用）。换章清零；
    // 第一张可见图不是第 0 张了，就是往下翻过。
    LaunchedEffect(chapterIndex) { scrolledDownInChapter = false }
    LaunchedEffect(visibleImage) { if (visibleImage > 0) scrolledDownInChapter = true }

    // Chapter that should land on its LAST image after a backwards
    // chapter crossing; -1 = land normally.
    var landLastImageForChapter by remember { mutableIntStateOf(-1) }

    fun goChapter(target: Int) {
        if (target in 0 until totalChapters) {
            zoomedPage = null
            chapterIndex = target
            currentImage = 0
        }
    }

    /** One image step in any turn mode (buttons, tap zones). */
    fun stepImage(delta: Int) {
        if (images.isEmpty()) return
        val target = (currentImage + delta).coerceIn(0, images.size - 1)
        if (target == currentImage) return
        currentImage = target
        if (settings.pageTurnMode == ReaderPageTurnModes.VERTICAL) {
            scope.launch { listState.animateScrollToItem(target) }
        }
    }

    /** 1.95：滑杆跳图：上下连续即时跳，左右单页跟页走（页器跟 currentImage）。 */
    fun jumpToImage(target: Int) {
        if (images.isEmpty()) return
        val t = target.coerceIn(0, images.size - 1)
        if (t == currentImage) return
        currentImage = t
        if (settings.pageTurnMode == ReaderPageTurnModes.VERTICAL) {
            scope.launch { listState.scrollToItem(t) }
        }
    }

    /** Single-tap zones: left third = previous image, right third = next. */
    fun tapZoneStep(fraction: Float) {
        when {
            fraction < 1f / 3f -> stepImage(-1)
            fraction > 2f / 3f -> stepImage(1)
        }
    }

    /** Drag-driven chapter crossing (pager edge swipe / curl edge drag). */
    fun crossChapter(forward: Boolean) {
        if (forward) {
            if (chapterIndex < totalChapters - 1) goChapter(chapterIndex + 1)
        } else if (chapterIndex > 0) {
            landLastImageForChapter = chapterIndex - 1
            goChapter(chapterIndex - 1)
        }
    }

    // Backwards crossing lands on the previous chapter's last image
    // once that chapter's images are in. The imagesChapter guard is
    // the point: without it the effect fires on the OLD chapter's
    // images the moment chapterIndex changes (they are still on
    // screen), lands on the old last image, clears the flag, and the
    // new chapter's images arrive too late to land again.
    LaunchedEffect(images, landLastImageForChapter, chapterIndex, imagesChapter) {
        if (landLastImageForChapter == chapterIndex && imagesChapter == chapterIndex && images.isNotEmpty()) {
            currentImage = images.size - 1
            landLastImageForChapter = -1
            if (settings.pageTurnMode == ReaderPageTurnModes.VERTICAL) {
                // 2.54 修：记下落点时间，底部自动跨章 3 秒内不触发，不然刚落到底上
                // 就被送回下一章，来回跳。
                backLandAtMs = System.currentTimeMillis()
                scope.launch { listState.scrollToItem(images.size - 1) }
            }
        }
    }

    // Vertical mode (logtest25): settling at the very bottom of the
    // chapter's image list crosses into the next chapter on its own —
    // finger scrolling used to stop dead at the chapter end and only
    // the chapter buttons moved on. The old chapter's images remain
    // on screen until the new ones land, so the crossing shows no
    // spinner and no blank frame.
    LaunchedEffect(listState, chapterIndex, images.size, settings.pageTurnMode) {
        if (!settings.verticalMode) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last != null && layout.totalItemsCount > 0 &&
                last.index >= layout.totalItemsCount - 1 &&
                last.offset + last.size <= layout.viewportEndOffset + 2 &&
                !listState.isScrollInProgress
        }.collectLatest { atBottom ->
            if (atBottom && images.isNotEmpty() && chapterIndex < totalChapters - 1) {
                delay(350)
                // 2.54 修：往回落点 3 秒内不往前跨——刚落到上一章末张，人就在底上，
                // 不挡就立刻被送回下一章，来回跳。
                if (System.currentTimeMillis() - backLandAtMs < 3000L) return@collectLatest
                goChapter(chapterIndex + 1)
            }
        }
    }

    // 2.54 修：上下模式到顶也要跨章——原来只有到底才自动进下一章，到顶往上翻
    // 是死的，只能按上一章按钮。跟到底跨章同款：停稳在顶上 350ms 才跨，落到
    // 上一章最后一张；scrolledDownInChapter 挡着刚打开那一下（开章就在顶上，
    // 没往下翻过就不跨，不然一开章就跳回上一章）。
    LaunchedEffect(listState, chapterIndex, images.size, settings.pageTurnMode) {
        if (!settings.verticalMode) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()
            first != null && first.index == 0 && first.offset >= 0 &&
                !listState.isScrollInProgress && scrolledDownInChapter
        }.collectLatest { atTop ->
            if (atTop && images.isNotEmpty() && chapterIndex > 0) {
                delay(350)
                val layout = listState.layoutInfo
                val first = layout.visibleItemsInfo.firstOrNull()
                if (first != null && first.index == 0 && first.offset >= 0 &&
                    !listState.isScrollInProgress && scrolledDownInChapter
                ) {
                    crossChapter(false)
                }
            }
        }
    }

    // Landscape is the TV form: the reader root takes focus so a
    // remote can turn pages with the dpad and open the bars with OK.
    // Portrait (touch) is untouched. In-chapter steps go through
    // stepImage and chapter edges through crossChapter — the same
    // actions the tap zones and bottom bar use — so the landing
    // rules (last image backwards, first image forwards) match.
    val landscape = isLandscapeOrientation()
    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(landscape) {
        if (landscape) runCatching { rootFocusRequester.requestFocus() }
    }

    fun comicTurn(forward: Boolean) {
        if (settings.verticalMode) {
            scope.launch {
                val viewport = listState.layoutInfo.viewportSize.height.toFloat()
                if (viewport > 0f) {
                    listState.animateScrollBy(if (forward) viewport * 0.9f else -viewport * 0.9f)
                }
            }
        } else if (forward) {
            if (currentImage < images.size - 1) stepImage(1) else crossChapter(true)
        } else {
            if (currentImage > 0) stepImage(-1) else crossChapter(false)
        }
        barsVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                                        comicTurn(true)
                                        true
                                    }
                                    Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> {
                                        comicTurn(false)
                                        true
                                    }
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                        toggleBars()
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
        // Column with it (same change as the novel reader): the
        // body always owns the full screen, so summoning the bars
        // can no longer resize it — in curl mode that resize used
        // to rebuild the whole bitmap window under the reader's
        // fingers. The bars still glide in and out over the page.
            Box(modifier = Modifier.fillMaxSize()) {
                ReaderChapterFade(chapterKey = chapterIndex, modifier = Modifier.fillMaxSize(), instant = false) {
                when {
                    loading && images.isEmpty() -> {
                        CircularProgressIndicator(
                            color = accent,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    loadError != null && images.isEmpty() -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        ) {
                            Text(text = loadError!!)
                            TextButton(onClick = { loadTick += 1 }) {
                                Text(text = "重试", color = accent) // i18n: keep
                            }
                        }
                    }
                    images.isNotEmpty() -> {
                        // Exactly one turn path is in the tree at a
                        // time: a mode switch disposes the previous
                        // body outright — its layers, bitmap windows
                        // and gesture handlers leave with it, so no
                        // residue of one path can ever paint or
                        // gesture inside another.
                        when (settings.pageTurnMode) {
                        ReaderPageTurnModes.VERTICAL -> {
                            LazyColumn(
                                state = listState,
                                // 1.95：图间距 6dp，与 Web 漫画的 6px 对齐（之前是紧贴）。
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                itemsIndexed(images) { index, url ->
                                    ComicImage(
                                        url = url,
                                        headers = imageHeaders,
                                        original = false,
                                        contentScale = ContentScale.FillWidth,
                                        fillHeight = false,
                                        modifier = Modifier.fillMaxWidth(),
                                        onTap = {},
                                        onLongPress = { toggleBars() },
                                    )
                                }
                            }
                        }
                        ReaderPageTurnModes.HORIZONTAL -> {
                            val pagerState = rememberPagerState(initialPage = 0) { images.size }
                            LaunchedEffect(currentImage, images) {
                                if (images.isNotEmpty() && pagerState.currentPage != currentImage) {
                                    pagerState.scrollToPage(currentImage.coerceIn(0, images.size - 1))
                                }
                            }
                            LaunchedEffect(pagerState) {
                                snapshotFlow { pagerState.currentPage }.collectLatest { page ->
                                    currentImage = page
                                }
                            }
                            // A finger page turn hides the bars, same
                            // rule as vertical scrolling.
                            LaunchedEffect(pagerState) {
                                snapshotFlow { pagerState.isScrollInProgress }.collectLatest { scrolling ->
                                    if (scrolling) barsVisible = false
                                }
                            }
                            // Chapter-edge crossing, same rule as the
                            // novel pager: a committed drag (a quarter
                            // of the width) on the chapter's first/last
                            // image is observed and reported as a
                            // chapter step; the start page is latched
                            // at press time so a normal turn INTO the
                            // edge image cannot chain into a jump.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(images.size) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(
                                                requireUnconsumed = false,
                                            )
                                            val startPage = pagerState.currentPage
                                            var lastX = down.position.x
                                            var up = false
                                            while (!up) {
                                                val event = awaitPointerEvent()
                                                val pressed =
                                                    event.changes.filter { it.pressed }
                                                if (pressed.isEmpty()) {
                                                    up = true
                                                } else {
                                                    lastX = pressed.first().position.x
                                                }
                                            }
                                            val dxTotal = lastX - down.position.x
                                            when {
                                                startPage >= images.size - 1 &&
                                                    dxTotal < -size.width * 0.25f ->
                                                    crossChapter(true)
                                                startPage <= 0 &&
                                                    dxTotal > size.width * 0.25f ->
                                                    crossChapter(false)
                                            }
                                        }
                                    },
                            ) {
                                HorizontalPager(
                                    state = pagerState,
                                    userScrollEnabled = zoomedPage == null,
                                    modifier = Modifier.fillMaxSize(),
                                ) { page ->
                                    ComicImage(
                                        url = images[page],
                                        headers = imageHeaders,
                                        original = showOriginal,
                                        contentScale = ContentScale.Fit,
                                        fillHeight = true,
                                        modifier = Modifier.fillMaxSize(),
                                        onTap = { fraction -> tapZoneStep(fraction) },
                                        onLongPress = { toggleBars() },
                                        onZoomChanged = { zoomed ->
                                            zoomedPage = if (zoomed) {
                                                page
                                            } else if (zoomedPage == page) {
                                                null
                                            } else {
                                                zoomedPage
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        else -> {
                            // Paper-curl turns: page bitmaps are
                            // composed off-screen (image fitted into the
                            // page frame over the page background) and
                            // fed to the shared turn surface.
                            val pageBgArgb = MaterialTheme.colorScheme.background.toArgb()
                            val snapshotProvider: suspend (Int, Int, Int) -> Bitmap? =
                                { page, w, h ->
                                    val url = images.getOrNull(page)
                                    if (url == null || w <= 0 || h <= 0) {
                                        null
                                    } else {
                                        try {
                                            val loader = ReaderImageLoader.get(
                                                context, imageHeaders,
                                            )
                                            val baseRequest = comicImageRequest(
                                                context, url, showOriginal,
                                            )
                                            val request = if (showOriginal) {
                                                // Reuse the bitmap the
                                                // display path already
                                                // decoded (logtest26): an
                                                // original-mode display
                                                // request carries no
                                                // explicit size, so
                                                // AsyncImage resolves it
                                                // to the page's layout
                                                // size — this window's
                                                // size. Executing the
                                                // un-sized request here
                                                // keyed the shared memory
                                                // cache differently and
                                                // decoded the untouched
                                                // original a second time
                                                // (or missed out to the
                                                // network). Pinning the
                                                // same size makes this a
                                                // cache hit on the
                                                // already-decoded image.
                                                baseRequest.newBuilder()
                                                    .size(Size(w, h)).build()
                                            } else {
                                                baseRequest
                                            }
                                            val result = loader.execute(request)
                                            val src = (result as? SuccessResult)
                                                ?.image?.toBitmap()
                                            if (src == null) {
                                                null
                                            } else {
                                                val frame = Bitmap.createBitmap(
                                                    w, h, Bitmap.Config.ARGB_8888,
                                                )
                                                val canvas = android.graphics.Canvas(frame)
                                                canvas.drawColor(pageBgArgb)
                                                val scale = min(
                                                    w.toFloat() / src.width,
                                                    h.toFloat() / src.height,
                                                )
                                                val dw = src.width * scale
                                                val dh = src.height * scale
                                                val dst = RectF(
                                                    (w - dw) / 2f, (h - dh) / 2f,
                                                    (w + dw) / 2f, (h + dh) / 2f,
                                                )
                                                canvas.drawBitmap(
                                                    src, null, dst,
                                                    Paint(Paint.FILTER_BITMAP_FLAG),
                                                )
                                                frame
                                            }
                                        } catch (th: Throwable) {
                                            null
                                        }
                                    }
                                }
                            PageTurnSurface(
                                contentKey = listOf(images, showOriginal),
                                pageCount = images.size,
                                pageIndex = currentImage,
                                backColorArgb = pageBgArgb,
                                instantTurns = false,
                                handleTaps = true,
                                zoomEnabled = true,
                                // Comic pages are network image
                                // loads: a drag gates on the current
                                // page alone and the target may land
                                // late (see PageTurnSurface) — gating
                                // on both bitmaps left drags dead
                                // while the page sat on screen.
                                targetMayLandLate = true,
                                snapshotProvider = snapshotProvider,
                                onPageSettled = { currentImage = it },
                                onDragStart = { barsVisible = false },
                                onTap = { fraction -> tapZoneStep(fraction) },
                                onDoubleTap = {},
                                onLongPress = { toggleBars() },
                                onChapterEdge = { forward -> crossChapter(forward) },
                                fallback = { page ->
                                    // Shown only until the first page
                                    // bitmap lands; taps stay with the
                                    // turn surface, so no double toggle.
                                    ComicImage(
                                        url = images.getOrElse(page) { "" },
                                        headers = imageHeaders,
                                        original = showOriginal,
                                        contentScale = ContentScale.Fit,
                                        fillHeight = true,
                                        modifier = Modifier.fillMaxSize(),
                                        onTap = {},
                                    )
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        }
                    }
                }
                }
            }

            // Top bar, composed AFTER the body (logtest25, same fix
            // as the novel reader): later Box children draw over
            // earlier ones and the bodies paint opaque full-screen
            // pages, so the old order left the summoned top bar
            // painted UNDER the page — a long-press appeared to
            // raise only the bottom bar. statusBarsPadding keeps its
            // content clear of a transiently visible system bar.
            AnimatedVisibility(
                visible = barsVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(160)),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().statusBarsPadding()
                            .padding(horizontal = 4.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") // i18n: keep
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            Text(
                                text = chapterName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = barsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(200)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        // 1.95：图进度滑杆，拖动跳图。
                        if (images.size > 1) {
                            ReaderSlider(
                                value = currentImage.toFloat(),
                                onValueChange = { jumpToImage(it.roundToInt()) },
                                valueRange = 0f..(images.size - 1).toFloat(),
                                steps = if (images.size > 2) images.size - 2 else 0,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(onClick = { goChapter(chapterIndex - 1) }, enabled = chapterIndex > 0) {
                                Text(text = "上一话") // i18n: keep
                            }
                            Text(
                                text = "第 ${chapterIndex + 1} / $totalChapters 话", // i18n: keep
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                            )
                            TextButton(
                                onClick = { goChapter(chapterIndex + 1) },
                                enabled = chapterIndex < totalChapters - 1,
                            ) {
                                Text(text = "下一话") // i18n: keep
                            }
                        }
                        // The page-turn row: previous page | the
                        // turn capsules | next page, in the same
                        // order as the novel reader — except comics
                        // carry FOUR pieces, not five: there is no
                        // paper-curl capsule here (a stored CURL is
                        // mapped to HORIZONTAL in ReaderStore, so
                        // the curl body below is never derived).
                        // This row is the ONE place the turn mode
                        // is chosen. The four pieces split the row
                        // into four EQUAL weighted slots — the
                        // SpaceBetween + nested-chip-row layout it
                        // replaces had the same overflow as the
                        // novel row: the group's intrinsic width
                        // pushed the last piece off the screen edge.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(
                                onClick = { stepImage(-1) },
                                enabled = currentImage > 0,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = "上一页", maxLines = 1) // i18n: keep
                            }
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                ReaderModeChip(
                                    label = "上下翻页", // i18n: keep
                                    selected = settings.pageTurnMode ==
                                        ReaderPageTurnModes.VERTICAL,
                                    onClick = {
                                        settings = settings.copy(
                                            pageTurnMode = ReaderPageTurnModes.VERTICAL,
                                        )
                                        ReaderStore.saveComicSettings(settings)
                                    },
                                )
                            }
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                ReaderModeChip(
                                    label = "左右翻页", // i18n: keep
                                    selected = settings.pageTurnMode ==
                                        ReaderPageTurnModes.HORIZONTAL,
                                    onClick = {
                                        settings = settings.copy(
                                            pageTurnMode = ReaderPageTurnModes.HORIZONTAL,
                                        )
                                        ReaderStore.saveComicSettings(settings)
                                    },
                                )
                            }
                            TextButton(
                                onClick = { stepImage(1) },
                                enabled = currentImage < images.size - 1,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = "下一页", maxLines = 1) // i18n: keep
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (images.isEmpty()) "" else "图 ${currentImage + 1} / ${images.size}", // i18n: keep
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row {
                                TextButton(onClick = { showCatalog = true }) {
                                    Text(text = "目录") // i18n: keep
                                }
                                if (settings.pageTurnMode != ReaderPageTurnModes.VERTICAL) {
                                    TextButton(onClick = { showOriginal = !showOriginal }) {
                                        Text(
                                            text = if (showOriginal) "原图开" else "看原图", // i18n: keep
                                            color = if (showOriginal) accent else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                        // 1.93：阅读器路由开关。自带漫画底栏里它是关的，拨开就切回 Web。
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
                                onCheckedChange = { enabled ->
                                    onSwitchReader?.invoke(chapterIndex, enabled)
                                },
                            )
                        }
                    }
                }
            }
    }

    if (showCatalog) {
        ReaderCatalogDialog(
            title = session.title,
            episodes = episodes,
            currentIndex = chapterIndex,
            accent = accent,
            onPick = { index ->
                showCatalog = false
                goChapter(index)
            },
            onDismiss = { showCatalog = false },
        )
    }

}

private fun comicImageRequest(context: Context, url: String, original: Boolean): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .apply {
            if (!original) {
                size(Size(1440, 4096))
            }
        }
        .build()

/**
 * Transform gestures that stay out of the parent scroller's way. Pointer
 * changes are consumed only once the gesture is a pinch (two pressed
 * pointers / a zoom change) or the image is already zoomed; a plain
 * one-finger drag at 100% is never consumed here. The previous code ran
 * detectTransformGestures unconditionally on every page, which starved
 * both the HorizontalPager and the LazyColumn of every drag starting on
 * an image — i.e. the whole screen — so pages could not turn at all.
 */
private fun Modifier.readerTransformGestures(
    key: Any,
    isZoomed: () -> Boolean,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var transforming = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.none { it.isConsumed }) {
                val pressedCount = event.changes.count { it.pressed }
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!transforming && (pressedCount >= 2 || zoomChange != 1f || isZoomed())) {
                    transforming = true
                }
                if (transforming) {
                    onTransform(panChange, zoomChange)
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * One comic page. Images go through the reader's Coil loader (spider
 * headers injected per chapter). Non-original mode caps the decode width
 * so long strips do not blow up memory; original mode requests the
 * untouched bitmap. Pinch zoom + pan work through
 * [readerTransformGestures]; double-tap toggles 100% <-> 250%.
 */
@Composable
private fun ComicImage(
    url: String,
    headers: Map<String, String>,
    original: Boolean,
    contentScale: ContentScale,
    fillHeight: Boolean,
    modifier: Modifier,
    /** Single tap; the argument is the tap's x as a fraction of the width. */
    onTap: (Float) -> Unit,
    onLongPress: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }
    LaunchedEffect(scale > 1f) { onZoomChanged(scale > 1f) }
    val request = remember(url, original) { comicImageRequest(context, url, original) }
    Box(modifier = modifier) {
        AsyncImage(
            model = request,
            imageLoader = ReaderImageLoader.get(context, headers),
            contentDescription = null,
            contentScale = contentScale,
            modifier = (if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
                // Same single gesture arbiter as everywhere else in
                // the readers: a non-consuming observer, so this
                // image can never starve the pager / list / curl
                // surface that owns the drags around it, and the
                // tap / long-press / double-tap priority is the one
                // shared rule instead of detectTapGestures' own.
                .readerTapArbiter(
                    key = url,
                    onTap = onTap,
                    onLongPress = onLongPress,
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                )
                .readerTransformGestures(url, isZoomed = { scale > 1f }) { pan, zoom ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    if (newScale <= 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
        )
    }
}

private fun normalizeImageUrl(raw: String, headers: Map<String, String>): String {
    val value = raw.trim()
    if (value.startsWith("pics://")) return value.substring("pics://".length)
    if (value.startsWith("//")) return "https:$value"
    if (!value.startsWith("http") && !value.startsWith("data:")) {
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        if (!referer.isNullOrEmpty()) {
            return runCatching { java.net.URL(URLOrNull(referer), value).toString() }.getOrDefault(value)
        }
    }
    return value
}

private fun URLOrNull(value: String): java.net.URL? = runCatching { java.net.URL(value) }.getOrNull()
