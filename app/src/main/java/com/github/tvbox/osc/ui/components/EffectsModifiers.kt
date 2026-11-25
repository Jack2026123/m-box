package com.github.tvbox.osc.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.github.tvbox.osc.ui.theme.ControlGroup
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.EffectsConfig
import com.github.tvbox.osc.ui.theme.EffectsState
import com.github.tvbox.osc.ui.theme.resolveUserKind

// ---- 悬浮立体 (relief) SOFT rendering, signed scale (logtest37/38;
// magnitude-only since logtest42d, concave retired) ----
//
// What went wrong in logtest36, for the record: the extrusion wall was
// filled with the raw onSurface color at alpha 0.30-0.65 and stepped
// 24dp deep, the platform shadow saturated toward 0.9 black, the
// canvas shadow was black at 0.70, and the bevel added wide black
// gradient bands plus a hard bottom "lip" line. On device every
// settings group and poster card sat on a thick black tray, and
// translucent panels visibly darkened. The user rejected it.
//
// The signed model (37-42, retired in 42d): a single value s in
// -50..+50 (EffectsState): 0 = flat, positive = convex relief （凸，
// like a capsule inflating), negative = concave intaglio （凹，
// pressed into the surface). Every layer was driven by s
// CONTINUOUSLY — dragging the slider morphed one object through
// flat, with no discrete flips. 42d keeps the continuity and
// drops the sign: s is now magnitude 0..1, always convex.
//
// logtest38 adjustments from the user's device verdicts on 37:
// - F1: at s < 0 NOTHING is painted on the top bevel band — no rim,
//   bright or dark, at the top outer edge. The concave top is only
//   the feathered inner shadow inside the face; the light lives only
//   along the bottom inner edge.
// - F2: max inset per side 9dp -> 16dp (still linear in |s|; corner
//   radii stay concentric, inner = outer - inset, clamped at 0).
// - F3: peak alphas raised about a third over 37 everywhere (bands,
//   halo, extrusion, inner shadow) — tones stay warm gray / white,
//   never black, so the effect reads at arm's length.
// - F4: sign discipline — outer shadows (halo, extrusion, platform)
//   are driven by max(0, s) ONLY; the inner shadow and the bottom
//   light line by max(0, -s) ONLY; at the extremes the two plates are
//   unmistakable.
// - F5: the four bevel bands are FLAT uniform tones (one tone per
//   band: top lightest, bottom darkest, sides mid), cross-faded by
//   the sign of s through 0 — no rolled gradients on the bands and
//   no dark line wrapping the perimeter. The face sheen gradient
//   stays (it is on the face, not an edge).
//
// logtest39 changes (user verdicts on 38 and on a friend's
// gradient-ring variant, which was rejected for its dark edges):
// - the inner face is NO LONGER an equal-dp erosion: it is the
//   outer face scaled about its centre by f = 1 - 0.2t (aspect
//   ratio and corner radii preserved, 20x30 -> 16x24 at max).
// - the bevel ring is split at the corner-arc midpoints by wedge
//   triangles (apex = intersection of the two adjacent corners'
//   bisector lines), one identical join construction at all four
//   corners, no tone ever painted twice over the same point.
//   Tones, alphas, F1, halo, extrusion, sheen and engraving are
//   unchanged from 38.
//
// logtest42d magnitude-only (user verdict: -50..+50 felt like one
// setting folded in half, so the sign retired): the slider is now
// 0..100 magnitude, default 0, and every renderer takes s in 0..1
// always in the convex direction — all concave branches (tNeg,
// inverted dish, dark rims, concave inner shade) are DELETED, not
// bypassed. The logtest42c poster rim survives bright-side only:
// a CRISP 1.5dp white perimeter rim on poster cards ONLY
// (PressableCard path), no blur, alpha = lightScaled(0.85 * s),
// zero at s = 0, drawn last over the profile face so it stays
// crisp on every profile. Halo, platform, extrusion and overall
// amplitude are untouched; non-poster cards and settings
// flatness are untouched.
//
// logtest40 re-tune (user verdicts on 39: shadow too heavy, no
// sense of light, range amplitude too big):
// - amplitude halved across -50..+50: inner-face scale
//   f = 1 - 0.1t (20x30 -> 18x27 at max, not 16x24), platform
//   shadow 24dp -> 12dp at max, extrusion depth 8dp -> 4dp,
//   halo offset 10dp -> 5dp.
// - light leads: top/bottom light bands 0.44 -> 0.56, side light
//   bands 0.20 -> 0.24, face-sheen highlight 0.13 -> 0.20.
// - shadows softer and more feathered, never a hard outline:
//   halo blur 36dp -> 48dp at max with alpha 0.27 -> 0.20,
//   extrusion fade 0.26..0.04 -> 0.18..0.03, platform alpha
//   0.30 -> 0.22, inner-shadow feather 12dp -> 18dp with alpha
//   0.27 -> 0.20, shadow-side bands 0.27/0.16 -> 0.20/0.12.
// - the bevel corner arc follows the GLOBAL corner radius
//   (EffectsState cornerScale): every relief call site resolves
//   its shape through appCardShape/appCardRadius, including
//   previously fixed-radius cards (search rows), so the relief
//   arc can never desync from the card arc.

private val ReliefWarmGray = Color(0xFF7A6A5C)

/**
 * 光感亮度 (logtest42, brightened logtest43): scales ONLY white
 * (light-side) relief alphas by EffectsState lightFactor (value/80,
 * default 1.5x); warm-gray shadow alphas are never passed through
 * here and stay untouched. Capped at 1.0; at brightness 0 all
 * highlights vanish while shadows and the bevel geometry remain.
 */
private fun lightScaled(alpha: Float): Float =
    lightScaled(alpha, EffectsState.config.lightFactor)

/**
 * 元素配置版：同一个上限算法，光感乘子取解析后的元素配置，
 * 不读全局。全局版调用处走上面那个全局版，一字不动。
 */
private fun lightScaled(alpha: Float, lightFactor: Float): Float =
    (alpha * lightFactor).coerceIn(0f, 1f)

/**
 * 高透明 (73): scales ONLY relief alphas by EffectsState
 * highTransparencyFactor (1 at 高透明 0, 0 at 高透明 100).
 * At 100 every relief layer fades to 0, so a card with relief on
 * reads exactly like relief off; at 0 nothing changes; in between
 * it fades proportionally. Geometry (insets, widths, radii, gaps,
 * stroke widths) is never touched — only alpha. Applied to every
 * relief paint site: the six profile faces, the face sheen, the
 * crisp poster rim, the fxCardShadow platform shadows/glow, and
 * the fxGroupShadow halo + extrusion.
 */
private fun htScaled(alpha: Float): Float =
    (alpha * EffectsState.config.highTransparencyFactor).coerceIn(0f, 1f)

private const val EXTRUSION_STEP_DP = 1f

/** Extrusion depth for convex strength t: 0 at t=0, ~3dp at t=0.5, 6dp max at t=1 (logtest43: one notch up from logtest40's 4dp). */
private fun extrusionDepthDp(t: Float): Float = 9f * t  // 挤出深度加强，侧壁更明显

