package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2.12 球体真变形：把海报位图按球面网格顶点 warp，而不是
 * scale / rotationX 的假 3D。
 * 2.13 往里面卷（用户 18:23–18:24 定）：原尺寸整张海报往里面
 * 卷成球——人不压扁、不裁切。等距径向卷曲：沿径向的弧长守恒，
 * 中心（人脸）基本不变形，边缘往里面卷（透视缩短，自然成球）。
 * 2.14 曲率扩到 200%（用户 18:58–19:00 定：100% 不是圆，
 * 200% 是个圆）：滑杆 0..200%，warpDegree 0..2；卷曲半径改
 * 0.8w，200% 时纵横屏上半径相等（0.38w），轮廓是正圆，直径
 * 0.76w；100% 时整张海报都看得见（球宽 0.94w、高 1.29w）。
 * 曲率大时海报上下边与角点卷到球背面，背面三角裁掉不画。
 * 球面高光与环境反射只做亮部（加色叠加），没有任何暗色与阴影。
 * 胶囊与其它卡片本版不动，仍走 2.10 的边缘鼓起；设置面板保持
 * 扁平。位图来自 VodPoster 的 AsyncImage onState（Coil 3 已无
 * rememberAsyncImagePainter）。
 * 2.28 黑点补丁（用户 23:40 发的补丁包，基于 2.18）：硬裁剪改成
 * 前半球精确裁剪（跨赤道插值），镜面指数 24→12、强度 0.55→0.22，
 * 环境填充 0.035，高光上限 0.70→0.28，不再靠抬基底洗白整球。
 * 补丁原文件丢了 2.19 的往外折开关，这里补回：outward 参数与
 * 法线取反原样保留，VodPoster 的 curvatureOutward 照常传进来。
 */
private const val WARP_COLS = 24
private const val WARP_ROWS = 32

/**
 * 2.14 卷曲球半径（占卡片宽的比例）：R=0.8w。
 * 100%（degree=1）时球宽 0.94w、高 1.29w（整张海报都看得见）；
 * 200%（degree=2）时纵横屏上半径相等（0.38w），轮廓是正圆，
 * 直径 0.76w——上下边与角点卷到球背面（背面三角裁掉，只看
 * 见球前面的圆）。半径越小卷得越紧，但角点越早卷到背面；
 * 0.8 让正圆正好落在 200%。
 */
private const val ROLL_RADIUS_FRAC = 0.8f

/** 光源方向（左上前方），与 1.92 的左上单光源一致。 */
private val LightDir = floatArrayOf(-0.45f, -0.55f, 0.75f).normalized()

private fun FloatArray.normalized(): FloatArray {
    val len = sqrt(this[0] * this[0] + this[1] * this[1] + this[2] * this[2])
    return floatArrayOf(this[0] / len, this[1] / len, this[2] / len)
}

