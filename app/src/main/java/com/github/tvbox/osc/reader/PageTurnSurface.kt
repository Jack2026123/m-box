package com.github.tvbox.osc.reader

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private enum class TurnPhase { IDLE, DRAG, ANIM }

/**
 * Full-page surface for the paper-curl turn mode ONLY. It is composed
 * exclusively while the reader's single page-turn mode is CURL: the
 * vertical path is a plain scroll body and the horizontal path is the
 * pager, neither of which ever composes this surface, so no curl
 * layer, bitmap window or gesture state can leak into them — and
 * leaving CURL mode removes this surface (and its window) from the
 * tree outright.
 *
 * Both hosts (novel text pages, comic image pages) feed it through
 * [snapshotProvider]: a page index goes in, a surface-sized bitmap of
 * that page comes out. The surface owns a three-bitmap window
 * (previous / current / next) around the settled page; bitmaps are
 * built only while this surface is composed, reused across window
 * shifts, and recycled as soon as they fall out of the window or the
 * surface leaves composition.
 *
 * Gesture split (mirrors the pager's coexistence with the tap
 * overlays): a press is observed but nothing is consumed until the
 * pointer travels past the touch slop and a direction is classified,
 * so plain taps and double-taps keep flowing to the host's tap
 * handlers — the novel host's outer detectTapGestures box, or this
 * surface itself when [handleTaps] is set (comic). Past the slop the
 * drag is a turn: the sheet follows the finger (curl geometry in
 * [PageCurlRenderer]).
 *
 * Release classification: the turn completes when the drag has
 * covered at least a quarter of the width OR the release carries a
 * clear fling in the turn direction (>= 400 dp/s); anything less
 * springs back. Legado's own delegate has no distance threshold at
 * all — its ACTION_UP (HorizontalPageDelegate.q() calling
 * SimulationPageDelegate.l()) decides cancel purely from the
 * isCancel flag, which the MOVE step sets from whether the latest
 * movement heads back toward the start side. The quarter-width /
 * fling rule is the deliberate, more conservative adaptation the
 * product settled on after small drags failed to turn the page.
 * Buttons and other external page steps run a programmatic turn.
 * [instantTurns] steps cut instantly instead; hosts decide per path
 * (the novel curl path passes false so its auto-read performs real
 * programmatic curls at the reading pace).
 *
 * Chapter edges: with no page beyond the current one the sheet
 * never moves (Legado's missing-target drag), but the drag is still
 * classified: past the same quarter-width / fling bar on release it
 * reports through [onChapterEdge] so the host can cross into the
 * neighbouring chapter.
 *
 * A content (chapter) switch cancels every in-flight job of this
 * surface: a finish animation or window build belonging to the old
 * chapter must never land, paint or report inside the new one.
 *
 * Long-press: while a press stays near-still (never classified as a
 * drag) for the long-press timeout, [onLongPress] fires once and
 * the press is swallowed — no tap, no turn follows from it. Any
 * real drag classifies first and cancels the long-press.
 */