/**
 * Gradient extrusion: the silhouette redrawn at 1dp steps downward,
 * each step fainter than the one above (0.18 beside the element down
 * to 0.03 at the deepest step, softened further at low strength).
 * The first copy sits one step below the element — nothing is
 * painted at zero offset, so the area under the element itself
 * stays clean. Driven by max(0, s) only (convex): callers gate on
 * s > 0.
 */
private fun DrawScope.drawGradientExtrusion(path: Path, t: Float) {
    val depthPx = extrusionDepthDp(t).dp.toPx()
    if (depthPx <= 0f) return
    val stepPx = EXTRUSION_STEP_DP.dp.toPx()
    val steps = (depthPx / stepPx).toInt().coerceAtLeast(1)
    val soften = 0.25f + 0.75f * t
    for (i in 1..steps) {
        val dy = minOf(i * stepPx, depthPx)
        val fade = if (steps == 1) {
            0.18f
        } else {
            0.18f + (0.03f - 0.18f) * ((i - 1).toFloat() / (steps - 1).toFloat())
        }
        drawContext.canvas.save()
        drawContext.canvas.translate(0f, dy)
        drawPath(path, color = ReliefWarmGray, alpha = fade * soften * EffectsState.config.highTransparencyFactor)
        drawContext.canvas.restore()
    }
}

/** Close soft halo on the native canvas: warm gray, heavily blurred, tiny offset. Convex only. Logtest40: blur widened (36dp -> 48dp at max) and alpha lowered (0.27 -> 0.20) so the edge melts instead of drawing a line; offset halved (10dp -> 5dp). Logtest43: alpha one notch up (0.20 -> 0.26), blur kept at 48dp so the edge still melts. */
private fun DrawScope.drawSoftHalo(path: Path, t: Float) {
    val blurPx = (t * 48f).dp.toPx()
    val offsetPx = (t * 5f).dp.toPx()
    val paint = AndroidPaint().apply {
        color = ReliefWarmGray.toArgb()
        alpha = (0.26f * t * EffectsState.config.highTransparencyFactor * 255f).toInt().coerceIn(0, 255)
        isAntiAlias = true
        if (blurPx > 0f) maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
    }
    with(drawContext.canvas.nativeCanvas) {
        save()
        translate(0f, offsetPx)
        drawPath(path.asAndroidPath(), paint)
        restore()
    }
}

/** Convex group underlay: halo first (deepest), then the gradient extrusion wall. Dome keeps the feathered halo only — a stepped tray would fight the dome language. */
private fun DrawScope.drawGroupUnderlay(shape: Shape, t: Float, haloOnly: Boolean) {
    if (t <= 0f) return
    val path = outlineToPath(shape.createOutline(size, layoutDirection, this))
    drawSoftHalo(path, t)
    if (!haloOnly) {
        drawGradientExtrusion(path, t)
    }
}

/**
 * The silhouette scaled PROPORTIONALLY about its own centre by
 * factor [f] (logtest39, replacing the old equal-dp erosion): same
 * centre, same aspect ratio, corner radii scaled by the same
 * factor, so the inner face is a true miniature of the outer one
 * (a 20x30 face at f = 0.8 becomes 16x24, not 16x26). Null for a
 * degenerate outline or a non-positive factor.
 */
private fun scaledOutlinePath(outline: Outline, f: Float): Path? = when (outline) {
    is Outline.Rectangle -> {
        val r = outline.rect
        if (r.width <= 0f || r.height <= 0f || f <= 0f) null
        else {
            val cx = r.left + r.width / 2f
            val cy = r.top + r.height / 2f
            val nw = r.width * f
            val nh = r.height * f
            Path().apply {
                addRect(Rect(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f))
            }
        }
    }
    is Outline.Rounded -> {
        val rr = outline.roundRect
        if (rr.width <= 0f || rr.height <= 0f || f <= 0f) null
        else {
            val cx = rr.left + rr.width / 2f
            val cy = rr.top + rr.height / 2f
            val nw = rr.width * f
            val nh = rr.height * f
            Path().apply {
                addRoundRect(
                    RoundRect(
                        left = cx - nw / 2f,
                        top = cy - nh / 2f,
                        right = cx + nw / 2f,
                        bottom = cy + nh / 2f,
                        topLeftCornerRadius = CornerRadius(
                            rr.topLeftCornerRadius.x * f,
                            rr.topLeftCornerRadius.y * f,
                        ),
                        topRightCornerRadius = CornerRadius(
                            rr.topRightCornerRadius.x * f,
                            rr.topRightCornerRadius.y * f,
                        ),
                        bottomRightCornerRadius = CornerRadius(
                            rr.bottomRightCornerRadius.x * f,
                            rr.bottomRightCornerRadius.y * f,
                        ),
                        bottomLeftCornerRadius = CornerRadius(
                            rr.bottomLeftCornerRadius.x * f,
                            rr.bottomLeftCornerRadius.y * f,
                        ),
                    )
                )
            }
        }
    }
    is Outline.Generic -> {
        val bounds = outline.path.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f || f <= 0f) null
        else {
            val matrix = Matrix()
            val cx = bounds.left + bounds.width / 2f
            val cy = bounds.top + bounds.height / 2f
            matrix.translate(cx, cy)
            matrix.scale(f, f)
            matrix.translate(-cx, -cy)
            Path().apply {
                addPath(outline.path)
                transform(matrix)
            }
        }
    }
}

private fun bandPath(vararg points: Pair<Float, Float>): Path = Path().apply {
    moveTo(points[0].first, points[0].second)
    for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
    close()
}

/**
 * Four-side inset bevel frame for magnitude strength [s] (0..2,
 * logtest42d: always the convex direction, the concave sign is
 * retired): the ring between the silhouette and its
 * proportionally scaled copy (inner = outer scaled about its
 * centre by f = 1 - 0.12t, t = s; a 20x30 face at slider 200
 * (t = 2) becomes 15.2x22.8 — same centre, same aspect ratio,
 * radii scaled by f — so the ring is wider on the long sides
 * than on the short sides, by design), painted in one FLAT tone
 * on all four sides — white 0.70t — with alpha proportional to
 * s, so the frame fades continuously to flat at s = 0 (every
 * alpha is 0). One tone on all sides means the four corners
 * match and the diagonal seams vanish.
 * The outer shadows live elsewhere (halo / extrusion / platform)
 * and there is no inner shadow.
 *
 * Corner joins (logtest39): the sides are painted through wedge
 * clips, one per side, instead of trapezoid bands mitred on the
 * bounding rect. A side's wedge is a TRIANGLE: its apex is the
 * intersection of its two corners' 45-degree bisector lines (each
 * line through a corner's arc centre and its arc midpoint), fanning
 * outward along those lines. The four wedge triangles therefore
 * partition the ring exactly — every corner arc is split at its
 * midpoint between the two adjacent sides' tones, with the SAME
 * join construction at all four corners and no tone ever painted
 * twice over the same point (the old bottom-right dark block and
 * top-left double-light corner were both two bands stacked on one
 * corner).
 *
 * The wedge MUST stay a triangle: an earlier draft used a
 * quadrilateral (corner centres joined to far in/out points) and
 * that polygon self-intersects into a bowtie, whose two lobes
 * paint the opposite strip too — the stacking bug, reintroduced.
 */