private fun dot(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float =
    ax * bx + ay * by + az * bz

/**
 * 海报位图的球面 warp + 球面高光（两遍 drawVertices：第一遍贴图，
 * 第二遍按同一顶点位置做加色高光，只亮不暗）。
 */
private fun DrawScope.drawSphereWarp(bitmap: ImageBitmap, warpDegree: Float, outward: Boolean = false) {
    val w = size.width
    val h = size.height
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    if (w <= 0f || h <= 0f || bw <= 0f || bh <= 0f) return
    val degree = warpDegree.coerceIn(0f, 2f)
    // ContentScale.Crop：位图裁出填满画布的那块，纹理坐标取裁后区域。
    // 整张海报，不裁切、不压扁（2.13 往里面卷：原尺寸）。
    val scale = max(w / bw, h / bh)
    val srcW = w / scale
    val srcH = h / scale
    val srcX = (bw - srcW) / 2f
    val srcY = (bh - srcH) / 2f
    // 等距径向卷曲：曲率 k 从 0（平面）到 1/R（满卷），R=0.8w。
    // 弧长守恒：屏上半径 = sin(s·k)/k（k→0 时退化为 s，平面）。
    val rBall = ROLL_RADIUS_FRAC * w
    val k = degree / rBall
    val positions = ArrayList<Offset>((WARP_COLS + 1) * (WARP_ROWS + 1))
    val texs = ArrayList<Offset>((WARP_COLS + 1) * (WARP_ROWS + 1))
    val colors = ArrayList<Color>((WARP_COLS + 1) * (WARP_ROWS + 1))
    val highlights = ArrayList<Color>((WARP_COLS + 1) * (WARP_ROWS + 1))
    // 每顶点法线的 z 分量（2.14 背面裁剪用：nz<0 = 卷到球背面）。
    val normalsZ = ArrayList<Float>((WARP_COLS + 1) * (WARP_ROWS + 1))
    for (j in 0..WARP_ROWS) {
        val v = j.toFloat() / WARP_ROWS
        val fy = (v - 0.5f) * h
        for (i in 0..WARP_COLS) {
            val u = i.toFloat() / WARP_COLS
            val fx = (u - 0.5f) * w
            texs.add(Offset(srcX + u * srcW, srcY + v * srcH))
            if (k <= 1e-6f) {
                // 平面：原位，无高光，法线朝前。
                positions.add(Offset(w / 2f + fx, h / 2f + fy))
                colors.add(Color.White)
                highlights.add(Color.Transparent)
                normalsZ.add(1f)
                continue
            }
            val s = sqrt(fx * fx + fy * fy)
            // 球面法线（已归一化）与屏上位置；中心点法线朝前。
            val nx: Float
            val ny: Float
            val nz: Float
            val px: Float
            val py: Float
            if (s <= 1e-6f) {
                px = 0f
                py = 0f
                nx = 0f
                ny = 0f
                nz = 1f
            } else {
                val phi = s * k
                val sinPhi = sin(phi)
                val cosPhi = cos(phi)
                val screenR = sinPhi / k
                px = fx / s * screenR
                py = fy / s * screenR
                // 2.19 往外折保留：法线横向分量取反，高光跟着翻到另一侧；
                // 屏上位置与 nz 不动，贴图布局不变；裁剪只看 nz，不受往外折影响。
                val dir = if (outward) -1f else 1f
                nx = dir * sinPhi * fx / s
                ny = dir * sinPhi * fy / s
                nz = cosPhi
            }
            positions.add(Offset(w / 2f + px, h / 2f + py))
            normalsZ.add(nz)
            // 漫反射（只提亮，不压暗：下面乘以 warpDegree，且高光遍是加色）。
            val diffuse = max(0f, dot(nx, ny, nz, LightDir[0], LightDir[1], LightDir[2]))
            // 镜面高光：降低指数，避免高曲率下形成针尖状亮斑与强烈反差。
            val rDotV = 2f * nz
            val rxr = rDotV * nx
            val ryr = rDotV * ny
            val rzr = rDotV * nz - 1f
            val spec = max(0f, dot(rxr, ryr, rzr, LightDir[0], LightDir[1], LightDir[2]))
                .pow(12f)
            // 柔和环境填充 + 宽漫反射；仅叠加亮度，不对海报做乘法压暗。
            val rim = max(0f, dot(nx, ny, nz, 0f, 0.95f, 0.31f)).pow(6f)
            val glow = (degree * (0.035f + diffuse * 0.12f + spec * 0.22f + rim * 0.06f))
                .coerceIn(0f, 0.28f)
            val backLight = max(0f, -dot(nx, ny, nz, LightDir[0], LightDir[1], LightDir[2]))
            val shade = (1f - degree * (0.025f + (1f - nz.coerceIn(0f, 1f)) * 0.10f + backLight * 0.045f))
                .coerceIn(0.82f, 1f)
            colors.add(Color(shade, shade, shade, 1f))
            highlights.add(Color.White.copy(alpha = glow))
        }
    }
    // 2.19: 将每个三角形精确裁剪到前半球 nz >= 0。
    // 2.18 的“任一顶点在背面就整三角跳过”会在赤道附近留下不连续缺口，
    // 曲率方向翻转时缺口位置随之移动，视觉上像卡片表面黑点/黑边。
    // 此处对跨赤道边插值屏幕坐标、纹理坐标与高光，再扇形三角化，避免硬跳块。
    val clippedPositions = ArrayList<Offset>(WARP_COLS * WARP_ROWS * 6)
    val clippedTexs = ArrayList<Offset>(WARP_COLS * WARP_ROWS * 6)
    val clippedColors = ArrayList<Color>(WARP_COLS * WARP_ROWS * 6)
    val clippedHighlights = ArrayList<Color>(WARP_COLS * WARP_ROWS * 6)
    val indices = ArrayList<Int>(WARP_COLS * WARP_ROWS * 6)

    fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
    )
    fun lerpColor(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
    )
    fun appendClippedTriangle(a: Int, b: Int, c: Int) {
        val polygon = arrayListOf(a, b, c)
        val output = ArrayList<Int>(4)
        for (edge in polygon.indices) {
            val current = polygon[edge]
            val next = polygon[(edge + 1) % polygon.size]
            val currentZ = normalsZ[current]
            val nextZ = normalsZ[next]
            val currentFront = currentZ >= 0f
            val nextFront = nextZ >= 0f
            if (currentFront) output.add(current)
            if (currentFront != nextFront) {
                val t = (currentZ / (currentZ - nextZ)).coerceIn(0f, 1f)
                clippedPositions.add(lerpOffset(positions[current], positions[next], t))
                clippedTexs.add(lerpOffset(texs[current], texs[next], t))
                clippedColors.add(lerpColor(colors[current], colors[next], t))
                clippedHighlights.add(lerpColor(highlights[current], highlights[next], t))
                output.add(-clippedPositions.lastIndex - 1) // encoded intersection vertex index
            }
        }
        if (output.size < 3) return
        // Copy original front vertices and resolve intersection markers into a flat output mesh.
        val resolved = ArrayList<Int>(output.size)
        for (idx in output) {
            if (idx < 0) {
                resolved.add(-idx - 1)
            } else {
                clippedPositions.add(positions[idx])
                clippedTexs.add(texs[idx])
                clippedColors.add(colors[idx])
                clippedHighlights.add(highlights[idx])
                resolved.add(clippedPositions.lastIndex)
            }
        }
        for (n in 1 until resolved.size - 1) {
            indices.add(resolved[0])
            indices.add(resolved[n])
            indices.add(resolved[n + 1])
        }
    }
    for (j in 0 until WARP_ROWS) {
        for (i in 0 until WARP_COLS) {
            val a = j * (WARP_COLS + 1) + i
            val b = a + 1
            val c = a + WARP_COLS + 1
            val d = c + 1
            appendClippedTriangle(a, b, c)
            appendClippedTriangle(b, d, c)
        }
    }
    val texturePaint = Paint().apply {
        shader = ImageShader(bitmap, TileMode.Clamp, TileMode.Clamp)
    }
    val highlightPaint = Paint()
    // drawVertices 是 Canvas 的方法（Compose 1.12），经 drawIntoCanvas 调用。
    drawIntoCanvas { canvas ->
        canvas.drawVertices(
            Vertices(VertexMode.Triangles, clippedPositions, clippedTexs, clippedColors, indices),
            BlendMode.SrcOver,
            texturePaint,
        )
        canvas.drawVertices(
            Vertices(VertexMode.Triangles, clippedPositions, clippedTexs, clippedHighlights, indices),
            BlendMode.Plus,
            highlightPaint,
        )
    }
}

/** 海报位图的球面 warp 画布（VodPoster 在位图已加载时用它代替 AsyncImage）。 */
@Composable
internal fun WarpedPosterCanvas(
    bitmap: ImageBitmap,
    warpDegree: Float,
    modifier: Modifier = Modifier,
    outward: Boolean = false,
) {
    Canvas(modifier = modifier) {
        drawSphereWarp(bitmap, warpDegree, outward)
    }
}
