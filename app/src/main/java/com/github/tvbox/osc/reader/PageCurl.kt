package com.github.tvbox.osc.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Build
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Paper-curl ("simulation") page-turn geometry and renderer, ported
 * point-for-point from Legado's SimulationPageDelegate (the classic
 * bezier page curl used by the reading apps): the turning sheet is
 * the quad strip between the two page edges and the fold line through
 * the touch point and the grabbed page corner; its back face is the
 * same bitmap mirrored about the fold axis over the page colour, and
 * the fold carries three gradient shadows (on the revealed page, on
 * the flap, and along the fold itself).
 *
 * Coordinates are raw pixels of the surface the bitmaps cover. The
 * caller owns the touch/corner state machine (drag classification,
 * thresholds, scroller); this class only computes the geometry for
 * the current touch point and paints one frame.
 */
internal class PageCurlRenderer {

    companion object {
        /** Turn direction constants (Legado's PREV / NEXT). */
        const val PREV = 0
        const val NEXT = 1

        /** Scroller duration scale for a finger-released finish. */
        const val BASE_RELEASE = 300

        /** Scroller duration scale for a programmatic (button) turn. */
        const val BASE_PROGRAMMATIC = 100
    }

    var touchX = 0.1f
        private set
    var touchY = 0.1f
        private set
    var cornerX = 1
        private set
    var cornerY = 1
        private set

    /** true when the grabbed corner is right-top or left-bottom. */
    private var isRTorLB = false

    private var viewWidth = 0
    private var viewHeight = 0

    /** hypot(viewWidth, viewHeight); the gradient extent "I". */
    private var diagonal = 0f

    /** Fold angle in degrees, computed by [drawUnder]. */
    private var degrees = 0f

    /** Distance from touch point to bezierStart2 ("D" in Legado's y()). */
    private var touchToCornerDis = 0f

    private val pathA = Path()
    private val pathTmp = Path()

    private val bezierStart1 = PointF()
    private val bezierControl1 = PointF()
    private val bezierVertex1 = PointF()
    private val bezierEnd1 = PointF()
    private val bezierStart2 = PointF()
    private val bezierControl2 = PointF()
    private val bezierVertex2 = PointF()
    private val bezierEnd2 = PointF()

    private val backPaint = Paint().apply { style = Paint.Style.FILL }
    private val backFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    private val reflectMatrix = Matrix()
    private val reflectValues = FloatArray(9)