private fun DrawScope.drawInsetBevelFrame(shape: Shape, s: Float, lightFactor: Float) {
    // Magnitude-only (logtest42d): s is 0..2, always convex.
    if (s <= 0f) return
    val t = s
    val tPos = s
    // 2026-10-25 corner fix (replaces logtest43's 0.25t): the inner
    // face is the outer face scaled about its centre by
    // f = 1 - 0.12t (f = 1 flat, f = 0.88 at slider 100, f = 0.76
    // at slider 200), so aspect ratio and corner radii are
    // preserved: 20x30 at slider 200 becomes 15.2x22.8. The border
    // per side never exceeds 12%, so the poster corners always
    // stay visible. The bevel ring is therefore wider on the long
    // sides than on the short sides, by design.
    val f = 1f - 0.20f * t  // 2.28boost: 斜面环再加厚，平面切面轮廓更清晰
    val outline = shape.createOutline(size, layoutDirection, this)
    val innerPath = scaledOutlinePath(outline, f) ?: return
    val outerPath = outlineToPath(outline)
    val w = size.width
    val h = size.height
    val white = Color.White
    // Corner arc centres: the outline bounds pulled in by each
    // corner's radii (plain bounds corners when unrounded).
    val rounded = (outline as? Outline.Rounded)?.roundRect
    val bounds = when (outline) {
        is Outline.Rectangle -> outline.rect
        is Outline.Rounded -> outline.roundRect.let {
            Rect(it.left, it.top, it.right, it.bottom)
        }
        is Outline.Generic -> outline.path.getBounds()
    }
    val tlCx = bounds.left + (rounded?.topLeftCornerRadius?.x ?: 0f)
    val tlCy = bounds.top + (rounded?.topLeftCornerRadius?.y ?: 0f)
    val trCx = bounds.right - (rounded?.topRightCornerRadius?.x ?: 0f)
    val trCy = bounds.top + (rounded?.topRightCornerRadius?.y ?: 0f)
    val brCx = bounds.right - (rounded?.bottomRightCornerRadius?.x ?: 0f)
    val brCy = bounds.bottom - (rounded?.bottomRightCornerRadius?.y ?: 0f)
    val blCx = bounds.left + (rounded?.bottomLeftCornerRadius?.x ?: 0f)
    val blCy = bounds.bottom - (rounded?.bottomLeftCornerRadius?.y ?: 0f)
    // Intersection of two lines, each given by a point and a
    // direction. Adjacent corner bisectors are perpendicular, so
    // they always meet; the midpoint fallback only guards a
    // degenerate (parallel) input.
    fun lineIntersection(
        p1x: Float, p1y: Float, d1x: Float, d1y: Float,
        p2x: Float, p2y: Float, d2x: Float, d2y: Float,
    ): Pair<Float, Float> {
        val denom = d1x * d2y - d1y * d2x
        if (denom == 0f) return (p1x + p2x) / 2f to (p1y + p2y) / 2f
        val t = ((p2x - p1x) * d2y - (p2y - p1y) * d2x) / denom
        return (p1x + d1x * t) to (p1y + d1y * t)
    }
    // Far extension along the bisectors: larger than any in-bounds
    // distance, so each wedge triangle spans the whole ring.
    val e = (w + h) * 2f
    val topApex = lineIntersection(tlCx, tlCy, 1f, 1f, trCx, trCy, 1f, -1f)
    val bottomApex = lineIntersection(blCx, blCy, 1f, -1f, brCx, brCy, 1f, 1f)
    val leftApex = lineIntersection(tlCx, tlCy, 1f, 1f, blCx, blCy, 1f, -1f)
    val rightApex = lineIntersection(trCx, trCy, 1f, -1f, brCx, brCy, 1f, 1f)
    val topWedge = bandPath(
        topApex.first to topApex.second,
        (topApex.first + e) to (topApex.second - e),
        (topApex.first - e) to (topApex.second - e),
    )
    val bottomWedge = bandPath(
        bottomApex.first to bottomApex.second,
        (bottomApex.first - e) to (bottomApex.second + e),
        (bottomApex.first + e) to (bottomApex.second + e),
    )
    val leftWedge = bandPath(
        leftApex.first to leftApex.second,
        (leftApex.first - e) to (leftApex.second - e),
        (leftApex.first - e) to (leftApex.second + e),
    )
    val rightWedge = bandPath(
        rightApex.first to rightApex.second,
        (rightApex.first + e) to (rightApex.second - e),
        (rightApex.first + e) to (rightApex.second + e),
    )
    fun drawSide(wedge: Path, tone: Color) {
        if (tone.alpha <= 0.001f) return
        clipPath(wedge) {
            drawRect(
                color = tone,
                topLeft = Offset(bounds.left, bounds.top),
                size = Size(bounds.width, bounds.height),
            )
        }
    }

    clipPath(outerPath) {
        clipPath(innerPath, ClipOp.Difference) {
            if (tPos > 0f) {
                // 2.28boost: 恢复方向性明暗（顶/左亮、底/右暗），让
                // 「平面切面」真正读出受光与背光，三个 profile 差异立刻拉开。
                val lightTone = white.copy(alpha = htScaled(lightScaled(0.38f * tPos, lightFactor)))
                val midTone   = white.copy(alpha = htScaled(lightScaled(0.22f * tPos, lightFactor)))
                val darkTone  = ReliefWarmGray.copy(alpha = htScaled(0.28f * tPos))
                drawSide(topWedge, lightTone)   // 受光面
                drawSide(leftWedge, midTone)    // 侧受光
                drawSide(rightWedge, darkTone)  // 背光
                drawSide(bottomWedge, darkTone) // 背光
            }
        }
    }
}

/**
 * Face sheen for magnitude strength [s] (0..1, logtest42d): a very
 * subtle diagonal gradient across the whole face, clipped to the
 * outline — the capsule "plumpness" cue: lighter at the top rolling
 * to a touch of shade at the bottom, alpha proportional to s,
 * vanishing when flat. Drawn over the content, under the bevel
 * frame.
 */
/**
 * Spherical-dome face (logtest41 second profile, logtest42d
 * elliptical): a shallow dome with NO flat bands — light pooled
 * upper-left (one light source, top-left, shared by every profile) falling softly
 * to transparent at the edges, peak 0.20, feathered halo kept,
 * 1.92 merged poster_bulge_patch bulge shape at 1.91 light levels:
 * 3 stops (peak 0.20, mid-ring 0.07, transparent edge), radius 0.72w,
 * plus limb darkening from the spatial rework,
 * clipped to the outline, drawn over the content. The falloff is
 * ELLIPTICAL and aspect-aware: the circular radial gradient is
 * drawn through a canvas scale about the highlight centre
 * (sx = 1, sy = h/w), so the circle becomes an ellipse whose
 * radii are (0.75w, 0.75h) — the same fractions of the card on
 * every aspect, never a round spotlight on a rectangular card.
 * The gradient clamps to its last stop (transparent) outside the
 * ellipse, so the pool always fades to transparent at the edges:
 * top edge ~0.47 of the y-radius, sides ~0.67 of the x-radius,
 * bottom edge ~0.87, bottom corners outside the ellipse and fully
 * transparent.
 */