@Composable
internal fun PageTurnSurface(
    contentKey: Any,
    pageCount: Int,
    pageIndex: Int,
    backColorArgb: Int,
    instantTurns: Boolean,
    handleTaps: Boolean,
    zoomEnabled: Boolean,
    /**
     * Image hosts (comic) only: page bitmaps are network loads, so a
     * drag gates on the CURRENT page alone and the target may land
     * mid-drag or even after the settle (the host's live fallback
     * covers it). Text hosts (novel) leave this false: their pages
     * render synchronously and the strict both-bitmaps gate plus
     * the atomic window build below stay exactly as they were.
     */
    targetMayLandLate: Boolean = false,
    snapshotProvider: suspend (page: Int, width: Int, height: Int) -> Bitmap?,
    onPageSettled: (Int) -> Unit,
    onDragStart: () -> Unit,
    /** Single tap; the argument is the tap's x as a fraction of the width. */
    onTap: (Float) -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    /** Release past the bar on a chapter-edge drag; true = next chapter. */
    onChapterEdge: (Boolean) -> Unit,
    fallback: @Composable (page: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val renderer = remember { PageCurlRenderer() }

    val providerState = rememberUpdatedState(snapshotProvider)
    val countState = rememberUpdatedState(pageCount)
    val instantState = rememberUpdatedState(instantTurns)
    val settledState = rememberUpdatedState(onPageSettled)
    val dragStartState = rememberUpdatedState(onDragStart)
    val tapState = rememberUpdatedState(onTap)
    val doubleTapState = rememberUpdatedState(onDoubleTap)
    val longPressState = rememberUpdatedState(onLongPress)
    val edgeState = rememberUpdatedState(onChapterEdge)

    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var internalPage by remember(contentKey) {
        mutableIntStateOf(pageIndex.coerceIn(0, maxOf(0, pageCount - 1)))
    }
    var phase by remember { mutableStateOf(TurnPhase.IDLE) }
    var direction by remember { mutableIntStateOf(PageCurlRenderer.NEXT) }
    var fromPage by remember { mutableIntStateOf(0) }
    var toPage by remember { mutableIntStateOf(0) }
    var snaps by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }

    // The live touch point of the turning sheet. This state is READ
    // inside the Canvas draw block below: snapshot reads in the draw
    // scope subscribe the canvas to the value, so every pointer move
    // (and every finish-animation frame, which writes here too)
    // invalidates exactly the draw — the sheet follows the finger
    // frame by frame. The previous build kept the touch point in a
    // plain field of the renderer plus a state nothing in the draw
    // path ever read, so moves never invalidated the canvas at all:
    // the sheet painted only on unrelated recompositions and stayed
    // frozen at the geometry of the classification instant — just
    // past the touch slop, a small corner at the grab edge.
    val touchPoint = remember { mutableStateOf(Offset.Zero) }
    var zoomScale by remember(contentKey) { mutableFloatStateOf(1f) }
    var zoomOffset by remember(contentKey) { mutableStateOf(Offset.Zero) }
    var lastDownPoint by remember { mutableStateOf(Offset.Zero) }

    val animT = remember { Animatable(0f) }
    var animJob by remember { mutableStateOf<Job?>(null) }
    var prepareJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTimeMs by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    // Landing data of the running finish animation, kept outside the
    // animation coroutine so a fresh press can land it synchronously.
    var animCommitFlag by remember { mutableStateOf(false) }
    var animTargetPage by remember { mutableIntStateOf(0) }
    // Last page this surface reported to the host. While the host's
    // echo of it is still in flight, reconcile must not mistake the
    // stale host value for a fresh external step (would bounce back).
    var lastReported by remember { mutableIntStateOf(-1) }

    fun recycleSnaps() {
        snaps.values.forEach { runCatching { it.recycle() } }
        snaps = emptyMap()
    }

    /** (Re)builds the prev/current/next bitmap window at [internalPage]. */
    fun prepareWindow() {
        prepareJob?.cancel()
        prepareJob = scope.launch {
            val w = surfaceSize.width
            val h = surfaceSize.height
            val count = countState.value
            if (w <= 0 || h <= 0 || count <= 0) return@launch
            val center = internalPage.coerceIn(0, count - 1)
            if (targetMayLandLate) {
                // Image hosts: publish EACH bitmap the moment it
                // lands, current page first. Under the atomic build
                // below, one slow neighbour kept the whole window —
                // including the already-built current page —
                // unpublished until all three loads returned, and
                // every drag in that window was gated dead while
                // the screen plainly showed the page (the comic
                // "picture shows, drag does nothing" bug).
                for (p in listOf(center, center + 1, center - 1)) {
                    if (p !in 0 until count) continue
                    val existing = snaps[p]
                    if (existing != null && !existing.isRecycled &&
                        existing.width == w && existing.height == h
                    ) {
                        continue
                    }
                    val bmp = try {
                        providerState.value(p, w, h)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (th: Throwable) {
                        null
                    } ?: continue
                    if (!isActive) {
                        runCatching { bmp.recycle() }
                        return@launch
                    }
                    val merged = HashMap(snaps)
                    val replaced = merged.put(p, bmp)
                    // A sheet in a running turn is never recycled
                    // from under the draw.
                    val turnPages = if (phase != TurnPhase.IDLE) {
                        setOf(fromPage, toPage)
                    } else {
                        emptySet()
                    }
                    if (replaced != null && replaced !== bmp &&
                        p !in turnPages
                    ) {
                        runCatching { replaced.recycle() }
                    }
                    val sweep = merged.entries.iterator()
                    while (sweep.hasNext()) {
                        val e = sweep.next()
                        if (e.key !in (center - 1)..(center + 1) &&
                            e.key !in turnPages
                        ) {
                            runCatching { e.value.recycle() }
                            sweep.remove()
                        }
                    }
                    snaps = merged
                }
                return@launch
            }
            val built = HashMap<Int, Bitmap>()
            try {
                for (p in (center - 1)..(center + 1)) {
                    if (p !in 0 until count) continue
                    val existing = snaps[p]
                    if (existing != null && !existing.isRecycled &&
                        existing.width == w && existing.height == h
                    ) {
                        built[p] = existing
                        continue
                    }
                    val bmp = try {
                        providerState.value(p, w, h)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (th: Throwable) {
                        null
                    }
                    if (bmp != null) built[p] = bmp
                }
            } catch (ce: CancellationException) {
                built.values.forEach {
                    if (snaps.values.none { s -> s === it }) {
                        runCatching { it.recycle() }
                    }
                }
                throw ce
            }
            val old = snaps
            snaps = built
            for ((_, bmp) in old) {
                if (built.values.none { it === bmp }) runCatching { bmp.recycle() }
            }
        }
    }

    /** Finishes the current turn: animate the sheet to its end. */
    fun finishTurn(commit: Boolean, base: Int) {
        phase = TurnPhase.ANIM
        // A commit additionally needs the target bitmap in hand:
        // classification no longer waits for it (a drag must curl
        // even while the neighbour is still being built — the draw
        // falls back to the current page), but a settle onto a page
        // that never arrived must spring back instead of landing on
        // a blank sheet. Image hosts (targetMayLandLate) are the
        // exception: their settled page is covered by the host's
        // live fallback image until its snapshot lands, so the
        // turn settles onto the target index regardless — the page
        // is never lost and never blank.
        val willCommit = commit && (targetMayLandLate || snaps.containsKey(toPage))
        animCommitFlag = willCommit
        animTargetPage = toPage
        val targetPage = toPage
        animJob?.cancel()
        val fromTouch = touchPoint.value
        val target = renderer.finishTarget(direction, !willCommit)
        val toTouch = Offset(target.first, target.second)
        val durationMs = renderer.finishDurationMs(
            toTouch.x - fromTouch.x, toTouch.y - fromTouch.y, base,
        ).coerceAtLeast(1)
        animJob = scope.launch {
            animT.snapTo(0f)
            // Eased, not linear: the eased hand-off is what lets the
            // sheet leave the finger without a visible lurch — it
            // starts near the finger's own speed and glides into the
            // corner / off the edge.
            animT.animateTo(
                1f, tween(durationMs, easing = FastOutSlowInEasing),
            ) {
                val x = fromTouch.x + (toTouch.x - fromTouch.x) * value
                val y = fromTouch.y + (toTouch.y - fromTouch.y) * value
                // Writing the touch state is all it takes: the draw
                // block reads it, so each animation frame repaints
                // the sheet at the interpolated point.
                touchPoint.value = Offset(x, y)
            }
            // A target outside the current page range belongs to a
            // stale turn (the content switched mid-animation): never
            // land it and never report it to the host.
            if (willCommit && targetPage in 0 until countState.value) {
                internalPage = targetPage
                lastReported = targetPage
                settledState.value(targetPage)
            }
            phase = TurnPhase.IDLE
            prepareWindow()
        }
    }

    /**
     * Touch-Y pinning from Legado's MOVE step (delegate q()): the
     * band decision reads the CURRENT touch Y (ReadView's live touch
     * point, updated from every event), not the DOWN point — a
     * finger that starts in the middle band and slides up crosses
     * into the top pin mid-drag in Legado, and so does ours. Shared
     * by the drag classifier and the programmatic turn below (it is
     * declared first because local functions are not hoisted).
     */
    fun curlTouchY(avgY: Float, dir: Int, height: Float): Float {
        var ty = avgY
        if ((avgY > height / 3f && avgY < height * 2f / 3f) ||
            dir == PageCurlRenderer.PREV
        ) {
            ty = height
        }
        if (avgY <= height / 3f && dir == PageCurlRenderer.NEXT) {
            ty = 1f
        }
        return ty
    }

    /** Programmatic (button / external) turn by one page. */
    fun startProgrammaticTurn(delta: Int) {
        val dir = if (delta > 0) PageCurlRenderer.NEXT else PageCurlRenderer.PREV
        direction = dir
        fromPage = internalPage
        toPage = internalPage + delta
        val w = surfaceSize.width.toFloat()
        val h = surfaceSize.height.toFloat()
        if (w > 0f && h > 0f) {
            renderer.setViewSize(w.toInt(), h.toInt())
            // Synthetic grab built EXACTLY like a manual one
            // (logtest26): the drag classifier anchors the corner
            // from the grab point, re-anchors it for the direction,
            // and rides the touch through the band pinning. This
            // path used to skip the corner anchoring and derive its
            // start Y from the last physical tap, so the sheet kept
            // the corner the renderer was born with — (1,1),
            // top-left — or whatever a previous gesture left behind,
            // and every button / auto-read turn first grew a dark
            // wedge out of the top-left corner instead of lifting
            // the sheet off the turning edge. Now NEXT grabs the
            // bottom of the right edge and sweeps left, PREV the
            // bottom of the left edge — the same points a
            // bottom-band finger drag classifies from.
            val startX = if (dir == PageCurlRenderer.NEXT) w * 0.9f else w * 0.1f
            val startY = h * 0.9f
            renderer.setCornerByPoint(startX, startY)
            renderer.anchorCornerForDrag(dir, startX, startY)
            val ty = curlTouchY(startY, dir, h)
            renderer.setTouch(startX, ty)
            touchPoint.value = Offset(startX, ty)
        }
        finishTurn(commit = true, base = PageCurlRenderer.BASE_PROGRAMMATIC)
    }

    /** Aligns the settled page with the host-driven page index. */
    fun reconcileExternal() {
        val count = countState.value
        if (count <= 0 || phase != TurnPhase.IDLE) return
        val ext = pageIndex.coerceIn(0, count - 1)
        if (ext == internalPage || ext == lastReported) return
        if (instantState.value || zoomScale > 1f) {
            internalPage = ext
            prepareWindow()
            return
        }
        val delta = ext - internalPage
        if (abs(delta) == 1 && snaps.containsKey(internalPage) &&
            snaps.containsKey(ext)
        ) {
            startProgrammaticTurn(delta)
        } else {
            internalPage = ext
            prepareWindow()
        }
    }

    // Runs on every host page change, and again whenever a turn
    // animation lands (phase back to IDLE), so a step requested
    // mid-animation is picked up right after the landing.
    LaunchedEffect(pageIndex, phase) { reconcileExternal() }
    LaunchedEffect(contentKey) {
        // Content (chapter) switch: kill in-flight turn work first —
        // a stale finish animation or window build must never land,
        // paint or report inside the new chapter.
        animJob?.cancel()
        animJob = null
        prepareJob?.cancel()
        phase = TurnPhase.IDLE
        recycleSnaps()
        prepareWindow()
    }
    LaunchedEffect(surfaceSize) {
        if (surfaceSize.width > 0 && surfaceSize.height > 0) prepareWindow()
    }
    DisposableEffect(Unit) {
        onDispose {
            animJob?.cancel()
            prepareJob?.cancel()
            snaps.values.forEach { runCatching { it.recycle() } }
        }
    }

    /**
     * Kicks a window build unless one is already running. Called on
     * every press: the neighbour bitmaps must be in hand BEFORE the
     * drag starts, never fetched mid-drag — and re-calling
     * [prepareWindow] unconditionally would cancel the very build
     * that is about to deliver the target page.
     */
    fun ensureWindow() {
        if (prepareJob?.isActive != true) prepareWindow()
    }

    fun handleTap(pos: Offset) {
        // Single taps fire on the spot: the old build parked every
        // tap in a 300 ms delayed job to wait out a possible second
        // tap, which made every tap-turn feel a beat late. Now the
        // first tap acts immediately; a second tap landing within
        // the double-tap window is recognised as the double-tap and
        // its own tap action is suppressed (the first tap's page
        // step stands, exactly like the pager's zone taps).
        val now = SystemClock.uptimeMillis()
        if (now - lastTapTimeMs <= 300L &&
            (pos - lastTapPos).getDistance() <= 120f
        ) {
            lastTapTimeMs = 0L
            if (zoomEnabled) {
                if (zoomScale > 1f) {
                    zoomScale = 1f
                    zoomOffset = Offset.Zero
                } else {
                    zoomScale = 2.5f
                }
            }
            doubleTapState.value()
        } else {
            lastTapTimeMs = now
            lastTapPos = pos
            val w = surfaceSize.width
            tapState.value(if (w > 0) pos.x / w else 0.5f)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { surfaceSize = it }
            .pointerInput(contentKey, handleTaps, zoomEnabled) {
                val slopSq = viewConfiguration.touchSlop * viewConfiguration.touchSlop
                // Release fling that still completes a turn on its
                // own: 400 dp/s in the turn direction.
                val flingPxPerSecond = 400f * density
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A fresh press lands a running finish animation at
                    // once (Legado v()): a commit-type animation settles
                    // on its target page, a cancel-type one dissolves.
                    if (phase == TurnPhase.ANIM) {
                        animJob?.cancel()
                        animJob = null
                        if (animCommitFlag) {
                            internalPage = animTargetPage
                            lastReported = animTargetPage
                            settledState.value(animTargetPage)
                        }
                        phase = TurnPhase.IDLE
                        prepareWindow()
                    }
                    // Warm the bitmap window at press time so the
                    // current and target pages are ready before the
                    // drag is classified — no waiting on images, no
                    // blank frame mid-turn.
                    ensureWindow()
                    lastDownPoint = down.position
                    renderer.setViewSize(size.width, size.height)
                    renderer.setCornerByPoint(down.position.x, down.position.y)
                    renderer.setTouch(down.position.x, down.position.y)
                    var gestureMode = 0 // 0 none, 1 turn, 2 pan, 3 pinch, 4 dead, 5 chapter-edge drag, 6 pending (image hosts: waiting on the current page bitmap)
                    var dir = PageCurlRenderer.NEXT
                    var lastAvg = down.position
                    val downTimeMs = down.uptimeMillis
                    var longPressFired = false
                    // (uptimeMillis, x) samples of the turn drag, for
                    // the release-velocity classification.
                    val track = ArrayList<Pair<Long, Float>>()
                    var upSeen = false
                    while (!upSeen) {
                        // While the press is unclassified and nearly
                        // still, race the next event against the
                        // long-press deadline: a perfectly still
                        // finger produces no events, so the deadline
                        // itself has to be what fires the long-press.
                        // A real drag classifies on its first event
                        // and leaves this path, cancelling it.
                        val event = if (gestureMode == 0 && handleTaps &&
                            !longPressFired
                        ) {
                            val remaining = 450L -
                                (SystemClock.uptimeMillis() - downTimeMs)
                            if (remaining > 0L) {
                                val arrived = withTimeoutOrNull(remaining) {
                                    awaitPointerEvent()
                                }
                                if (arrived == null) {
                                    longPressFired = true
                                    gestureMode = 4
                                    longPressState.value()
                                    continue
                                }
                                arrived
                            } else {
                                awaitPointerEvent()
                            }
                        } else {
                            awaitPointerEvent()
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            upSeen = true
                            break
                        }
                        if (zoomEnabled && pressed.size >= 2) {
                            if (gestureMode == 1) {
                                // Pinch takes over: roll the half-turn back.
                                finishTurn(
                                    commit = false,
                                    base = PageCurlRenderer.BASE_RELEASE,
                                )
                            }
                            gestureMode = 3
                            val newScale = (zoomScale * event.calculateZoom())
                                .coerceIn(1f, 5f)
                            zoomScale = newScale
                            zoomOffset = if (newScale <= 1f) {
                                Offset.Zero
                            } else {
                                zoomOffset + event.calculatePan()
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val avg = Offset(
                            pressed.sumOf { it.position.x.toDouble() }.toFloat() /
                                pressed.size,
                            pressed.sumOf { it.position.y.toDouble() }.toFloat() /
                                pressed.size,
                        )
                        if (gestureMode == 3) {
                            // One finger left after a pinch: keep panning.
                            if (zoomScale > 1f) {
                                zoomOffset += avg - lastAvg
                                event.changes.forEach { it.consume() }
                            }
                            lastAvg = avg
                            continue
                        }
                        if (gestureMode == 6 && snaps.containsKey(internalPage)) {
                            // Pending drag, current page just
                            // published: fall back into the
                            // classifier below with the full drag
                            // vector (down -> now) intact.
                            gestureMode = 0
                        }
                        if (gestureMode == 0) {
                            val dx = avg.x - down.position.x
                            val dy = avg.y - down.position.y
                            if (dx * dx + dy * dy > slopSq) {
                                if (zoomEnabled && zoomScale > 1f) {
                                    gestureMode = 2
                                } else {
                                    dir = if (dx > 0f) {
                                        PageCurlRenderer.PREV
                                    } else {
                                        PageCurlRenderer.NEXT
                                    }
                                    val target = internalPage +
                                        if (dir == PageCurlRenderer.NEXT) 1 else -1
                                    if (target !in 0 until countState.value) {
                                        // Chapter edge: the sheet never
                                        // moves, but the drag is tracked
                                        // so a committed release can
                                        // cross chapters.
                                        gestureMode = 5
                                        direction = dir
                                        dragStartState.value()
                                        track.add(
                                            pressed.first().uptimeMillis to avg.x,
                                        )
                                    } else if (snaps.containsKey(internalPage) &&
                                        (targetMayLandLate ||
                                            snaps.containsKey(target))
                                    ) {
                                        // BOTH bitmaps gate the drag
                                        // (logtest25): the sheet that
                                        // lifts AND the target page that
                                        // is revealed underneath must
                                        // exist before the curl starts.
                                        // The logtest24 build let the
                                        // target land mid-drag; combined
                                        // with the renderer's degenerate
                                        // geometry at the pinned touch,
                                        // a missing/late target is what
                                        // users saw as the sheet opening
                                        // onto a black void. A press that
                                        // arrives before the target is
                                        // ready now stays dead (window
                                        // build kicked below); the next
                                        // press curls — a beat late beats
                                        // a black page.
                                        //
                                        // Image hosts (targetMayLandLate)
                                        // gate on the CURRENT page alone:
                                        // the logtest25 black void came
                                        // from the novel's renderer
                                        // geometry (long since fixed),
                                        // while a comic target is just a
                                        // network image — the draw
                                        // reveals the current page under
                                        // the lifting sheet until it
                                        // lands, and the settle is
                                        // covered by the live fallback.
                                        // Waiting for it here is what
                                        // made comic drags feel dead
                                        // while the page sat on screen.
                                        gestureMode = 1
                                        direction = dir
                                        fromPage = internalPage
                                        toPage = target
                                        phase = TurnPhase.DRAG
                                        dragStartState.value()
                                        renderer.setCornerByPoint(
                                            down.position.x, down.position.y,
                                        )
                                        renderer.anchorCornerForDrag(
                                            dir, down.position.x, down.position.y,
                                        )
                                        val ty = curlTouchY(
                                            avg.y, dir, size.height.toFloat(),
                                        )
                                        touchPoint.value = Offset(avg.x, ty)
                                        renderer.setTouch(avg.x, ty)
                                        track.add(
                                            pressed.first().uptimeMillis to avg.x,
                                        )
                                    } else {
                                        // Current (or, for text hosts,
                                        // target) bitmap not built yet.
                                        // Text hosts: this press is
                                        // dead, the next one curls.
                                        // Image hosts stay PENDING
                                        // (mode 6) instead: the drag
                                        // keeps being tracked and the
                                        // classifier re-runs the
                                        // moment the current page
                                        // publishes — a press that
                                        // starts during the first
                                        // window build must not be
                                        // thrown away with the page
                                        // already on screen.
                                        gestureMode = if (targetMayLandLate) 6 else 4
                                        ensureWindow()
                                    }
                                }
                            }
                        }
                        when (gestureMode) {
                            1, 5 -> {
                                if (gestureMode == 1) {
                                    val ty = curlTouchY(
                                        avg.y, dir, size.height.toFloat(),
                                    )
                                    // The state write is the repaint
                                    // trigger: the draw block reads
                                    // this value, so the sheet tracks
                                    // the finger on every move event.
                                    touchPoint.value = Offset(avg.x, ty)
                                    renderer.setTouch(avg.x, ty)
                                }
                                // Mode 5 (chapter edge) only tracks:
                                // the sheet never moves without a
                                // target page.
                                val nowMs = pressed.first().uptimeMillis
                                track.add(nowMs to avg.x)
                                while (track.size > 2 &&
                                    nowMs - track.first().first > 120L
                                ) {
                                    track.removeAt(0)
                                }
                                event.changes.forEach {
                                    if (it.positionChanged()) it.consume()
                                }
                            }
                            2 -> {
                                zoomOffset += avg - lastAvg
                                event.changes.forEach { it.consume() }
                            }
                        }
                        lastAvg = avg
                    }
                    // All fingers up.
                    if (gestureMode == 1 || gestureMode == 5) {
                        // Completion is the total drag distance along
                        // the turn direction (a quarter of the width
                        // is enough) or a clear release fling in the
                        // turn direction; anything less springs back
                        // (mode 1) or just ends (mode 5, chapter edge:
                        // past the same bar it crosses chapters).
                        val currentX = if (gestureMode == 1) {
                            touchPoint.value.x
                        } else {
                            lastAvg.x
                        }
                        val dragDist = if (dir == PageCurlRenderer.NEXT) {
                            down.position.x - currentX
                        } else {
                            currentX - down.position.x
                        }
                        var velocityX = 0f
                        if (track.size >= 2) {
                            val first = track.first()
                            val last = track.last()
                            val dtMs = last.first - first.first
                            if (dtMs >= 30L) {
                                velocityX = (last.second - first.second) *
                                    1000f / dtMs
                            }
                        }
                        val flung = if (dir == PageCurlRenderer.NEXT) {
                            velocityX <= -flingPxPerSecond
                        } else {
                            velocityX >= flingPxPerSecond
                        }
                        val committed = dragDist >= size.width * 0.25f || flung
                        if (gestureMode == 1) {
                            finishTurn(
                                commit = committed,
                                base = PageCurlRenderer.BASE_RELEASE,
                            )
                        } else if (committed) {
                            edgeState.value(dir == PageCurlRenderer.NEXT)
                        }
                    } else if (gestureMode == 0 && handleTaps) {
                        handleTap(down.position)
                    }
                }
            },
    ) {
        if (snaps[internalPage] == null) {
            fallback(internalPage)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Reading the touch state HERE — inside the draw scope —
            // is the subscription that makes the canvas repaint on
            // every pointer move and every animation frame. The draw
            // itself only ever blits whole-page bitmaps through
            // precomputed clip paths: no relayout, no decode, nothing
            // but GPU-friendly image draws while a drag runs.
            val touchNow = touchPoint.value
            val current = snaps[internalPage]
            // Paper first (logtest25): whatever happens below — a
            // missing bitmap, a clip path edge, a renderer gap — the
            // pixel under it is the theme's paper color, never the
            // canvas default black. But ONLY once a bitmap or a turn
            // exists to paper under (logtest26): with no snapshot
            // yet and no turn running, the rect used to paint over
            // the host's live fallback for the whole first window
            // build — in the comic reader that build is a network
            // image load, so entering simulation mode meant a solid
            // paper-white screen with the current page hidden under
            // it. Transparent here lets the fallback (the live page)
            // show until the first snapshot lands.
            if (current != null || phase != TurnPhase.IDLE) {
                drawRect(color = Color(backColorArgb))
            }
            if (zoomEnabled && zoomScale > 1f && current != null) {
                withTransform({
                    translate(zoomOffset.x, zoomOffset.y)
                    scale(
                        zoomScale, zoomScale,
                        Offset(size.width / 2f, size.height / 2f),
                    )
                }) {
                    drawImage(current.asImageBitmap())
                }
                return@Canvas
            }
            when (phase) {
                TurnPhase.IDLE -> current?.let { drawImage(it.asImageBitmap()) }
                else -> {
                    val from = snaps[fromPage]
                    // The classification gate guarantees both bitmaps
                    // before a turn starts; `?: from` is only a
                    // last-ditch guard so a frame can never draw a
                    // hole.
                    val to = snaps[toPage] ?: from
                    if (from != null && to != null) {
                        renderer.setViewSize(
                            size.width.toInt(), size.height.toInt(),
                        )
                        renderer.setTouch(touchNow.x, touchNow.y)
                        renderer.draw(
                            drawContext.canvas.nativeCanvas,
                            from, to, backColorArgb,
                        )
                    } else {
                        current?.let { drawImage(it.asImageBitmap()) }
                    }
                }
            }
        }
    }
}