    // Gradient stops transcribed from Legado's constructor, with the
    // dark stops lightened in logtest26 — the user read the fold as a
    // black edge and asked for the shadows faded way down: fold
    // shadow (drawUnder) first stop alpha FF -> 4D, fold-edge shadows
    // (drawFoldShadows) 80 -> 33, back-face falloff (drawBack) dark
    // stop B0 -> 4D. Same shapes, same extents — the gradients still
    // hug the fold and give the sheet its form, they just whisper
    // instead of painting a black seam.
    private val fallColors = intArrayOf(0x00333333, 0x4D333333)
    private val foldColors = intArrayOf(0x4D111111, 0x00111111)
    private val edgeColors = intArrayOf(0x33111111, 0x00111111)
    private val gradientL = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, fallColors)
        .apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val gradientM = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, fallColors)
        .apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val gradientJ = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, foldColors)
        .apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val gradientK = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, foldColors)
        .apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val gradientP = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, edgeColors)
        .apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val gradientQ = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, edgeColors)
        .apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val gradientO = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, edgeColors)
        .apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
    private val gradientN = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, edgeColors)
        .apply { gradientType = GradientDrawable.LINEAR_GRADIENT }

    fun setViewSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        diagonal = hypot(width.toDouble(), height.toDouble()).toFloat()
    }

    /** Picks the grabbed page corner from a touch point (Legado x()). */
    fun setCornerByPoint(x: Float, y: Float) {
        cornerX = if (x <= (viewWidth / 2).toFloat()) 0 else viewWidth
        cornerY = if (y <= (viewHeight / 2).toFloat()) 0 else viewHeight
        isRTorLB = (cornerX == 0 && cornerY == viewHeight) ||
            (cornerY == 0 && cornerX == viewWidth)
    }

    fun setTouch(x: Float, y: Float) {
        touchX = x
        touchY = y
    }

    /**
     * Corner re-anchoring applied when a drag direction is decided
     * (Legado s()): pulls the corner to the bottom edge for PREV and
     * mirrors the start point for a NEXT started on the left half.
     */
    fun anchorCornerForDrag(direction: Int, startX: Float, startY: Float) {
        if (direction == PREV) {
            if (startX > viewWidth / 2f) {
                setCornerByPoint(startX, viewHeight.toFloat())
            } else {
                setCornerByPoint(viewWidth - startX, viewHeight.toFloat())
            }
        } else if (viewWidth / 2f > startX) {
            setCornerByPoint(viewWidth - startX, startY)
        }
    }

    /**
     * Computes every bezier point for the current touch/corner pair
     * (Legado y()), including the out-of-range start1 correction that
     * pulls the touch point back along the corner ray.
     */
    fun calcPoints() {
        var midX = (touchX + cornerX) / 2f
        var midY = (touchY + cornerY) / 2f
        bezierControl1.x = midX - (cornerY - midY) * (cornerY - midY) / safeDiv(cornerX - midX)
        bezierControl1.y = cornerY.toFloat()
        bezierControl2.x = cornerX.toFloat()
        bezierControl2.y = midY - (cornerX - midX) * (cornerX - midX) / safeDiv(cornerY - midY)
        bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f
        bezierStart1.y = cornerY.toFloat()
        if (touchX > 0f && touchX < viewWidth &&
            (bezierStart1.x < 0f || bezierStart1.x > viewWidth)
        ) {
            if (bezierStart1.x < 0f) {
                bezierStart1.x = viewWidth - bezierStart1.x
            }
            val f1 = abs(cornerX - touchX)
            val f2 = abs(cornerX - viewWidth * f1 / bezierStart1.x)
            val newTouchY = abs(
                cornerY - abs(cornerY - touchY) * abs(cornerX - f2) / f1
            )
            touchX = f2
            touchY = newTouchY
            midX = (touchX + cornerX) / 2f
            midY = (touchY + cornerY) / 2f
            bezierControl1.x =
                midX - (cornerY - midY) * (cornerY - midY) / safeDiv(cornerX - midX)
            bezierControl1.y = cornerY.toFloat()
            bezierControl2.x = cornerX.toFloat()
            bezierControl2.y =
                midY - (cornerX - midX) * (cornerX - midX) / safeDiv(cornerY - midY)
            bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f
            bezierStart1.y = cornerY.toFloat()
        }
        bezierStart2.x = cornerX.toFloat()
        bezierStart2.y = bezierControl2.y - (cornerY - bezierControl2.y) / 2f
        // logtest25 root-cause fix (the "black under-page"): the
        // surface pins the touch Y onto the corner Y for bottom-band
        // turns, so (cornerY - midY) is 0 and the safeDiv(0.1) fallback
        // throws bezierControl2.y / bezierStart2.y to ~-1e6 px. D then
        // explodes to ~1e6 and the under-page fold shadow — a gradient
        // spanning D/4 — stretches across hundreds of thousands of
        // pixels, painting the whole revealed region flat with its
        // first stop, an opaque near-black 0xFF111111. Legado never
        // pins the touch onto the corner, so it never reaches this
        // singularity. Clamp every computed point into a generous
        // band around the view instead: the fold's on-screen
        // direction is unchanged, but all derived distances stay
        // screen-scale and the shadow hugs the fold line again.
        clampPoint(bezierControl1)
        clampPoint(bezierControl2)
        clampPoint(bezierStart1)
        clampPoint(bezierStart2)
        // Legado computes D at exactly this point of y(), from the
        // touch point to bezierStart2 (whose x is the corner x).
        touchToCornerDis = hypot(
            (touchX - bezierStart2.x).toDouble(), (touchY - bezierStart2.y).toDouble()
        ).toFloat().coerceAtMost(hypot(viewWidth.toDouble(), viewHeight.toDouble()).toFloat())
        val end1 = lineIntersect(
            touchX, touchY, bezierControl1.x, bezierControl1.y,
            bezierStart1.x, bezierStart1.y, bezierStart2.x, bezierStart2.y,
        )
        bezierEnd1.set(end1.first, end1.second)
        val end2 = lineIntersect(
            touchX, touchY, bezierControl2.x, bezierControl2.y,
            bezierStart1.x, bezierStart1.y, bezierStart2.x, bezierStart2.y,
        )
        bezierEnd2.set(end2.first, end2.second)
        clampPoint(bezierEnd1)
        clampPoint(bezierEnd2)
        bezierVertex1.x = (bezierStart1.x + 2 * bezierControl1.x + bezierEnd1.x) / 4f
        bezierVertex1.y = (bezierStart1.y + 2 * bezierControl1.y + bezierEnd1.y) / 4f
        bezierVertex2.x = (bezierStart2.x + 2 * bezierControl2.x + bezierEnd2.x) / 4f
        bezierVertex2.y = (bezierStart2.y + 2 * bezierControl2.y + bezierEnd2.y) / 4f
    }

    /** Legado guards a zero divisor in calcPoints with 0.1f; same here. */
    private fun safeDiv(v: Float): Float = if (v == 0f) 0.1f else v

    /**
     * Keeps a computed bezier point finite and within two screens of
     * the view; see the singularity note in [calcPoints]. A
     * non-finite coordinate (0/0 in the line math) collapses onto the
     * corner, which renders as "no curl" rather than garbage.
     */
    private fun clampPoint(p: PointF) {
        if (!p.x.isFinite()) p.x = cornerX.toFloat()
        if (!p.y.isFinite()) p.y = cornerY.toFloat()
        p.x = p.x.coerceIn(-viewWidth * 2f, viewWidth * 3f)
        p.y = p.y.coerceIn(-viewHeight * 2f, viewHeight * 3f)
    }

    /** Intersection of lines (p1,p2) and (p3,p4) (Legado D()). */
    private fun lineIntersect(
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float,
    ): Pair<Float, Float> {
        val k1 = (y2 - y1) / (x2 - x1)
        val b1 = (y2 * x1 - y1 * x2) / (x1 - x2)
        val x = ((y4 * x3 - y3 * x4) / (x3 - x4) - b1) / (k1 - (y4 - y3) / (x4 - x3))
        return x to k1 * x + b1
    }

    /**
     * The scroller target that finishes a turn (Legado l()):
     * [cancel] runs the sheet back to its flat state, otherwise the
     * turn completes past the far edge.
     */
    fun finishTarget(direction: Int, cancel: Boolean): Pair<Float, Float> {
        val dx: Float
        val dy: Float
        if (cancel) {
            var d = if (cornerX <= 0 || direction != NEXT) -touchX else viewWidth - touchX
            if (direction != NEXT) d = -(touchX + viewWidth)
            dx = d
            dy = if (cornerY <= 0) -touchY else viewHeight - touchY
        } else {
            dx = if (cornerX <= 0 || direction != NEXT) {
                viewWidth - touchX
            } else {
                -(touchX + viewWidth)
            }
            dy = (if (cornerY > 0) viewHeight.toFloat() else 1f) - touchY
        }
        return (touchX + dx) to (touchY + dy)
    }

    /** Scroller duration for a finish (Legado u()). */
    fun finishDurationMs(dx: Float, dy: Float, base: Int): Int {
        return if (dx != 0f) {
            (abs(dx) * base / viewWidth).toInt()
        } else {
            (abs(dy) * base / viewHeight).toInt()
        }
    }

    /**
     * Paints one curl frame: the flat part of the turning [sheet],
     * the [under] page in the revealed region with its fold shadow,
     * the two fold-edge shadows, and the mirrored back of the sheet.
     * [backColor] fills the back face under the mirrored bitmap.
     */
    fun draw(canvas: Canvas, sheet: Bitmap, under: Bitmap, backColor: Int) {
        calcPoints()
        drawSheetFlat(canvas, sheet)
        drawUnder(canvas, under)
        drawFoldShadows(canvas)
        drawBack(canvas, sheet, backColor)
    }

    /** Flat remainder of the turning sheet (Legado A()). */
    private fun drawSheetFlat(canvas: Canvas, bitmap: Bitmap) {
        pathA.reset()
        pathA.moveTo(bezierStart1.x, bezierStart1.y)
        pathA.quadTo(bezierControl1.x, bezierControl1.y, bezierEnd1.x, bezierEnd1.y)
        pathA.lineTo(touchX, touchY)
        pathA.lineTo(bezierEnd2.x, bezierEnd2.y)
        pathA.quadTo(bezierControl2.x, bezierControl2.y, bezierStart2.x, bezierStart2.y)
        pathA.lineTo(cornerX.toFloat(), cornerY.toFloat())
        pathA.close()
        canvas.save()
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipOutPath(pathA)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(pathA, Region.Op.XOR)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.restore()
    }

    /**
     * The revealed page and the shadow the fold casts on it
     * (Legado C()). Also fixes [degrees] for the back face.
     */
    private fun drawUnder(canvas: Canvas, bitmap: Bitmap) {
        pathTmp.reset()
        pathTmp.moveTo(bezierStart1.x, bezierStart1.y)
        pathTmp.lineTo(bezierVertex1.x, bezierVertex1.y)
        pathTmp.lineTo(bezierVertex2.x, bezierVertex2.y)
        pathTmp.lineTo(bezierStart2.x, bezierStart2.y)
        pathTmp.lineTo(cornerX.toFloat(), cornerY.toFloat())
        pathTmp.close()
        degrees = Math.toDegrees(
            atan2(
                (bezierControl1.x - cornerX).toDouble(),
                (bezierControl2.y - cornerY).toDouble(),
            )
        ).toFloat()
        val gradient: GradientDrawable
        val left: Int
        val right: Int
        if (isRTorLB) {
            left = bezierStart1.x.toInt()
            right = (bezierStart1.x + touchToCornerDis / 4f).toInt()
            gradient = gradientJ
        } else {
            left = (bezierStart1.x - touchToCornerDis / 4f).toInt()
            right = bezierStart1.x.toInt()
            gradient = gradientK
        }
        canvas.save()
        intersectClip(canvas, pathA, pathTmp)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.rotate(degrees, bezierStart1.x, bezierStart1.y)
        gradient.setBounds(
            left, bezierStart1.y.toInt(), right,
            (diagonal + bezierStart1.y).toInt(),
        )
        gradient.draw(canvas)
        canvas.restore()
    }

    /** The two thin shadows hugging the fold edges (Legado B()). */
    private fun drawFoldShadows(canvas: Canvas) {
        val left = isRTorLB
        val angle = PI / 4 - if (left) {
            atan2(
                (bezierControl1.y - touchY).toDouble(),
                (touchX - bezierControl1.x).toDouble(),
            )
        } else {
            atan2(
                (touchY - bezierControl1.y).toDouble(),
                (touchX - bezierControl1.x).toDouble(),
            )
        }
        val sx = (touchX + cos(angle) * 35.35).toFloat()
        val sy = if (left) {
            (touchY + sin(angle) * 35.35).toFloat()
        } else {
            (touchY - sin(angle) * 35.35).toFloat()
        }

        // Shadow along start1 -> control1.
        pathTmp.reset()
        pathTmp.moveTo(sx, sy)
        pathTmp.lineTo(touchX, touchY)
        pathTmp.lineTo(bezierControl1.x, bezierControl1.y)
        pathTmp.lineTo(bezierStart1.x, bezierStart1.y)
        pathTmp.close()
        val gradient1: GradientDrawable
        val b1Left: Int
        val b1Right: Int
        if (left) {
            b1Left = bezierControl1.x.toInt()
            b1Right = bezierControl1.x.toInt() + 25
            gradient1 = gradientP
        } else {
            b1Left = bezierControl1.x.toInt() - 25
            b1Right = bezierControl1.x.toInt() + 1
            gradient1 = gradientQ
        }
        canvas.save()
        clipOutAIntersect(canvas, pathTmp)
        canvas.rotate(
            Math.toDegrees(
                atan2(
                    (touchX - bezierControl1.x).toDouble(),
                    (bezierControl1.y - touchY).toDouble(),
                )
            ).toFloat(),
            bezierControl1.x, bezierControl1.y,
        )
        gradient1.setBounds(
            b1Left, (bezierControl1.y - diagonal).toInt(),
            b1Right, bezierControl1.y.toInt(),
        )
        gradient1.draw(canvas)
        canvas.restore()

        // Shadow along start2 -> control2.
        pathTmp.reset()
        pathTmp.moveTo(sx, sy)
        pathTmp.lineTo(touchX, touchY)
        pathTmp.lineTo(bezierControl2.x, bezierControl2.y)
        pathTmp.lineTo(bezierStart2.x, bezierStart2.y)
        pathTmp.close()
        val gradient2: GradientDrawable
        val b2Top: Int
        val b2Bottom: Int
        if (left) {
            b2Top = bezierControl2.y.toInt()
            b2Bottom = bezierControl2.y.toInt() + 25
            gradient2 = gradientO
        } else {
            b2Top = bezierControl2.y.toInt() - 25
            b2Bottom = bezierControl2.y.toInt() + 1
            gradient2 = gradientN
        }
        canvas.save()
        clipOutAIntersect(canvas, pathTmp)
        canvas.rotate(
            Math.toDegrees(
                atan2(
                    (bezierControl2.y - touchY).toDouble(),
                    (bezierControl2.x - touchX).toDouble(),
                )
            ).toFloat(),
            bezierControl2.x, bezierControl2.y,
        )
        var refY = bezierControl2.y
        if (refY < 0f) refY -= viewHeight
        val hyp = hypot(bezierControl2.x.toDouble(), refY.toDouble()).toFloat()
        if (hyp > diagonal) {
            gradient2.setBounds(
                (bezierControl2.x - 25 - hyp).toInt(), b2Top,
                (bezierControl2.x + diagonal - hyp).toInt(), b2Bottom,
            )
        } else {
            gradient2.setBounds(
                (bezierControl2.x - diagonal).toInt(), b2Top,
                bezierControl2.x.toInt(), b2Bottom,
            )
        }
        gradient2.draw(canvas)
        canvas.restore()
    }

    /**
     * Back face of the turning sheet: page colour, the sheet bitmap
     * mirrored about the fold axis, and the light falloff gradient
     * (Legado z()).
     */
    private fun drawBack(canvas: Canvas, bitmap: Bitmap, backColor: Int) {
        val leftDis = abs(((bezierStart1.x + bezierEnd1.x) / 2f).toInt() - bezierControl1.x)
        val rightDis = abs(((bezierStart2.y + bezierControl2.y) / 2f).toInt() - bezierControl2.y)
        val minDis = min(leftDis, rightDis)
        pathTmp.reset()
        pathTmp.moveTo(bezierVertex2.x, bezierVertex2.y)
        pathTmp.lineTo(bezierVertex1.x, bezierVertex1.y)
        pathTmp.lineTo(bezierEnd1.x, bezierEnd1.y)
        pathTmp.lineTo(touchX, touchY)
        pathTmp.lineTo(bezierEnd2.x, bezierEnd2.y)
        pathTmp.close()
        val gradient: GradientDrawable
        val left: Int
        val right: Int
        if (isRTorLB) {
            left = bezierStart1.x.toInt() - 1
            right = (bezierStart1.x + minDis + 1f).toInt()
            gradient = gradientL
        } else {
            left = (bezierStart1.x - minDis - 1f).toInt()
            right = bezierStart1.x.toInt() + 1
            gradient = gradientM
        }
        canvas.save()
        intersectClip(canvas, pathA, pathTmp)
        backPaint.colorFilter = backFilter
        val hyp = hypot(
            (cornerX - bezierControl1.x).toDouble(),
            (bezierControl2.y - cornerY).toDouble(),
        ).toFloat()
        val f1 = (cornerX - bezierControl1.x) / hyp
        val f2 = (bezierControl2.y - cornerY) / hyp
        reflectValues.fill(0f)
        reflectValues[0] = 1f - 2f * f2 * f2
        reflectValues[1] = 2f * f1 * f2
        reflectValues[3] = 2f * f1 * f2
        reflectValues[4] = 1f - 2f * f1 * f1
        reflectValues[8] = 1f
        reflectMatrix.reset()
        reflectMatrix.setValues(reflectValues)
        reflectMatrix.preTranslate(-bezierControl1.x, -bezierControl1.y)
        reflectMatrix.postTranslate(bezierControl1.x, bezierControl1.y)
        canvas.drawColor(backColor)
        canvas.drawBitmap(bitmap, reflectMatrix, backPaint)
        backPaint.colorFilter = null
        canvas.rotate(degrees, bezierStart1.x, bezierStart1.y)
        gradient.setBounds(
            left, bezierStart1.y.toInt(), right,
            (bezierStart1.y + diagonal).toInt(),
        )
        gradient.draw(canvas)
        canvas.restore()
    }

    /** clip = clip(first) then intersect second (Legado's SDK split). */
    private fun intersectClip(canvas: Canvas, first: Path, second: Path) {
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipPath(first)
            canvas.clipPath(second)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(first, Region.Op.INTERSECT)
            @Suppress("DEPRECATION")
            canvas.clipPath(second, Region.Op.INTERSECT)
        }
    }

    /** clip = clipOut(pathA) then intersect [path] (Legado B()). */
    private fun clipOutAIntersect(canvas: Canvas, path: Path) {
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipOutPath(pathA)
            canvas.clipPath(path)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(pathA, Region.Op.XOR)
            @Suppress("DEPRECATION")
            canvas.clipPath(path, Region.Op.INTERSECT)
        }
    }
}