private fun DrawScope.drawDomeFace(shape: Shape, s: Float, lightFactor: Float) {
    // Magnitude-only (logtest42d): s is 0..1, always the bulge.
    if (s <= 0f) return
    val path = outlineToPath(shape.createOutline(size, layoutDirection, this))
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val cx = w * 0.38f
    val cy = h * 0.30f
    clipPath(path) {
        drawContext.canvas.save()
        // Scale about the highlight centre: the circular gradient
        // below (radius 0.72w) maps to an ellipse with radii
        // (0.72w, 0.72h) — the card's own aspect.
        drawContext.canvas.translate(cx, cy)
        drawContext.canvas.scale(1f, h / w)
        drawContext.canvas.translate(-cx, -cy)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    // 2.28boost: 高光峰值与中圈明显加强，球面鼓起一目了然
                    Color.White.copy(alpha = htScaled(lightScaled(0.36f * s, lightFactor))),
                    Color.White.copy(alpha = htScaled(lightScaled(0.14f * s, lightFactor))),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = 0.78f * w,
            ),
            size = Size(w, h),
        )
        drawContext.canvas.restore()
        // 1.92：曲面边缘收暗——球面朝观众鼓出，边缘向后弯离视线，
        // 所以边缘比中心暗；只有亮心、没有暗边的只能读成平面上的光斑。
        // 2.28boost: 边缘 limb darkening 加强，鼓包轮廓更清晰
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Transparent,
                    ReliefWarmGray.copy(alpha = htScaled((0.28f * s).coerceIn(0f, 1f))),
                ),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = 0.78f * maxOf(w, h),
            ),
            size = Size(w, h),
        )
    }
}

/**
 * Geometric-faceted face (logtest42 third profile): the card face as a
 * cut gemstone. Construction, deterministic and size-independent: 8
 * perimeter points in order (4 corners + 4 edge midpoints) fan into 8
 * triangular facets around the face centre. Every facet is a FLAT
 * tone — no gradients inside a facet. Brightness follows the facet's
 * orientation to the top-left light: the light direction L = (-1,-1)
 * normalized; for a facet centroid G, d = dot(normalize(G - C), L).
 * d > 0 facets go white (up to 0.45*t), d < 0 facets go warm gray
 * (up to 0.15*t). Every facet
 * edge (8 spokes + 8 outer segments) gets a 2px light cut hairline
 * offset 1px to the light side, so cuts read as edges even where
 * neighbouring facets share a tone. No flat bands,
 * no dome radial gradient. Clipped to the outline (rounded corners
 * handled by the clip, not by the facet geometry); feathered halo
 * kept by the call sites. Zero at s = 0 like the other profiles.
 */
private fun DrawScope.drawFacetedFace(shape: Shape, s: Float, lightFactor: Float) {
    // Magnitude-only (logtest42d): s is 0..1, always the bright-side map.
    if (s <= 0f) return
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val t = s
    val cx = w / 2f
    val cy = h / 2f
    val light = Color.White
    val shade = ReliefWarmGray
    // Light direction (top-left), normalized.
    val invSqrt2 = 0.70710678f
    val lx = -invSqrt2
    val ly = -invSqrt2
    val perimeter = listOf(
        Offset(0f, 0f),
        Offset(cx, 0f),
        Offset(w, 0f),
        Offset(w, cy),
        Offset(w, h),
        Offset(cx, h),
        Offset(0f, h),
        Offset(0f, cy),
    )
    val center = Offset(cx, cy)
    val path = outlineToPath(shape.createOutline(size, layoutDirection, this))
    clipPath(path) {
        // Facet fills.
        for (i in perimeter.indices) {
            val a = perimeter[i]
            val b = perimeter[(i + 1) % perimeter.size]
            val gx = (center.x + a.x + b.x) / 3f
            val gy = (center.y + a.y + b.y) / 3f
            var dx = gx - cx
            var dy = gy - cy
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len <= 0f) continue
            dx /= len
            dy /= len
            val d = dx * lx + dy * ly
            // 2026-10-25: dark-side faces return at faint 0.15 warm
            // gray for contrast (user-approved; the 2026-10-24
            // removal is lifted for face tones only, no drop shadow).
            // White fills are the light side — only they take the
            // 光感亮度 multiplier.
            val color = if (d < 0f) {
                // 2.28boost: 暗面对比加强，刻面明暗立刻可读
                shade.copy(alpha = htScaled(0.22f * t * (-d)))
            } else {
                // 2.28boost: 亮面高光提高
                light.copy(alpha = htScaled(lightScaled(0.42f * t * d, lightFactor)))
            }
            val facet = Path().apply {
                moveTo(center.x, center.y)
                lineTo(a.x, a.y)
                lineTo(b.x, b.y)
                close()
            }
            drawPath(facet, color)
        }
        // Cut hairlines: for each edge, one 1px light line 0.5px to
        // the light side and one 1px shade line 0.5px to the other.
        val lightLine = light
        val shadeLine = shade
        val edges = mutableListOf<Pair<Offset, Offset>>()
        for (p in perimeter) edges.add(center to p)
        for (i in perimeter.indices) {
            edges.add(perimeter[i] to perimeter[(i + 1) % perimeter.size])
        }
        for ((p0, p1) in edges) {
            var ex = p1.x - p0.x
            var ey = p1.y - p0.y
            val elen = kotlin.math.sqrt(ex * ex + ey * ey)
            if (elen <= 0f) continue
            ex /= elen
            ey /= elen
            // Edge normal; pick the side facing the light.
            var nx = -ey
            var ny = ex
            if (nx * lx + ny * ly < 0f) {
                nx = -nx
                ny = -ny
            }
            // White hairline (2px, offset 1px to the light side)
            // takes the 光感亮度 multiplier; the warm-gray hairline
            // is dropped with the shadow side.
            // 2.28boost: 切光线更粗更亮，几何刻面边缘清晰
            val lightLineAlpha = htScaled(lightScaled(0.28f * t, lightFactor))
            drawLine(
                color = lightLine.copy(alpha = lightLineAlpha),
                start = Offset(p0.x + nx * 1f, p0.y + ny * 1f),
                end = Offset(p1.x + nx * 1f, p1.y + ny * 1f),
                strokeWidth = 2.8f,
            )
            // 2.28boost: 背光侧补一条极淡暗线，强化切面边界
            val shadeLineAlpha = htScaled(0.14f * t)
            drawLine(
                color = shadeLine.copy(alpha = shadeLineAlpha),
                start = Offset(p0.x - nx * 1f, p0.y - ny * 1f),
                end = Offset(p1.x - nx * 1f, p1.y - ny * 1f),
                strokeWidth = 1.6f,
            )
        }
    }
}

/**
 * Blue-white porcelain face (profile 3), premium direction:
 * restraint + lustre, no piled decoration. A bright moist
 * glaze-white gradient (top light falling to a softer bottom,
 * never a flat dead-white board) plus one clean deep-cobalt
 * edge line. Relief slider only: the strength [s] (1 at slider
 * 100, up to 2 at slider 200) drives every alpha and width; the
 * light slider does not touch it, and it carries no drop shadow
 * (like flat/faceted). No painted patterns, no corner ornaments.
 */
private fun DrawScope.drawPorcelainFace(shape: Shape, s: Float) {
    if (s <= 0f) return
    if (size.width <= 0f || size.height <= 0f) return
    val t = s
    val path = outlineToPath(shape.createOutline(size, layoutDirection, this))
    // Glaze: top-left light (0.20t) falling to a softer bottom-right
    // (0.15t) — one diagonal light direction, shared by every profile.
    // 68: lifted from 0.20/0.06 so the glaze still reads on busy
    // poster art instead of washing out under the photo.
    // 71: brighter still (user: overall look was dull) — the glaze
    // now reads as luminous porcelain, not a faint wash.
    // 1.92: vertical wash -> diagonal, so the glaze follows the
    // same top-left light as the dome, the facets and the sheen.
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = htScaled((0.20f * t).coerceIn(0f, 1f))),
                Color.White.copy(alpha = htScaled((0.15f * t).coerceIn(0f, 1f))),
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
    )
    // One clean cobalt edge line, paler and fainter than 68.
    // 71: alpha 0.70t -> 0.45t and deep cobalt #1E4A8A -> pale
    // #5B84C4 (user: blue edge fainter) — the edge stays, quiet.
    drawPath(
        path = path,
        color = Color(0xFF5B84C4).copy(alpha = htScaled((0.20f * t).coerceIn(0f, 1f))),
        style = Stroke(width = (2f + 1f * t).dp.toPx()),
    )
}

/**
 * Cocoa keyboard face (profile 4, 69): the card as one plump
 * chocolate keycap — warm brown, never neutral, so it reads
 * instantly apart from the smooth neutral dome. A translucent
 * chocolate wash keeps the poster readable underneath; a dark
 * skirt ring (outer face minus its scaled copy) gives the
 * keycap side wall; the scaled inner face is the raised milk-
 * chocolate top; an elliptical cream bulge highlight sells the
 * bun-like plumpness. Relief slider only: s (1 at slider 100,
 * up to 2 at 200) drives every alpha and the skirt width; the
 * light slider does not touch it (chocolate keeps its own
 * color at any light level). The soft platform shadow lives in
 * [fxCardShadow], not here.
 */
private fun DrawScope.drawCocoaFace(shape: Shape, s: Float) {
    if (s <= 0f) return
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val t = s
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = outlineToPath(outline)
    val milkTop = Color(0xFFC89B6A)
    val milk = Color(0xFF8B5A2B)
    val dark = Color(0xFF4A2C17)
    val cream = Color(0xFFFFE8C8)
    clipPath(path) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    milkTop.copy(alpha = htScaled((0.15f * t).coerceIn(0f, 1f))),
                    milk.copy(alpha = htScaled((0.15f * t).coerceIn(0f, 1f))),
                    dark.copy(alpha = htScaled((0.40f * t).coerceIn(0f, 1f))),
                ),
            ),
            size = Size(w, h),
        )
        drawPath(path, color = dark.copy(alpha = htScaled((0.28f * t).coerceIn(0f, 1f))))
        val f = (1f - 0.14f * t).coerceAtLeast(0.5f)
        val inner = scaledOutlinePath(outline, f)
        if (inner != null) {
            drawPath(
                path = inner,
                brush = Brush.verticalGradient(
                    listOf(
                        milkTop.copy(alpha = htScaled((0.20f * t).coerceIn(0f, 1f))),
                        milk.copy(alpha = htScaled((0.15f * t).coerceIn(0f, 1f))),
                    ),
                ),
            )
        }
        val cx = w * 0.42f
        val cy = h * 0.34f
        drawContext.canvas.save()
        drawContext.canvas.translate(cx, cy)
        drawContext.canvas.scale(1f, h / w)
        drawContext.canvas.translate(-cx, -cy)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    cream.copy(alpha = htScaled((0.20f * t).coerceIn(0f, 1f))),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = 0.55f * w,
            ),
            size = Size(w, h),
        )
        drawContext.canvas.restore()
    }
    drawPath(
        path = path,
        color = dark.copy(alpha = htScaled((0.65f * t).coerceIn(0f, 1f))),
        style = Stroke(width = (1.5f + 1f * t).dp.toPx()),
    )
}

/**
 * Cyberpunk armor face (profile 5, 69, combined spec): night-city
 * dark base + segmented dark armor plates + cyan/magenta neon
 * glowing outer ring (glow wash + bright core, like neon tubes,
 * never flat dead lines) + fine corner circuit traces with
 * nodes. 71: neon moved off the plate seams (69's seams read as
 * a cross in the middle) onto the outermost ring only; plate
 * gaps stay dark, traces stay in corners and never cross the
 * centre. Neon is self-luminous: it never passes through
 * lightScaled, so the light slider cannot dim it. Relief slider
 * only: s drives the black wash, plate tone, ring glow/core and
 * trace alphas. No drop shadow (armor sits flat, like
 * faceted/porcelain).
 */
private fun DrawScope.drawCyberFace(shape: Shape, s: Float) {
    if (s <= 0f) return
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val t = s
    val path = outlineToPath(shape.createOutline(size, layoutDirection, this))
    val cyan = Color(0xFF00E5FF)
    val magenta = Color(0xFFFF2E9A)
    clipPath(path) {
        fun neonLine(color: Color, start: Offset, end: Offset) {
            drawLine(
                color = color.copy(alpha = htScaled((0.30f * t).coerceIn(0f, 1f))),
                start = start,
                end = end,
                strokeWidth = (7f).dp.toPx(),
            )
            drawLine(
                color = color.copy(alpha = htScaled((0.95f * t).coerceIn(0f, 1f))),
                start = start,
                end = end,
                strokeWidth = 2f,
            )
        }
        // 赛博只留外圈霓虹：海报与胶囊本身不盖黑、不铺钢板、不画电路纹，
        // 霓虹只走卡片四边（上左青、下右品红），海报原色露出来。
        neonLine(cyan, Offset(0f, 0f), Offset(w, 0f))
        neonLine(cyan, Offset(0f, 0f), Offset(0f, h))
        neonLine(magenta, Offset(0f, h), Offset(w, h))
        neonLine(magenta, Offset(w, 0f), Offset(w, h))
    }
}

private fun DrawScope.drawFaceSheen(shape: Shape, s: Float, lightFactor: Float) {
    // Magnitude-only (logtest42d): s is 0..1, always convex.
    if (s <= 0f) return
    val path = outlineToPath(shape.createOutline(size, layoutDirection, this))
    val w = size.width
    val h = size.height
    clipPath(path) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    // 2.28boost: 平面切面配套的斜向高光也加强一点
                    Color.White.copy(alpha = htScaled(lightScaled(0.28f * s, lightFactor))),
                    ReliefWarmGray.copy(alpha = htScaled(0.10f * s)),
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            ),
            size = Size(w, h),
        )
    }
}

/**
 * App text never carries a shadow: flat and readable at any light
 * level. The old letterpress white shadow is retired.
 */
@Composable
fun fxEngravedTextStyle(base: TextStyle): TextStyle {
    return base
}

/**
 * Crisp poster rim (logtest42c, PressableCard path only, logtest42d
 * bright-side only): a 1.5dp FLAT white stroke hugging the inside
 * of the card outline — no blur, no gradient — so the relief edge
 * survives busy poster art and any photo background. Alpha is
 * lightScaled(0.85 * s), proportional to s and zero at s = 0, so
 * the rim fades in continuously from flat. The stroke is inset by
 * half its width, so it lies fully inside the layout bounds (a
 * centred stroke would lose its outer half to the draw clip) and
 * follows the card's own arc via the outline's radii. Drawn last,
 * over the profile face, on every profile.
 */
private fun DrawScope.drawCrispPosterRim(shape: Shape, s: Float, lightFactor: Float) {
    // Magnitude-only (logtest42d): bright white rim only, scaling
    // with s; the concave dark rim is retired with the sign.
    if (s <= 0f) return
    val widthPx = 1.5.dp.toPx()
    val half = widthPx / 2f
    val color = Color.White.copy(alpha = htScaled(lightScaled(0.20f * s, lightFactor)))
    if (color.alpha <= 0.001f) return
    val style = Stroke(width = widthPx)
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rounded -> {
            val rr = outline.roundRect
            drawRoundRect(
                color = color,
                topLeft = Offset(rr.left + half, rr.top + half),
                size = Size(
                    (rr.width - widthPx).coerceAtLeast(0f),
                    (rr.height - widthPx).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius(
                    (rr.topLeftCornerRadius.x - half).coerceAtLeast(0f),
                    (rr.topLeftCornerRadius.y - half).coerceAtLeast(0f),
                ),
                style = style,
            )
        }
        is Outline.Rectangle -> {
            val r = outline.rect
            drawRect(
                color = color,
                topLeft = Offset(r.left + half, r.top + half),
                size = Size(
                    (r.width - widthPx).coerceAtLeast(0f),
                    (r.height - widthPx).coerceAtLeast(0f),
                ),
                style = style,
            )
        }
        is Outline.Generic -> {
            // No clean inset for an arbitrary path: a centred stroke
            // still leaves its inner half visible as the edge line.
            drawPath(outlineToPath(outline), color = color, style = style)
        }
    }
}


/**
 * 1.92 空间感：投射阴影。光源在左上，卡片的影子投在背景上，
 * 落在右下方：偏移 (3dp·s, 5dp·s)，模糊 (6dp + 10dp·s)，暖灰，
 * 透明度 0.30·s。画在 drawBehind 里，位于卡片层之外（调用点
 * PressableCard 的 clip 在 fxCardShadow 之后，溢出不被裁），
 * 所以影子能落在卡片外面的背景上——这是“浮在空间里”最直接的证据。
 * 居中的晕影没有方向、没有距离，读出来只是光晕，不是影子。
 */
private fun DrawScope.drawCastShadow(shape: Shape, s: Float) {
    if (s <= 0f) return
    val path = outlineToPath(shape.createOutline(size, layoutDirection, this))
    val dx = (3f * s).dp.toPx()
    val dy = (5f * s).dp.toPx()
    val blurPx = (6f + 10f * s).dp.toPx()
    val alpha = (0.30f * s * EffectsState.config.highTransparencyFactor).coerceIn(0f, 0.55f)
    val paint = AndroidPaint().apply {
        color = ReliefWarmGray.toArgb()
        this.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        isAntiAlias = true
        if (blurPx > 0f) maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
    }
    with(drawContext.canvas.nativeCanvas) {
        save()
        translate(dx, dy)
        drawPath(path.asAndroidPath(), paint)
        restore()
    }
}

/**
 * 1.92 空间感：侧面厚度。卡片顶面之外，在底边与右边各留一条
 * 窄暗边（宽 2dp + 3dp·s），从内侧透明渐变到外侧暗色，内侧
 * 边界压一条 1px 亮线——顶面与侧面交界的刃口。光源左上，
 * 所以侧面只出现在背光的底、右两边；这条暗边是卡片自身的
 * 厚度，不是盖在海报上的罩。赛博不用它（霓虹圈本身就是边）。
 */
private fun DrawScope.drawSlabSide(shape: Shape, s: Float) {
    if (s <= 0f) return
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val path = outlineToPath(shape.createOutline(size, layoutDirection, this))
    val band = (2f + 3f * s).dp.toPx()
    val darkAlpha = (0.28f * s).coerceIn(0f, 0.45f)
    val dark = ReliefWarmGray
    clipPath(path) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, dark.copy(alpha = htScaled(darkAlpha))),
                startY = h - band,
                endY = h,
            ),
            topLeft = Offset(0f, h - band),
            size = Size(w, band),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, dark.copy(alpha = htScaled(darkAlpha))),
                startX = w - band,
                endX = w,
            ),
            topLeft = Offset(w - band, 0f),
            size = Size(band, h),
        )
        val edge = Color.White.copy(alpha = htScaled(lightScaled(0.18f * s)))
        if (edge.alpha > 0.001f) {
            drawLine(color = edge, start = Offset(0f, h - band), end = Offset(w, h - band), strokeWidth = 1f)
            drawLine(color = edge, start = Offset(w - band, 0f), end = Offset(w - band, h), strokeWidth = 1f)
        }
    }
}



/**
 * 胶囊/筛选条/底栏用的鼓起+浮起（海报同款语言，强度可缩放，
 * 取自 poster_bulge_patch 原样）。不依赖 crispRim；可与
 * waterCapsule / Surface 叠加。2.07 起只留球冠鼓起（不盖
 * 文字），不再画阴影层——没有阴影层，就没有对不齐。
 */
/**
 * 2.10 纸变球（用户 12:28、12:34 定）：扁平就是一个面，悬浮
 * 就是要鼓起来；立体效果就是一张纸变成球、球又变回一张纸
 * 的变化，拖杆即动画。画法：亮边向内四档渐淡的厚圆边
 * （宽描边打底、逐档收窄叠加，越靠近边缘越亮、向内渐淡），
 * 不用任何暗色，没有阴影边。卷曲进度 p = 立体值 / 200：
 * 0=平纸，1=成球；卷边宽度取卡片短边的一半 × p，拉满时
 * 四边卷边在中心合拢，整面成球（任何尺寸的卡片胶囊都一样）。
 * 2.12：上限随悬浮立体恢复 200，p = s / 2。
 */
private fun DrawScope.drawBulgeEdge(shape: Shape, s: Float, lightFactor: Float) {
    if (s <= 0f) return
    val p = (s / 2f).coerceIn(0f, 1f)
    if (p <= 0f) return
    val path = outlineToPath(shape.createOutline(size, layoutDirection, this))
    val fade = p * minOf(size.width, size.height) / 2f
    val a = htScaled(lightScaled(0.16f * p, lightFactor))
    clipPath(path) {
        drawPath(path, Color.White.copy(alpha = a), style = Stroke(width = fade * 2f))
        drawPath(path, Color.White.copy(alpha = a), style = Stroke(width = fade))
        drawPath(path, Color.White.copy(alpha = a), style = Stroke(width = fade / 2f))
        drawPath(path, Color.White.copy(alpha = a), style = Stroke(width = fade / 4f))
    }
}

/**
 * 3.10 小编辑框扩展 · 3.11 渲染按组取底 · 3.14 单元素覆盖已删：
 * 浮雕 / 曲面 / 光感 / 水光 / 字色一律读该元素所属用户组的组值，不再有
 * 单元素覆盖（小编辑框改组值，见 ControlElementEditor）。
 *
 * 3.11 底不再是全局 [EffectsState.config]，而是该元素所属用户组
 * （[resolveUserKind]：POSTER / CARD → 卡片组，其余 → 胶囊组）的
 * 组主题 [ControlThemeStore.groupConfig]——global.copy 里的控制字段
 * （cornerScale、cardElevationDp、reliefEnabled、reliefProfile、curvature、
 * curvatureEnabled、curvatureOutward、cardShape、lightBrightness、waterGloss、
 * fontColorArgb）直接换成组值。
 * 组值与全局一致、总开关关、种类不在覆盖范围里，三种情况都
 * 原样返回全局配置，调用处一字不动。
 *
 * 读 [ControlThemeStore.isKindCovered] 会订阅 uiRevision，组值一改
 * （saveGroup 调 bump）读这里的组合即重组。返回的是 [EffectsConfig.copy]，
 * reliefMagnitude / curvatureMagnitude / warpDegree / lightFactor /
 * isReliefDome 等派生值都由 copy 后的原始字段重新算，不用另设。
 *
 * 为什么不用 CompositionLocal：fxCardShadow / fxGroupBevel 是调用方
 * Modifier 链里的兄弟 Modifier，在调用方组合作用域里求值，不是
 * ControlSurface Box 的子内容，ControlSurface 里提供的 Local 够不到
 * 它们；DrawScope 里也读不到 Local，只能在 @Composable 的 fx 层抓值
 * 再当普通参数往下传，跟参数透传是同一份活，还要多改每个调用点去包
 * Provider。所以选可选参数透传：effects = null 时就是全局。
 */
@Composable
fun resolveElementEffects(elementId: String, kind: ControlElementKind): EffectsConfig {
    val global = EffectsState.config
    if (!ControlThemeStore.isControlThemeEnabled()) return global
    if (!ControlThemeStore.isKindCovered(kind)) return global
    val group = resolveUserKind(kind, elementId)
    val groupTheme = ControlThemeStore.groupConfig(group)
    // 3.14：单元素覆盖已删，控制字段一律读组值（胶囊组浮雕 / 曲面 / 光感本来
    // 就不画，见胶囊渲染路；这里只负责把组值抄进 copy，不再叠加任何覆盖）。
    if (
        groupTheme.cornerScale == global.cornerScale &&
        groupTheme.reliefMagnitude == global.cardElevationDp &&
        groupTheme.reliefEnabled == global.reliefEnabled &&
        groupTheme.reliefProfile == global.reliefProfile &&
        groupTheme.curvature == global.curvature &&
        groupTheme.curvatureEnabled == global.curvatureEnabled &&
        groupTheme.curvatureOutward == global.curvatureOutward &&
        groupTheme.shape == global.cardShape &&
        groupTheme.lightBrightness == global.lightBrightness &&
        groupTheme.waterGloss == global.waterGloss &&
        groupTheme.fontColorArgb == global.fontColorArgb
    ) {
        return global
    }
    return global.copy(
        cornerScale = groupTheme.cornerScale,
        cardElevationDp = groupTheme.reliefMagnitude,
        reliefEnabled = groupTheme.reliefEnabled,
        reliefProfile = groupTheme.reliefProfile,
        curvature = groupTheme.curvature,
        curvatureEnabled = groupTheme.curvatureEnabled,
        curvatureOutward = groupTheme.curvatureOutward,
        cardShape = groupTheme.shape,
        lightBrightness = groupTheme.lightBrightness,
        waterGloss = groupTheme.waterGloss,
        fontColorArgb = groupTheme.fontColorArgb,
    )
}

/**
 * 2.07 纯鼓起（用户 09:42 定）：卡片胶囊不再要侧壁/阴影层，
 * 立体只靠表面本身鼓起——没有阴影层，就没有对不齐。
 * 鼓起强度 = 立体值平方 / 2：小值微微隆起，拉满满鼓起读作球。
 * 2.08 起改向内卷边，2.10 起改变厚鼓起（drawBulgeEdge），
 * 此鼓起不再用于卡片胶囊。
 */
fun Modifier.fxCapsuleLift(shape: Shape, strength: Float = 1f, effects: EffectsConfig? = null): Modifier {
    // 2.12：上限随悬浮立体恢复 200（2.10 的 3 撤销）。
    val cfg = effects ?: EffectsState.config
    val s = (cfg.reliefMagnitude * strength.coerceIn(0f, 1.5f)).coerceIn(0f, 2f)
    if (s <= 0f) return this
    return this.drawWithContent {
        drawContent()
        // 2.10 变厚鼓起，取代卷边亮带
        drawBulgeEdge(shape, s, cfg.lightFactor)
    }
}


/**
 * 悬浮立体 for standalone cards (poster/detail/subscription cards with
 * air around them): 1.92 空间感——卡片是空间里的物体：定向投射阴影
 * 落在背景上（光源左上，影子右下方，随立体值偏移与模糊），底边与
 * 右边一条窄暗边读作卡片自身的侧面厚度，各剖面共用左上单光源。
 * NO extrusion tray — posters must never sit on a tray. At 0 this is a
 * no-op (flat). [crispRim] is the logtest42d poster-only bright
 * edge line (see [drawCrispPosterRim]); every other caller keeps
 * the un-rimmed rendering.
 *
 * Grouped settings cards do NOT use this per-card: inside a
 * SettingsGroup the segments sit 2dp apart and would paint over each
 * other's halos, so the group floats as one silhouette via
 * [fxGroupShadow] + [fxGroupBevel] instead.
 */
@Composable
fun Modifier.fxCardShadow(shape: Shape, crispRim: Boolean = false, effects: EffectsConfig? = null): Modifier {
    val cfg = effects ?: EffectsState.config
    val s = cfg.reliefMagnitude
    if (s <= 0f) return this
    // 2.10 变厚鼓起：卡片不再要侧壁/阴影层与球冠，立体只靠
    // 亮边渐淡的厚圆边；剖面档位不再分脸，厚圆边统一。海报亮边线保留。
    return this
        .drawBehind { drawCastShadow(shape, s.coerceAtMost(1.25f)) }
        .drawWithContent {
            drawContent()
            drawSlabSide(shape, (s * 0.55f).coerceAtMost(0.8f))
            drawBulgeEdge(shape, s * 0.72f, cfg.lightFactor)
            if (crispRim) drawCrispPosterRim(shape, s * 0.75f, cfg.lightFactor)
        }
}

/**
 * 悬浮立体 underlay for a whole SettingsGroup: gradient extrusion +
 * soft canvas halo for the group's outer silhouette (full width x
 * group height, group outer corner), painted by the group's
 * behind-layer so the whole block floats as a unit. Only offset
 * copies are drawn — translucent content inside the group never
 * darkens. (No live callers since logtest41 flattened the groups;
 * kept for the group-bevel call sites' future use.)
 */
@Composable
fun Modifier.fxGroupShadow(shape: Shape, effects: EffectsConfig? = null): Modifier {
    val cfg = effects ?: EffectsState.config
    val s = cfg.reliefMagnitude
    if (s <= 0f) return this
    // Dome, faceted, porcelain, cocoa and cyber
    // faces carry no extrusion tray — halo only.
    val haloOnly = cfg.isReliefDome || cfg.isReliefFaceted ||
        cfg.isReliefPorcelain ||
        cfg.isReliefCocoa || cfg.isReliefCyber
    return this.drawBehind { drawGroupUnderlay(shape, s, haloOnly) }
}

/**
 * 悬浮立体 bevel for a whole SettingsGroup: the four-side inset bevel
 * frame over the group's silhouette, applied to a transparent
 * overlay above the group's segments (the overlay itself has no
 * content and no pointer input, so it never intercepts touches).
 */
@Composable
fun Modifier.fxGroupBevel(shape: Shape, effects: EffectsConfig? = null): Modifier {
    val cfg = effects ?: EffectsState.config
    val s = cfg.reliefMagnitude
    if (s <= 0f) return this
    val dome = cfg.isReliefDome
    val faceted = cfg.isReliefFaceted
    val porcelain = cfg.isReliefPorcelain
    val cocoa = cfg.isReliefCocoa
    val cyber = cfg.isReliefCyber
    return this.drawWithContent {
        drawContent()
        if (faceted) {
            drawFacetedFace(shape, s, cfg.lightFactor)
        } else if (dome) {
            drawDomeFace(shape, s, cfg.lightFactor)
        } else if (porcelain) {
            drawPorcelainFace(shape, s)
        } else if (cocoa) {
            drawCocoaFace(shape, s)
        } else if (cyber) {
            drawCyberFace(shape, s)
        } else {
            drawFaceSheen(shape, s, cfg.lightFactor)
            drawInsetBevelFrame(shape, s, cfg.lightFactor)
        }
    }
}

private fun outlineToPath(outline: Outline): Path = when (outline) {
    is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    is Outline.Generic -> outline.path
}

/**
 * 全局圆角 token for main cards whose corner radius is hard-coded
 * rather than taken from the Material3 shapes: the radius follows the
 * same 全局圆角 factor the theme applies to the M3 shape scale.
 */
@Composable
fun appCardShape(base: Dp): RoundedCornerShape =
    RoundedCornerShape(base * EffectsState.config.cornerScale)

/**
 * 正多边形 Shape（2.70）：sides = 5/6/8，顶点朝上（pointy-top），sides < 3 时退回矩形。
 * 3.17 贴格模式：x/y 各用自己的半径，让多边形四边贴满格子——内接圆在 2:3 海报格里
 * 四周留白（六边形宽 95.3dp、高 110dp，格子 110×165dp）；x 半径 = (w/2)/最大|cos|、
 * y 半径 = (h/2)/最大|sin|——六边形就是 (w/2)/cos30°（≈0.577w）、h/2，顶点
 * -90°/-30°/30°/90°/150°/210°，四边全贴；照片内容本身不拉伸（只是裁剪窗口变形）。
 * 与球面 warp 叠加用：PressableCard 把它当 resolvedShape 裁剪 + 画立体边，
 * 立体边（drawBulgeEdge/drawCrispPosterRim）走 Outline.Generic，天然跟形状走。
 */
fun polygonShape(sides: Int): Shape {
    if (sides < 3) return RectangleShape
    return GenericShape { size, _ ->
        val cx = size.width / 2f
        val cy = size.height / 2f
        var maxCos = 0f
        var maxSin = 0f
        for (i in 0 until sides) {
            val angle = -PI / 2.0 + 2.0 * PI * i / sides
            maxCos = maxOf(maxCos, abs(cos(angle)).toFloat())
            maxSin = maxOf(maxSin, abs(sin(angle)).toFloat())
        }
        val rx = size.width / 2f / maxCos
        val ry = size.height / 2f / maxSin
        // 顶点朝上：第一个顶点在正上方（-90°），顺时针排布。
        for (i in 0 until sides) {
            val angle = -PI / 2.0 + 2.0 * PI * i / sides
            val x = cx + rx * cos(angle).toFloat()
            val y = cy + ry * sin(angle).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/**
 * 心形 Shape（2.70）：Material favorite（心）图标的路径，按 24×24
 * 视口等比缩进卡片中心 min(w,h) 的方框；2:3 海报卡上下会切掉一部分，
 * 跟正多边形一个口径。
 */
fun heartShape(): Shape = GenericShape { size, _ ->
    val s = minOf(size.width, size.height) / 24f
    val dx = (size.width - 24f * s) / 2f
    val dy = (size.height - 24f * s) / 2f
    fun X(x: Float) = dx + x * s
    fun Y(y: Float) = dy + y * s
    moveTo(X(12f), Y(21.35f))
    lineTo(X(10.55f), Y(20.03f))
    cubicTo(X(5.4f), Y(15.36f), X(2f), Y(12.28f), X(2f), Y(8.5f))
    cubicTo(X(2f), Y(5.42f), X(4.42f), Y(3f), X(7.5f), Y(3f))
    cubicTo(X(9.24f), Y(3f), X(10.91f), Y(3.81f), X(12f), Y(5.09f))
    cubicTo(X(13.09f), Y(3.81f), X(14.76f), Y(3f), X(16.5f), Y(3f))
    cubicTo(X(19.58f), Y(3f), X(22f), Y(5.42f), X(22f), Y(8.5f))
    cubicTo(X(22f), Y(12.28f), X(18.6f), Y(15.36f), X(13.45f), Y(20.04f))
    lineTo(X(12f), Y(21.35f))
    close()
}

/** 卡片形状解析（2.70/2.71）：0 = 矩形，5/6/8 = 正多边形，99 = 心形，100 = 圆形，其它退回矩形。 */
fun cardShape(value: Int): Shape = when (value) {
    EffectsState.CARD_SHAPE_PENTAGON, EffectsState.CARD_SHAPE_HEXAGON, EffectsState.CARD_SHAPE_OCTAGON -> polygonShape(value)
    EffectsState.CARD_SHAPE_HEART -> heartShape()
    EffectsState.CARD_SHAPE_CIRCLE -> CircleShape
    else -> RectangleShape
}

@Composable
fun appCardRadius(base: Dp): Dp = base * EffectsState.config.cornerScale
