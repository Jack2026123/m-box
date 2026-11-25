package com.github.tvbox.osc.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.theme.ControlTheme
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 水滴水光（3.14，胶囊组专用；3.22 按 res 包里的玻璃配方重做；3.25 按 res 实测值重做，
 * 3.25 跟底色走）：玻璃底：315° 三段渐变，由入参底色派生（只动明度，色相/饱和跟底色走；
 * 明度因子按 res 渐变相对未选中底 #383C73 的实测比例标定 0.82/0.74/0.86，alpha 走 res
 * 三档 230/214/204）——默认底 #383C73 时三档约等于 res #2F315E/#282955/#303463（肉眼无差），
 * 用户把胶囊底色改红/蓝后玻璃跟底色走，胶囊真变红/蓝；3.25 之前是写死 res 三色，改底色也变不过来；
 * 选中态（入参是金底）改走金色系渐变（只动明度，色相不动）——蓝紫玻璃盖金底会变浑浊，
 * 金渐变保住选中金底 + 深字，选中/未选中一眼大差别；
 * 浅色磨砂盖层：近白 #E6E6EA（alpha 0.10×f），盖满但底部留 2dp 不盖，底部露出斜渐变底色——
 * 就是 res 选中态 layer-list 两层错开的 depth；
 * 胶囊装饰（3.26）：没有 / 雪花 / 星星 / 枫叶 / 云朵 / 闪电 / 烟花 / 气球 / 金鱼 / 可爱熊 十选，默认没有；
 * 只水滴画，清透不画；数量每颗胶囊随机（星 2–4、雪 2–3、枫叶 3–4、雨 3–5、云 3–4、闪电 1 道、
 * 烟花 2–3 簇、气球 3–5、金鱼远 3–4 条小 + 近 1–2 条大 + 大鲨鱼 1 条、可爱熊 2–3 个），位置由种子随机、每颗胶囊不一样；
 * 3.30：装饰是整片背景（x 全宽，不再分左右条带），字最后画盖在最上层；
 * 动态开关开时雪/枫叶往下飘、云横漂、星星轻微闪烁 + 2 颗流星斜飞、闪电间歇亮、
 * 烟花间歇绽放、气球往上飘、金鱼左右游，全是时间驱动的平滑循环，不断点、不闪烁；
 * 开关关时位置冻住、星星不闪、闪电只留淡折线、烟花只留淡火花线、金鱼停中央。
 * 径向圆顶高光（3.22 新增，弧度）：白径向渐变，中心在上中部（宽 1/2、高 0.22），
 * 半径 1.1 倍高，中心最亮向边缘淡出，短胶囊（筛选 20dp）也能读出鼓起的曲面；
 * 底部边缘光：底部一圈很亮的白带、越靠底越亮（水滴最显眼的特征）；
 * 顶部亮面：柔和白亮面。
 * 强度 = fraction 0..2（= waterGloss / 200，3.25 上限由 1.0 放到 2.0）驱动各层高光与星星亮度；
 * 底渐变是 res 固定值，不随 f 变（f=0 时本函数不画，调用方走平板）。
 * 画法：先画玻璃底、磨砂盖层与星星，再画内容（字），最后画圆顶高光、边缘光与顶部亮面——
 * 底与星在字下、光在字上；clip 在外层，亮面不出框。调用方用此 Modifier 时
 * 自身底色须传透明，否则不透明底会把水滴底盖住。
 * 卡片组（海报）不走这里，仍用 [waterGlossSheen] 的轻亮面。
 * 3.17：调用方（ControlButton / controlSurfaceModifier）已改轻透，本函数暂留备用。
 * 3.18：胶囊风格开关把水滴接回本函数；轻透仍走 0.15 淡底 + 描边 + 顶部亮面，不动这里。
 */
/**
 * 水滴装饰时间轴：30 秒一圈，0..1 线性循环；各装饰用整数倍速换算，循环接点不断点。
 * 时间值在 drawWithContent 里读（只触发重画，不触发重组）；动态开关关时忽略时间，位置冻住。
 */
@Composable
fun Modifier.waterDropGloss(shape: Shape, baseColor: Color, fraction: Float, seed: Int = 0): Modifier {
    if (fraction <= 0f) return this
    val decoration = ControlThemeStore.capsuleDecoration()
    val animated = ControlThemeStore.isCapsuleDecorationAnimated()
    val decoTime = rememberInfiniteTransition(label = "capsuleDeco").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "capsuleDecoTime",
    )
    return this
        .clip(shape)
        .drawWithContent {
            // 3.25：fraction 上限放开到 2.0（水光 400 时 f=2.0）；1.0 以下与原来一样。
            val f = fraction.coerceIn(0f, 2f)
            // 玻璃底：315° 三段渐变，由入参底色派生（只动明度，色相/饱和跟底色走）；
            // 明度因子按 res 渐变相对未选中底 #383C73 的实测比例标定（0.82/0.74/0.86），
            // alpha 仍走 res 三档（230/214/204）。默认 #383C73 时三档约等于
            // #2F315E/#282955/#303463（肉眼无差）；用户把底色改红/蓝后玻璃跟底色走。
            // 选中态入参是金底时改走金色系渐变（只动明度），不盖浑金底。
            val stops = if (isGoldBase(baseColor)) {
                listOf(
                    goldGlassShade(baseColor, 0.72f, 230),
                    goldGlassShade(baseColor, 1.0f, 214),
                    goldGlassShade(baseColor, 1.12f, 204),
                )
            } else {
                listOf(
                    glassShade(baseColor, 0.82f, 230),
                    glassShade(baseColor, 0.74f, 214),
                    glassShade(baseColor, 0.86f, 204),
                )
            }
            drawRect(
                brush = Brush.linearGradient(
                    colors = stops,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
                size = size,
            )
            // 浅色磨砂盖层：近白 #E6E6EA，底部留 2dp 不盖，露出斜渐变底色（layer-list 错开的 depth）。
            val frostAlpha = 0.10f * f
            val frostHeight = (size.height - 2.dp.toPx()).coerceAtLeast(0f)
            if (frostAlpha > 0f && frostHeight > 0f) {
                drawRect(
                    color = Color(0xFFE6E6EA).copy(alpha = frostAlpha),
                    size = Size(size.width, frostHeight),
                )
            }
            // 胶囊装饰（3.26）：只水滴画，清透不画；选「没有」不画；动效开关关时位置冻住。
            // 3.30：字在上面、装饰在下面——装饰与水光高光一律画在 drawContent() 之前，
            // 字最后画、盖在最上层，装饰与高光都不会糊字。
            if (decoration != ControlTheme.DECORATION_NONE) {
                drawCapsuleDecoration(decoration, animated, decoTime.value, f, seed)
            }
            // 径向圆顶高光：白径向渐变，中心上中部、半径 1.1 倍高，中心最亮向边缘淡出，读出弧度。
            val domeAlpha = 0.22f * f
            if (domeAlpha > 0f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = domeAlpha),
                            Color.White.copy(alpha = 0f),
                        ),
                        center = Offset(size.width / 2f, size.height * 0.22f),
                        radius = 1.1f * size.height,
                    ),
                    size = size,
                )
            }
            // r484：水滴专属椭圆高光已去掉（用户嫌多余）——只留圆顶高光、底部边缘光与顶部亮面。
            // 底部边缘光：底部 35% 高度，白带越靠底越亮（底边最亮）。
            val rimAlpha = 0.78f * f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0f),
                        Color.White.copy(alpha = rimAlpha * 0.5f),
                        Color.White.copy(alpha = rimAlpha),
                    ),
                    startY = size.height * 0.65f,
                    endY = size.height,
                ),
                size = Size(size.width, size.height * 0.35f),
                topLeft = Offset(0f, size.height * 0.65f),
            )
            // 顶部柔和亮面：白渐变，只盖上半，字不糊。
            val topAlpha = 0.30f * f
            if (topAlpha > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = topAlpha),
                            Color.White.copy(alpha = 0f),
                        ),
                        startY = 0f,
                        endY = size.height * 0.45f,
                    ),
                    size = Size(size.width, size.height * 0.45f),
                )
            }
            // 字最后画、盖在最上层（3.30：装饰与水光高光都在字下面，字永远清晰）。
            drawContent()
        }
}

/** 入参是不是金底（选中态金色 #FFD34D 一带）：色相 30°–60° 且饱和 > 0.4。 */
private fun isGoldBase(base: Color): Boolean {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    return hsv[0] in 30f..60f && hsv[1] > 0.4f
}

/**
 * 金色玻璃档：只动明度（乘 valueFactor）、色相/饱和不动，alpha 走入参——
 * 选中金底的玻璃仍是金色系，不会被蓝紫盖浑浊。alpha 入参取 res 三档原值（230/214/204）。
 */
private fun goldGlassShade(base: Color, valueFactor: Float, alpha: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[2] = (hsv[2] * valueFactor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(alpha, hsv))
}

/**
 * 水滴玻璃档（3.25 跟底色走）：只动明度（乘 valueFactor）、色相/饱和不动，alpha 走入参——
 * 与 [goldGlassShade] 同款算法（金底那支不动，仍走它）。明度因子按 res 渐变相对未选中底
 * #383C73 的实测比例标定（0.82/0.74/0.86，alpha 仍走 res 三档 230/214/204）：
 * 默认 #383C73 时三档分别约等于 #2F315E/#282955/#303463（实测 RGB 误差 ≤3，肉眼无差）；
 * 用户把底色改红/蓝后，同一套明度结构跟底色走，胶囊真变红/蓝。
 */
private fun glassShade(base: Color, valueFactor: Float, alpha: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[2] = (hsv[2] * valueFactor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(alpha, hsv))
}

/**
 * 装饰粒子：一种装饰里的一个元素。全部由种子随机生成——同一颗胶囊每次画出来一样，
 * 不同胶囊颗数/位置/大小/相位都不一样；位置只由种子定，不每帧重随机，所以不闪烁。
 * x/y 归一化初始位置；sizeDp 是星半径/雪花半径/叶长/雨长/云宽/闪电高占比；
 * phase 是闪烁/飘动相位错开（0..1）；speed 是飘落倍速（整数倍，30 秒循环不断点）；
 * rotation 是倾斜角（叶/雨）；colorArgb 只叶用（秋叶色），其余画白色系。
 */
private data class DecoItem(
    val x: Float,
    val y: Float,
    val sizeDp: Float,
    val alphaFactor: Float,
    val phase: Float,
    val speed: Float,
    val rotation: Float,
    val colorArgb: Int,
)

/** 枫叶色：红/橙/金混着来，枫叶红为主，别全一个色。 */
private val autumnLeafColors = listOf(0xFFD32F2F.toInt(), 0xFFFF8A3D.toInt(), 0xFFFFC93C.toInt())

/** 气球色：浅蓝系（跟用户蓝气球照片一致），几颗颜色深浅不一。 */
private val balloonColors = listOf(0xFF7FB3D5.toInt(), 0xFF5B9BD5.toInt(), 0xFFA8D0E6.toInt(), 0xFF62A1E0.toInt())

/** 烟花色：金/橙/白（跟仙女棒照片一致），金为主、橙点缀、白芯最亮。 */
private val fireworkColors = listOf(0xFFFFD34D.toInt(), 0xFFFFC93C.toInt(), 0xFFFF8A3D.toInt())

/** 金鱼色：橙/金（小金鱼本体），尾巴略浅、身体略深。 */
private val goldfishBodyColor = Color(0xFFFF8A3D.toInt())
private val goldfishTailColor = Color(0xFFFFC93C.toInt())

/**
 * 按种子生成一颗胶囊的装饰布局：颗数随机（星 2–4、雪 2–3、枫叶 3–4、云 3–4、闪电 2–3 道（每道 2–3 条分叉）、烟花 2–3 簇、气球 3–5、金鱼远 3–4 条小 + 近 1–2 条大 + 大鲨鱼 1 条、可爱熊 2–3 个），
 * 位置随机。
 * 3.30：装饰是整片背景——x 全宽随机，不再分左右条带，动画连成一个整体；字由 drawContent
 * 最后画、盖在最上层，装饰与水光高光都在字下面，字永远清晰；窄胶囊（宽 < 64dp）减量：
 * 星/雪/叶/气球 1–2、雨 2–4、云不画，别硬挤。
 * 位置只由种子定，不每帧重随机，所以不闪烁。
 */
private fun decoLayout(decoration: Int, shortCapsule: Boolean, narrowCapsule: Boolean, seed: Int): List<DecoItem> {
    val rnd = Random(seed)
    fun ry(): Float = 0.10f + rnd.nextFloat() * 0.80f
    /** 3.30：装饰是整片背景，不再分左右两块——x 全宽随机（0.05–0.95），动画连成一个整体；
     * 字在中央、由 drawContent 最后画盖在最上层，装饰在字下面、不会糊字。 */
    fun fullX(): Float = 0.05f + rnd.nextFloat() * 0.90f
    return when (decoration) {
        ControlTheme.DECORATION_STAR -> {
            val count = if (narrowCapsule) rnd.nextInt(1, 3) else rnd.nextInt(2, 5)
            List(count) {
                DecoItem(fullX(), ry(), 2.5f + rnd.nextFloat() * 1.1f, 0.7f + rnd.nextFloat() * 0.3f, rnd.nextFloat(), 1f, 0f, 0)
            }
        }
        ControlTheme.DECORATION_SNOW -> {
            val count = if (narrowCapsule) rnd.nextInt(1, 3) else rnd.nextInt(2, 4)
            List(count) {
                DecoItem(fullX(), rnd.nextFloat(), 3f + rnd.nextFloat() * 2f, 0.75f + rnd.nextFloat() * 0.25f, rnd.nextFloat(), 2f, 0f, 0)
            }
        }
        ControlTheme.DECORATION_LEAF -> {
            val count = if (narrowCapsule) rnd.nextInt(1, 3) else rnd.nextInt(3, 5)
            List(count) {
                DecoItem(fullX(), rnd.nextFloat(), 4.5f + rnd.nextFloat() * 3f, 0.85f, rnd.nextFloat(), 2f, -30f + rnd.nextFloat() * 60f, autumnLeafColors[rnd.nextInt(autumnLeafColors.size)])
            }
        }
        ControlTheme.DECORATION_CLOUD -> {
            // 窄胶囊不画云（云太宽会盖字）；短胶囊云缩小、只放上下边缘带。
            val count = if (narrowCapsule) 0 else rnd.nextInt(3, 5)
            List(count) {
                val top = rnd.nextBoolean()
                val y0 = if (top) 0.06f + rnd.nextFloat() * 0.10f else 0.84f + rnd.nextFloat() * 0.10f
                val w = if (shortCapsule) 10f + rnd.nextFloat() * 5f else 15f + rnd.nextFloat() * 12f
                DecoItem(rnd.nextFloat(), y0, w, 0.9f, rnd.nextFloat(), 1f, 0f, 0)
            }
        }
        ControlTheme.DECORATION_LIGHTNING -> {
            // 闪电 2–3 道，竖着占 0.2–0.8 高，x 全宽随机，相位错开不同时闪，字盖在上面。
            val count = rnd.nextInt(2, 4)
            List(count) {
                DecoItem(fullX(), 0.20f, 0.8f, 1f, rnd.nextFloat(), 0f, 0f, 0)
            }
        }
        ControlTheme.DECORATION_FIREWORK -> {
            // 烟花 2–3 簇绽放（中心 + 放射火花），x 全宽随机，相位错开不同时闪，字盖在上面。
            val count = rnd.nextInt(2, 4)
            List(count) {
                DecoItem(fullX(), 0.30f + rnd.nextFloat() * 0.40f, 10f, 1f, rnd.nextFloat(), 0f, 0f, 0)
            }
        }
        ControlTheme.DECORATION_BALLOON -> {
            val count = if (narrowCapsule) rnd.nextInt(2, 4) else rnd.nextInt(3, 6)
            List(count) {
                DecoItem(fullX(), rnd.nextFloat(), 4.5f + rnd.nextFloat() * 2.5f, 0.7f + rnd.nextFloat() * 0.3f, rnd.nextFloat(), 1f, 0f, balloonColors[rnd.nextInt(balloonColors.size)])
            }
        }
        ControlTheme.DECORATION_GOLDFISH -> {
            // 金鱼分远近两层，有纵深感：远处多而小（3–4 条 7–9dp，淡一点、偏上），
            // 近处少而大（1–2 条 16–18dp，实色、偏中下）；远的先画（在后）、近的后画（在前）。
            val farCount = rnd.nextInt(3, 5)
            val nearCount = rnd.nextInt(1, 3)
            val items = ArrayList<DecoItem>(farCount + nearCount)
            for (i in 0 until farCount) {
                items.add(DecoItem(0.15f + rnd.nextFloat() * 0.7f, 0.25f + rnd.nextFloat() * 0.30f, 7f + rnd.nextFloat() * 2f, 0.65f, rnd.nextFloat(), 1f, 0f, 0))
            }
            for (i in 0 until nearCount) {
                items.add(DecoItem(0.25f + rnd.nextFloat() * 0.5f, 0.45f + rnd.nextFloat() * 0.30f, 16f + rnd.nextFloat() * 2f, 1f, rnd.nextFloat(), 1f, 0f, 0))
            }
            // 大鲨鱼 1 条（28dp，比近处金鱼大一圈），跟金鱼一起游，画在最前面；
            // colorArgb = 1 是鲨鱼标记（金鱼是 0），画的时候走鲨鱼形状。
            items.add(DecoItem(0.5f, 0.45f + rnd.nextFloat() * 0.25f, 28f, 1f, rnd.nextFloat(), 1f, 0f, 1))
            items
        }
        ControlTheme.DECORATION_JOLLY -> {
            // 可爱熊 2–3 个：个数随机；熊多就小一点（2 个 26dp、3 个 22dp），
            // 每只相位不同——跳不同的舞，位置上下左右错开，不挤在一起。
            val count = rnd.nextInt(2, 4)
            val size = if (count == 2) 26f else 22f
            List(count) { i ->
                DecoItem(
                    0.30f + i * 0.20f + rnd.nextFloat() * 0.10f,
                    0.30f + i * 0.20f + rnd.nextFloat() * 0.10f,
                    size, 1f, rnd.nextFloat(), 1f, 0f, 0,
                )
            }
        }
        else -> emptyList()
    }
}

/** 四角星路径（8 顶点，外/内角交替，内角半径 0.22×r——细长星芒，跟图 1 一致）。 */
private fun starPath(cx: Float, cy: Float, r: Float): Path {
    val inner = r * 0.22f
    val path = Path()
    path.moveTo(cx, cy - r)
    path.lineTo(cx + inner, cy - inner)
    path.lineTo(cx + r, cy)
    path.lineTo(cx + inner, cy + inner)
    path.lineTo(cx, cy + r)
    path.lineTo(cx - inner, cy + inner)
    path.lineTo(cx - r, cy)
    path.lineTo(cx - inner, cy - inner)
    path.close()
    return path
}

/**
 * 胶囊装饰总入口：按当前选项只画一种；画在玻璃层里、字下面。
 * 种子 = 调用方传的元素 id 哈希 × 31 + 胶囊宽高（同一元素尺寸变了布局也跟着变一点）；
 * 布局由种子一次生成，不每帧重随机；时间 t 只用来算位移/亮度，所以不闪烁、不断点。
 */
private fun DrawScope.drawCapsuleDecoration(decoration: Int, animated: Boolean, t: Float, f: Float, seed: Int) {
    val shortCapsule = size.height < 32.dp.toPx()
    val narrowCapsule = size.width < 64.dp.toPx()
    val effSeed = seed * 31 + size.width.toInt() * 7 + size.height.toInt() * 13
    val items = decoLayout(decoration, shortCapsule, narrowCapsule, effSeed)
    when (decoration) {
        ControlTheme.DECORATION_STAR -> drawDecoStars(items, t, animated, f)
        ControlTheme.DECORATION_SNOW -> drawDecoSnow(items, t, animated, f)
        ControlTheme.DECORATION_LEAF -> drawDecoLeaves(items, t, animated, f)
        ControlTheme.DECORATION_CLOUD -> drawDecoClouds(items, t, animated, f)
        ControlTheme.DECORATION_LIGHTNING -> drawDecoLightning(items, t, animated, f)
        ControlTheme.DECORATION_FIREWORK -> drawDecoFireworks(items, t, animated, f)
        ControlTheme.DECORATION_BALLOON -> drawDecoBalloons(items, t, animated, f)
        ControlTheme.DECORATION_GOLDFISH -> drawDecoGoldfish(items, t, animated, f)
        ControlTheme.DECORATION_JOLLY -> drawDecoJolly(items, t, animated, f)
    }
}

/**
 * 星星：边闪边轻飘——亮度在 0.75..1.0 之间缓慢起伏（像眨眼，不是生硬开关），每颗相位错开，
 * 别一起闪；位置慢慢往下飘（30 秒落满一颗胶囊高，落到底后从顶部重进）+ 左右小摇摆，
 * 速度要慢、别飘出胶囊；静态时亮度固定 0.9、位置冻住。亮白四角星 + 一圈柔光晕（图 1 样式）。
 */
private fun DrawScope.drawDecoStars(items: List<DecoItem>, t: Float, animated: Boolean, f: Float) {
    for (it in items) {
        val y = if (animated) (it.y + t * it.speed) % 1f else it.y
        val cy = size.height * y
        val sway = if (animated) sin((t * 2f + it.phase) * 2f * PI.toFloat()) * size.width * 0.03f else 0f
        val cx = size.width * it.x + sway
        val r = it.sizeDp.dp.toPx()
        val twinkle = if (animated) 0.75f + 0.25f * sin((t * 2f + it.phase) * 2f * PI.toFloat()) else 0.9f
        val starAlpha = (0.80f * f * it.alphaFactor * twinkle).coerceIn(0f, 1f)
        if (starAlpha <= 0f) continue
        val glowAlpha = (0.12f * f * it.alphaFactor * twinkle).coerceIn(0f, 1f)
        if (glowAlpha > 0f) {
            drawCircle(
                color = Color.White.copy(alpha = glowAlpha),
                radius = r * 2.2f,
                center = Offset(cx, cy),
            )
        }
        drawPath(
            path = starPath(cx, cy, r),
            color = Color.White.copy(alpha = starAlpha),
        )
    }
    // 流星：2 颗流星斜飞过（亮头 + 拖尾），约 5 秒一颗、相位错开；静态时不画流星。
    if (animated) {
        for (m in 0 until 2) {
            val mPhase = (if (items.isNotEmpty()) items[m % items.size].phase else 0f) + m * 0.5f
            val cycle = (t * 6f + mPhase) % 1f
            // 流星只在周期的前 0.3 露脸：从左上斜飞到右下，飞完消失。
            if (cycle >= 0.3f) continue
            val progress = cycle / 0.3f
            val headX = size.width * (0.05f + progress * 0.85f)
            val headY = size.height * (0.15f + progress * 0.6f)
            val fade = 1f - progress
            val tailLen = size.width * 0.18f
            val tailAngle = 0.6f
            val tailX = headX - cos(tailAngle) * tailLen
            val tailY = headY - sin(tailAngle) * tailLen
            val streakAlpha = (0.9f * f * fade).coerceIn(0f, 1f)
            if (streakAlpha <= 0f) continue
            drawLine(
                color = Color.White.copy(alpha = streakAlpha),
                start = Offset(tailX, tailY),
                end = Offset(headX, headY),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = Color.White.copy(alpha = streakAlpha),
                radius = 1.5.dp.toPx(),
                center = Offset(headX, headY),
            )
        }
    }
}

/**
 * 雪花：白色六角雪花（三条过中心的直线，0°/60°/120°），每条臂中段两侧带小分叉（羽枝，
 * 跟图 1 的真雪花一致），大小不一；
 * 轻飘：下落更慢（30 秒一圈落 2 次，约 15 秒落满一颗胶囊高），左右摇摆幅度加大
 * （0.06 倍胶囊宽、30 秒摇 2 个来回），像真雪花在空中飘；超出底部后从顶部重新进；
 * 亮度带很轻的明暗起伏（0.85..1.0）；只落在两侧条带、不进中央字区，画在字下面；
 * 静态时停在初始位置、亮度固定。
 */
private fun DrawScope.drawDecoSnow(items: List<DecoItem>, t: Float, animated: Boolean, f: Float) {
    for (it in items) {
        val y = if (animated) (it.y + t * it.speed) % 1f else it.y
        val cy = size.height * y
        val sway = if (animated) sin((t * 2f + it.phase) * 2f * PI.toFloat()) * size.width * 0.06f else 0f
        val cx = size.width * it.x + sway
        val r = it.sizeDp.dp.toPx()
        val breathe = if (animated) 0.85f + 0.15f * sin((t * 2f + it.phase) * 2f * PI.toFloat()) else 0.95f
        val a = (0.70f * f * it.alphaFactor * breathe).coerceIn(0f, 1f)
        if (a <= 0f) continue
        val c = Color.White.copy(alpha = a)
        val w = 1.dp.toPx()
        // 三条主辐 + 每条臂两端中段（0.55r 处）向两侧 45° 的小分叉，长 0.35r。
        for (k in 0 until 3) {
            val ang = k * PI.toFloat() / 3f
            val dx = cos(ang) * r
            val dy = sin(ang) * r
            drawLine(c, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), w, StrokeCap.Round)
            val bx = cos(ang) * r * 0.55f
            val by = sin(ang) * r * 0.55f
            val bl = r * 0.35f
            for (s in listOf(1f, -1f)) {
                val bang = ang + s * PI.toFloat() / 4f
                val ex = bx + cos(bang) * bl
                val ey = by + sin(bang) * bl
                drawLine(c, Offset(cx + bx, cy + by), Offset(cx + ex, cy + ey), w, StrokeCap.Round)
                drawLine(c, Offset(cx - bx, cy - by), Offset(cx - ex, cy - ey), w, StrokeCap.Round)
            }
        }
    }
}

/** 枫叶路径：五裂片（顶裂最长、上裂次之、下裂最短，裂间深凹）+ 一根短叶柄，🍁 造型。 */
private fun leafPath(cx: Float, cy: Float, len: Float): Path {
    val r = len / 2f
    // 五裂片外端：顶（-90°）最长 1.0r，上裂（-18°/198°）0.8r，下裂（54°/126°）0.65r。
    val outerR = listOf(1.0f, 0.8f, 0.65f, 0.65f, 0.8f)
    val path = Path()
    for (k in 0 until 5) {
        val angOuter = (-90f + k * 72f) * PI.toFloat() / 180f
        val ox = cx + cos(angOuter) * r * outerR[k]
        val oy = cy + sin(angOuter) * r * outerR[k]
        if (k == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
        // 裂间凹口（两裂之间，0.42r）。
        val angInner = (-90f + k * 72f + 36f) * PI.toFloat() / 180f
        val ix = cx + cos(angInner) * r * 0.42f
        val iy = cy + sin(angInner) * r * 0.42f
        path.lineTo(ix, iy)
    }
    path.close()
    return path
}

/**
 * 枫叶：五裂枫叶（红/橙/金，🍁 造型），带一点旋转角度；
 * 慢慢往下飘（约 15 秒落满一颗胶囊高），超出底部后从顶部重新进，落下时左右轻摆；
 * 静态时停在初始位置。
 */
private fun DrawScope.drawDecoLeaves(items: List<DecoItem>, t: Float, animated: Boolean, f: Float) {
    for (it in items) {
        val y = if (animated) (it.y + t * it.speed) % 1f else it.y
        val cy = size.height * y
        val sway = if (animated) sin((t * 2f + it.phase) * 2f * PI.toFloat()) * size.width * 0.03f else 0f
        val cx = size.width * it.x + sway
        val len = it.sizeDp.dp.toPx()
        val a = (0.75f * f * it.alphaFactor).coerceIn(0f, 1f)
        if (a <= 0f) continue
        val body = Color(it.colorArgb).copy(alpha = a)
        rotate(degrees = it.rotation, pivot = Offset(cx, cy)) {
            drawPath(leafPath(cx, cy, len), body)
            // 叶柄：从叶底向下短线。
            drawLine(
                color = body,
                start = Offset(cx, cy + len * 0.35f),
                end = Offset(cx, cy + len * 0.75f),
                strokeWidth = 0.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 云朵：小云团（5 个重叠圆拼成，蓬松，跟图 3 一致），白色半透明；慢慢横着漂（30 秒漂过约 1.4 倍胶囊宽），
 * 出右边界后从左边重新进；只落在上下边缘带（不进字区高度）、短胶囊云缩小；静态时停在初始位置。
 */
private fun DrawScope.drawDecoClouds(items: List<DecoItem>, t: Float, animated: Boolean, f: Float) {
    for (it in items) {
        val u = if (animated) (it.x + t) % 1f else it.x
        val cx = (u * 1.4f - 0.2f) * size.width
        val cy = size.height * it.y
        val w = it.sizeDp.dp.toPx()
        val a = (0.22f * f * it.alphaFactor).coerceIn(0f, 1f)
        if (a <= 0f) continue
        val c = Color.White.copy(alpha = a)
        drawCircle(c, radius = w * 0.26f, center = Offset(cx, cy))
        drawCircle(c, radius = w * 0.32f, center = Offset(cx + w * 0.22f, cy - w * 0.10f))
        drawCircle(c, radius = w * 0.24f, center = Offset(cx + w * 0.44f, cy))
        drawCircle(c, radius = w * 0.28f, center = Offset(cx + w * 0.22f, cy + w * 0.06f))
        drawCircle(c, radius = w * 0.18f, center = Offset(cx + w * 0.10f, cy - w * 0.14f))
    }
}

/** 闪电折线组：锯齿主干 + 分叉（真闪电，不闭合、不填充），主干 7–9 段（6–8 个拐点）、x 抖动 ±0.14h，分支 1–2 条、各 3–5 段、长约主干 1/3–1/2；seed 固定则形状固定。返回 [主干, 分支…]。 */
private fun lightningBoltPaths(cx: Float, topY: Float, height: Float, seed: Int): List<Path> {
    val rnd = Random(seed)
    // 主干：大体竖直往下走，只带小而不规则的拐（不左右规律交替），不画成 ⚡ 符号那种对称折线。
    val segments = 7 + rnd.nextInt(3) // 7–9 段
    val points = ArrayList<Offset>(segments + 1)
    var curX = cx
    for (i in 0..segments) {
        val y = topY + height * i / segments
        val x = when (i) {
            0 -> cx
            segments -> cx + (rnd.nextFloat() * 2f - 1f) * 0.04f * height
            else -> {
                // 小拐：每段只偏一点（±0.02h–0.06h），方向随机，不规律交替。
                curX += (rnd.nextFloat() * 2f - 1f) * 0.05f * height
                curX.coerceIn(cx - 0.10f * height, cx + 0.10f * height)
            }
        }
        points.add(Offset(x, y))
    }
    val paths = ArrayList<Path>(4)
    paths.add(Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1..segments) lineTo(points[i].x, points[i].y)
    })
    // 分叉：2–3 条，从主干上、中、下三处分开岔出去，岔得开、岔得长，跟主干一眼分得开，
    // 不是贴着主干的短毛刺；每条分叉自带 3–5 个小拐。
    val branchCount = 2 + rnd.nextInt(2) // 2–3 条
    val branchAt = listOf(0.25f, 0.5f, 0.75f).take(branchCount)
    for ((k, frac) in branchAt.withIndex()) {
        val startIdx = (frac * segments).toInt().coerceIn(1, segments - 1)
        val start = points[startIdx]
        val side = if (k % 2 == 0) 1f else -1f
        val branchSegments = 3 + rnd.nextInt(3) // 3–5 段
        var px = start.x
        var py = start.y
        val branch = Path().apply { moveTo(px, py) }
        for (i in 1..branchSegments) {
            px += side * (0.08f + rnd.nextFloat() * 0.06f) * height // 横向岔得开
            py += (0.07f + rnd.nextFloat() * 0.05f) * height // 竖向共 0.21h–0.60h，岔得长
            branch.lineTo(px, py)
        }
        paths.add(branch)
    }
    return paths
}

/** 紫色闪电色值（3.27，用户定）：亮紫本体 #B388FF（静态淡折线用）、深紫光晕 #9D4EDD；白芯 #F3E8FF。 */
private val lightningCoreColor = Color(0xFFB388FF)
private val lightningGlowColor = Color(0xFF9D4EDD)
private val lightningWhiteColor = Color(0xFFF3E8FF)

/**
 * 闪电：1 道真闪电（锯齿主干 + 分叉、白芯紫光），位置固定在两侧条带（不进中央字区）；
 * 间歇闪——平时很暗，每 5 秒突然亮一下再慢慢暗下去（亮度 onset 后指数衰减约 1.5 秒），
 * 别一直亮、别高频；静态时不亮，只留一条很淡的紫色折线。
 */
private fun DrawScope.drawDecoLightning(items: List<DecoItem>, t: Float, animated: Boolean, f: Float) {
    for (it in items) {
        val cx = size.width * it.x
        val topY = size.height * it.y
        val h = size.height * it.sizeDp
        // 形状随机数由该胶囊的 phase 派生：同一胶囊固定、不同胶囊不同。
        val paths = lightningBoltPaths(cx, topY, h, (it.phase * 1_000_003).toInt())
        if (!animated) {
            for ((i, path) in paths.withIndex()) {
                drawPath(
                    path,
                    lightningCoreColor.copy(alpha = 0.10f),
                    style = Stroke(
                        width = (if (i == 0) 2.2.dp else 1.1.dp).toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
            continue
        }
        val cycle = (t * 6f + it.phase) % 1f
        val flash = if (cycle < 0.06f) 1f else exp(-(cycle - 0.06f) * 7f)
        val glowAlpha = (0.18f * f * flash).coerceIn(0f, 1f)
        if (glowAlpha > 0f) {
            for ((i, path) in paths.withIndex()) {
                drawPath(
                    path,
                    lightningGlowColor.copy(alpha = glowAlpha),
                    style = Stroke(
                        width = (if (i == 0) 7.dp else 3.5.dp).toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
        val coreAlpha = (0.05f + 0.75f * f * flash).coerceIn(0f, 1f)
        for ((i, path) in paths.withIndex()) {
            drawPath(
                path,
                lightningWhiteColor.copy(alpha = coreAlpha),
                style = Stroke(
                    width = (if (i == 0) 2.2.dp else 1.1.dp).toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/**
 * 烟花：一簇绽放（中心亮点 + 8 条放射火花线 + 线头小亮点），金/橙/白，
 * 跟仙女棒照片一致。约 5 秒一闪：闪的瞬间全亮、随后指数衰减，像真烟花绽开又熄灭；
 * 相位错开，别一起闪。静态时只留淡火花线。只落在两侧条带、不进中央字区。
 */
private fun DrawScope.drawDecoFireworks(items: List<DecoItem>, t: Float, animated: Boolean, f: Float) {
    for (it in items) {
        val cx = size.width * it.x
        val cy = size.height * it.y
        val r = it.sizeDp.dp.toPx()
        if (!animated) {
            for (k in 0 until 8) {
                val ang = k * PI.toFloat() / 4f + it.phase
                val dx = cos(ang) * r
                val dy = sin(ang) * r
                drawLine(
                    Color(fireworkColors[k % fireworkColors.size]).copy(alpha = 0.10f),
                    Offset(cx, cy), Offset(cx + dx, cy + dy),
                    1.dp.toPx(), StrokeCap.Round,
                )
            }
            continue
        }
        val cycle = (t * 6f + it.phase) % 1f
        // 绽放：前 0.15 周期火花从中心向外张开（0.2r → 1.0r），随后指数熄灭；
        // 约 5 秒一簇，相位错开，别一起绽。
        val bloom = if (cycle < 0.15f) cycle / 0.15f else 1f
        val fade = if (cycle < 0.15f) 1f else exp(-(cycle - 0.15f) * 5f)
        val rayAlpha = (0.85f * f * it.alphaFactor * fade).coerceIn(0f, 1f)
        if (rayAlpha > 0f) {
            for (k in 0 until 8) {
                val ang = k * PI.toFloat() / 4f + it.phase
                val len = r * (0.2f + 0.8f * bloom)
                val dx = cos(ang) * len
                val dy = sin(ang) * len
                val c = Color(fireworkColors[k % fireworkColors.size]).copy(alpha = rayAlpha)
                drawLine(c, Offset(cx, cy), Offset(cx + dx, cy + dy), 1.2.dp.toPx(), StrokeCap.Round)
                drawCircle(c, radius = 1.dp.toPx(), center = Offset(cx + dx, cy + dy))
            }
            drawCircle(
                color = Color.White.copy(alpha = (0.95f * f * fade).coerceIn(0f, 1f)),
                radius = 1.5.dp.toPx(),
                center = Offset(cx, cy),
            )
        }
    }
}

/**
 * 气球：浅蓝小气球（椭圆球身 + 下面一根细线），慢慢往上飘（30 秒飘满一颗胶囊高，
 * 飘出顶部后从底部重新进），左右轻摆；静态时停在初始位置。只落在两侧条带、不进中央字区。
 */
private fun DrawScope.drawDecoBalloons(items: List<DecoItem>, t: Float, animated: Boolean, f: Float) {
    for (it in items) {
        val y = if (animated) ((it.y - t * it.speed) % 1f + 1f) % 1f else it.y
        val cy = size.height * y
        val sway = if (animated) sin((t * 2f + it.phase) * 2f * PI.toFloat()) * size.width * 0.03f else 0f
        val cx = size.width * it.x + sway
        val r = it.sizeDp.dp.toPx()
        val a = (0.80f * f * it.alphaFactor).coerceIn(0f, 1f)
        if (a <= 0f) continue
        val body = Color(it.colorArgb).copy(alpha = a)
        drawOval(
            color = body,
            topLeft = Offset(cx - r * 0.8f, cy - r),
            size = Size(r * 1.6f, r * 2f),
        )
        drawLine(
            color = Color.White.copy(alpha = (0.5f * f * it.alphaFactor).coerceIn(0f, 1f)),
            start = Offset(cx, cy + r),
            end = Offset(cx, cy + r * 2.2f),
            strokeWidth = 0.75.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 可爱熊：Muse 默认头像 Jolly 的小毛绒全身（奶油色毛茸茸身体 + 浅色光滑脸盘 + 亮黑圆眼睛 +
 * 弯弯小嘴 + 粉红腮红 + 两只小手臂 + 两只小脚），按头像原图比例画（身高约 1.6 倍身宽）；
 * 在胶囊里把 5 种舞蹈随机跳一遍（30 秒一轮，每 6 秒随机换一支：轻跳、摇摆、转圈、蹦跳、旋转跳跃）；
 * 静态时停在中央、不跳。
 */
private fun DrawScope.drawDecoJolly(items: List<DecoItem>, t: Float, animated: Boolean, f: Float) {
    for (it in items) {
        val baseCx = size.width * it.x
        val baseCy = size.height * it.y
        val h = it.sizeDp.dp.toPx()
        val w = h * 0.62f
        val a = (0.95f * f * it.alphaFactor).coerceIn(0f, 1f)
        if (a <= 0f) continue
        // 5 支舞随机跳：每 6 秒换一支，支内进度 dancePhase（0..1）；
        // 随机由时段 + 胶囊种子定，同一颗胶囊 30 秒一轮内顺序固定、不同胶囊顺序不同。
        // 跳舞不是蹦跶：跳是抛物线弧线——起跳、腾空、落地，中间有落地的停顿，
        // 跳得高（0.7–0.8 身高）、腾空时手臂抬起来，落地时手臂垂下。
        // 3.33：熊不能只在原地上下跳——字盖在上面，原地跳基本被字挡住看不见；
        // 每支舞都带左右走位（横向移动 0.6–0.8 身宽），熊在胶囊里走来走去地跳，才看得见在跳舞。
        val slot = if (animated) (t * 5f).toInt() else 0
        val danceIndex = if (animated) Random(slot * 97 + (it.phase * 1000f).toInt()).nextInt(5) else 0
        val dancePhase = if (animated) (t * 5f) % 1f else 0f
        val twoPi = 2f * PI.toFloat()
        // 抛物线跳弧：p 0..1 内从 0 升到最高再回 0（起跳—腾空—落地）。
        fun jumpArc(p: Float): Float = -4f * p * (1f - p)
        // 一跳的窗口：phase 在 [start, end] 内才跳、窗口外在地上站着。
        fun jumpWindow(phase: Float, start: Float, end: Float, height: Float): Float {
            if (phase < start || phase > end) return 0f
            return jumpArc((phase - start) / (end - start)) * height
        }
        var dx = 0f
        var dy = 0f
        var rotation = 0f
        var maxJump = h * 0.8f
        when (danceIndex) {
            0 -> { // 大跳（一支跳 1 下，跳得最高 0.8 身高，边跳边左右走）。
                dy = jumpWindow(dancePhase, 0.15f, 0.65f, h * 0.8f)
                dx = sin(dancePhase * twoPi) * w * 0.8f
            }
            1 -> { // 摇摆跳（左右摇摆 + 身体轻倾，中间跳 1 下 0.7 身高）。
                dx = sin(dancePhase * twoPi) * w * 0.8f
                rotation = sin(dancePhase * twoPi) * 12f
                dy = jumpWindow(dancePhase, 0.30f, 0.75f, h * 0.7f)
                maxJump = h * 0.7f
            }
            2 -> { // 转圈跳（360° 匀速转一圈，中间跳 1 下 0.7 身高，边转边走）。
                rotation = dancePhase * 360f
                dy = jumpWindow(dancePhase, 0.20f, 0.70f, h * 0.7f)
                dx = sin(dancePhase * twoPi) * w * 0.6f
                maxJump = h * 0.7f
            }
            3 -> { // 连跳（一支跳 3 下，每下 0.7 身高，落地有短暂停顿，边跳边走）。
                dy = jumpWindow(dancePhase, 0.00f, 0.28f, h * 0.7f) +
                    jumpWindow(dancePhase, 0.36f, 0.64f, h * 0.7f) +
                    jumpWindow(dancePhase, 0.72f, 1.00f, h * 0.7f)
                dx = sin(dancePhase * twoPi) * w * 0.8f
                maxJump = h * 0.7f
            }
            else -> { // 旋转跳跃（转 360° + 跳 2 下，每下 0.7 身高，边跳边走）。
                rotation = dancePhase * 360f
                dy = jumpWindow(dancePhase, 0.05f, 0.45f, h * 0.7f) +
                    jumpWindow(dancePhase, 0.55f, 0.95f, h * 0.7f)
                dx = sin(dancePhase * twoPi * 2f) * w * 0.6f
                maxJump = h * 0.7f
            }
        }
        // 腾空比例 0..1：跳得越高、手臂抬得越高（落地时 0，手臂垂着）。
        val armLift = (-dy / maxJump).coerceIn(0f, 1f)
        val cx = baseCx + dx
        val cy = baseCy + dy
        val bodyColor = Color(0xFFE8DCC8).copy(alpha = a)
        val faceColor = Color(0xFFF7EBD3).copy(alpha = a)
        rotate(degrees = rotation, pivot = Offset(cx, cy)) {
            // 小手臂：跳起来时抬起来（像跳舞举手，腾空越高抬得越高），落地时垂着。
            val armW = w * 0.14f
            val armH = h * 0.36f
            val armTop = cy - h * 0.08f
            rotate(degrees = -armLift * 70f, pivot = Offset(cx - w * 0.5f, armTop)) {
                drawRoundRect(
                    color = bodyColor,
                    topLeft = Offset(cx - w * 0.5f - armW * 0.55f, armTop),
                    size = Size(armW, armH),
                    cornerRadius = CornerRadius(armW * 0.5f, armW * 0.5f),
                )
            }
            rotate(degrees = armLift * 70f, pivot = Offset(cx + w * 0.5f, armTop)) {
                drawRoundRect(
                    color = bodyColor,
                    topLeft = Offset(cx + w * 0.5f - armW * 0.45f, armTop),
                    size = Size(armW, armH),
                    cornerRadius = CornerRadius(armW * 0.5f, armW * 0.5f),
                )
            }
            // 小脚（底部两只小短脚）。
            val footW = w * 0.24f
            val footH = h * 0.09f
            val footTop = cy + h * 0.5f - footH * 0.6f
            drawRoundRect(
                color = bodyColor,
                topLeft = Offset(cx - w * 0.28f - footW * 0.5f, footTop),
                size = Size(footW, footH),
                cornerRadius = CornerRadius(footH * 0.5f, footH * 0.5f),
            )
            drawRoundRect(
                color = bodyColor,
                topLeft = Offset(cx + w * 0.28f - footW * 0.5f, footTop),
                size = Size(footW, footH),
                cornerRadius = CornerRadius(footH * 0.5f, footH * 0.5f),
            )
            // 身体（高圆柱形，上下全圆角）。
            drawRoundRect(
                color = bodyColor,
                topLeft = Offset(cx - w * 0.5f, cy - h * 0.5f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w * 0.5f, w * 0.5f),
            )
            // 脸盘（上部一块浅色光滑圆角矩形，宽 0.72 身宽、高 0.34 身高）。
            val faceW = w * 0.72f
            val faceH = h * 0.34f
            val faceTop = cy - h * 0.5f + h * 0.06f
            val faceCx = cx
            val faceCy = faceTop + faceH * 0.5f
            drawRoundRect(
                color = faceColor,
                topLeft = Offset(faceCx - faceW * 0.5f, faceTop),
                size = Size(faceW, faceH),
                cornerRadius = CornerRadius(faceH * 0.42f, faceH * 0.42f),
            )
            // 亮黑圆眼睛（脸盘上部，左右各一）。
            val eyeR = w * 0.075f
            val eyeDx = w * 0.18f
            val eyeDy = faceCy - faceH * 0.12f
            drawCircle(color = Color.Black.copy(alpha = a), radius = eyeR, center = Offset(faceCx - eyeDx, eyeDy))
            drawCircle(color = Color.Black.copy(alpha = a), radius = eyeR, center = Offset(faceCx + eyeDx, eyeDy))
            // 眼睛高光（小白点）。
            val glintR = eyeR * 0.35f
            drawCircle(color = Color.White.copy(alpha = a), radius = glintR, center = Offset(faceCx - eyeDx + eyeR * 0.3f, eyeDy - eyeR * 0.3f))
            drawCircle(color = Color.White.copy(alpha = a), radius = glintR, center = Offset(faceCx + eyeDx + eyeR * 0.3f, eyeDy - eyeR * 0.3f))
            // 粉红腮红（眼睛下方偏外，柔和粉）。
            val cheekR = w * 0.085f
            val cheekDx = w * 0.24f
            val cheekDy = faceCy + faceH * 0.14f
            drawCircle(color = Color(0xFFF6C6C6).copy(alpha = (0.85f * a).coerceIn(0f, 1f)), radius = cheekR, center = Offset(faceCx - cheekDx, cheekDy))
            drawCircle(color = Color(0xFFF6C6C6).copy(alpha = (0.85f * a).coerceIn(0f, 1f)), radius = cheekR, center = Offset(faceCx + cheekDx, cheekDy))
            // 弯弯小嘴（眼睛下方中央，细黑弧线）。
            drawArc(
                color = Color(0xFF3A2E22).copy(alpha = a),
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(faceCx - w * 0.09f, faceCy + faceH * 0.02f),
                size = Size(w * 0.18f, faceH * 0.22f),
                style = Stroke(width = 0.7.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** 鲨鱼色：灰蓝身、浅灰蓝肚子。 */
private val sharkBodyColor = Color(0xFF5B7A99)
private val sharkBellyColor = Color(0xFFB8C9D9)

/**
 * 大鲨鱼：灰蓝纺锤身（尖吻、宽身、收尾）+ 背鳍 + 胸鳍 + 竖新月尾鳍 + 小黑眼 + 嘴弧线，
 * 跟金鱼一起左右游（朝向跟着速度方向翻），尾巴轻摆；画在金鱼最前面。
 */
private fun DrawScope.drawDecoShark(cx: Float, cy: Float, len: Float, dir: Float, wag: Float, a: Float) {
    val h = len * 0.28f
    val body = sharkBodyColor.copy(alpha = a)
    val belly = sharkBellyColor.copy(alpha = a)
    // 尾巴：竖新月形尾鳍（上下两叶），尾尖上下轻摆。
    val tailBaseX = cx - dir * len * 0.42f
    val tailTipX = tailBaseX - dir * len * 0.24f
    val tailPath = Path().apply {
        moveTo(tailBaseX, cy - h * 0.18f)
        lineTo(tailTipX, cy - h * 0.95f + wag * len * 0.08f)
        lineTo(tailBaseX, cy)
        lineTo(tailTipX, cy + h * 0.95f + wag * len * 0.08f)
        lineTo(tailBaseX, cy + h * 0.18f)
        close()
    }
    drawPath(tailPath, body)
    // 身体：纺锤形（吻尖在前、身宽在中、尾基收窄）。
    val bodyPath = Path().apply {
        moveTo(cx + dir * len * 0.5f, cy)
        quadraticTo(cx + dir * len * 0.15f, cy - h * 0.55f, cx - dir * len * 0.42f, cy - h * 0.10f)
        lineTo(cx - dir * len * 0.42f, cy + h * 0.10f)
        quadraticTo(cx + dir * len * 0.15f, cy + h * 0.55f, cx + dir * len * 0.5f, cy)
        close()
    }
    drawPath(bodyPath, body)
    // 肚子：下半一道浅色。
    val bellyPath = Path().apply {
        moveTo(cx + dir * len * 0.48f, cy + h * 0.02f)
        quadraticTo(cx, cy + h * 0.48f, cx - dir * len * 0.40f, cy + h * 0.08f)
        lineTo(cx - dir * len * 0.40f, cy - h * 0.02f)
        quadraticTo(cx, cy + h * 0.18f, cx + dir * len * 0.48f, cy + h * 0.02f)
        close()
    }
    drawPath(bellyPath, belly)
    // 背鳍：顶部一个后掠三角形。
    val dorsalPath = Path().apply {
        val dx0 = cx - dir * len * 0.05f
        moveTo(dx0, cy - h * 0.40f)
        lineTo(dx0 - dir * len * 0.10f, cy - h * 1.05f)
        lineTo(dx0 - dir * len * 0.22f, cy - h * 0.40f)
        close()
    }
    drawPath(dorsalPath, body)
    // 胸鳍：下方一个后掠小三角。
    val finPath = Path().apply {
        val fx0 = cx + dir * len * 0.05f
        moveTo(fx0, cy + h * 0.35f)
        lineTo(fx0 - dir * len * 0.12f, cy + h * 0.85f)
        lineTo(fx0 - dir * len * 0.20f, cy + h * 0.35f)
        close()
    }
    drawPath(finPath, body)
    // 眼睛：小黑点（吻部后上方）。
    drawCircle(
        color = Color.Black.copy(alpha = (0.85f * a).coerceIn(0f, 1f)),
        radius = 0.8.dp.toPx(),
        center = Offset(cx + dir * len * 0.32f, cy - h * 0.12f),
    )
    // 嘴：吻部下方一道短弧线。
    drawArc(
        color = Color.White.copy(alpha = (0.9f * a).coerceIn(0f, 1f)),
        startAngle = 30f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(cx + dir * len * 0.28f - len * 0.06f, cy + h * 0.02f),
        size = Size(len * 0.12f, h * 0.28f),
        style = Stroke(width = 0.8.dp.toPx(), cap = StrokeCap.Round),
    )
}

/**
 * 金鱼：一条小金鱼（椭圆身 + 扇形尾 + 小黑眼），在胶囊里左右游来游去——
 * 30 秒一个来回：x 在 0.15–0.85 之间正弦游动，到边掉头（朝向跟着速度方向翻），
 * 尾巴左右摆（约 5 次/秒轻摆）、身体上下轻浮；静态时停在中央、尾巴不摆。
 * 字盖在上面，金鱼在字下面游。
 */
private fun DrawScope.drawDecoGoldfish(items: List<DecoItem>, t: Float, animated: Boolean, f: Float) {
    for (it in items) {
        val phase = it.phase * 2f * PI.toFloat()
        val swim = if (animated) sin(t * 2f * PI.toFloat() + phase) else 0f
        val cx = size.width * (0.50f + 0.35f * swim)
        val bob = if (animated) sin(t * 4f * PI.toFloat() + phase) * size.height * 0.08f else 0f
        val cy = size.height * it.y + bob
        // 朝向：速度方向（cos>0 向右游，cos<0 向左游）；静态时朝右。
        val dir = if (!animated) 1f else if (cos(t * 2f * PI.toFloat() + phase) >= 0f) 1f else -1f
        val len = it.sizeDp.dp.toPx()
        val a = (0.85f * f * it.alphaFactor).coerceIn(0f, 1f)
        if (a <= 0f) continue
        // 大鲨鱼（colorArgb = 1）：灰蓝纺锤身 + 背鳍 + 竖新月尾鳍，跟金鱼一起游。
        if (it.colorArgb == 1) {
            val wag = if (animated) sin(t * 10f * PI.toFloat() + phase) * 0.45f else 0f
            drawDecoShark(cx, cy, len, dir, wag, a)
            continue
        }
        // 尾巴：扇形三角，尾尖左右摆（摆角 ±25°，约 5 次/秒）。
        val wag = if (animated) sin(t * 10f * PI.toFloat() + phase) * 0.45f else 0f
        val tailLen = len * 0.55f
        val tailBaseX = cx - dir * len * 0.32f
        val tailTipX = tailBaseX - dir * tailLen * cos(wag)
        val tailTipY = cy + tailLen * sin(wag)
        val tailPath = Path().apply {
            moveTo(tailBaseX, cy - len * 0.18f)
            lineTo(tailTipX, tailTipY)
            lineTo(tailBaseX, cy + len * 0.18f)
            close()
        }
        drawPath(tailPath, goldfishTailColor.copy(alpha = a))
        // 身体：椭圆。
        drawOval(
            color = goldfishBodyColor.copy(alpha = a),
            topLeft = Offset(cx - len * 0.5f, cy - len * 0.22f),
            size = Size(len, len * 0.44f),
        )
        // 眼睛：小黑点（朝向前方）。
        drawCircle(
            color = Color.Black.copy(alpha = (0.8f * a).coerceIn(0f, 1f)),
            radius = 0.6.dp.toPx(),
            center = Offset(cx + dir * len * 0.28f, cy - len * 0.06f),
        )
    }
}

/**
 * 水光高光层（3.11 新增）：表面顶部一道亮面（白渐变，上亮下透明），
 * 强度 = fraction 0..2（= waterGloss / 200，3.25 上限由 1.0 放到 2.0）。只在随心所欲开时挂——
 * 原样式自带水体（drawOriginal 路），不叠加；0 = 不画（纯平）。
 * 画在内容上面、形状裁剪内：clip 在 drawWithContent 外层，亮面不出框；
 * 文字居中在下半，亮面只盖上半，字不糊。
 * 卡片组（海报）与胶囊组都走这里（3.17 轻透：淡底 + 顶部亮面）；水滴实色底已删。
 */
fun Modifier.waterGlossSheen(shape: Shape, fraction: Float): Modifier {
    if (fraction <= 0f) return this
    return this
        .clip(shape)
        .drawWithContent {
            drawContent()
            // 3.25：fraction 上限放开到 2.0（水光 400 时 f=2.0）；1.0 以下与原来一样。
            val topAlpha = 0.45f * fraction.coerceIn(0f, 2f)
            if (topAlpha > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = topAlpha),
                            Color.White.copy(alpha = 0f),
                        ),
                        startY = 0f,
                        endY = size.height * 0.5f,
                    ),
                    size = Size(size.width, size.height * 0.5f),
                )
            }
        }
}

/**
 * 轻透样式专用（3.43，来自用户补丁）：一层薄雾蒙皮，不画玻璃穹顶、不画底边亮边、
 * 不画内壁。调用方仍画 15% 淡底或选中实色底；字画在最后，不糊字。
 */
fun Modifier.lightCapsuleSkin(shape: Shape, fraction: Float): Modifier =
    this.clip(shape).drawWithContent {
        val f = fraction.coerceIn(0f, 2f)
        if (f > 0f && size.width > 0f && size.height > 0f) {
            val sheen = (0.04f + 0.11f * f).coerceAtMost(0.26f)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = sheen),
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = size.height * 0.6f,
                ), size = Size(size.width, size.height * 0.6f),
            )
        }
        drawContent()
    }

/**
 * 1.14：深凹 / 微立体样式已删，depth 改成连续的槽强度 0..1（1 = 满槽纽扣，0 = 平板底）。
 * 画法沿用 3.44 的纽扣（单层实色内凹面，顶部暗壁、左右浅壁、底部受光薄边），
 * 槽的唇高和影子透明度都跟着 depth 走，拖水光滑杆时槽由深变浅是连续的；
 * fraction 不用（影子透明度是定值、不跟水光走）。
 */
fun Modifier.raisedCapsuleGloss(shape: Shape, baseColor: Color, fraction: Float, depth: Float = 1f): Modifier {
    return this.clip(shape).drawBehind {
        val h = size.height
        val w = size.width
        if (h <= 0f || w <= 0f) return@drawBehind
        val d = depth.coerceIn(0f, 1f)
        val dark = Color(baseColor.red * 0.46f, baseColor.green * 0.46f, (baseColor.blue * 0.51f).coerceAtMost(1f))
        drawRect(baseColor)
        if (d <= 0f) return@drawBehind
        val lip = (3.5.dp.toPx() * d).coerceAtMost(h * 0.16f)
        if (lip <= 0f) return@drawBehind
        drawRect(Brush.verticalGradient(
            0f to dark.copy(alpha = 0.90f * d),
            0.36f to dark.copy(alpha = 0.38f * d),
            1f to Color.Transparent,
            startY = 0f, endY = lip * 2.4f,
        ), size = Size(w, lip * 2.4f))
        val side = lip * 1.15f
        drawRect(Brush.horizontalGradient(listOf(dark.copy(alpha = 0.34f * d), Color.Transparent), startX = 0f, endX = side), size = Size(side, h))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, dark.copy(alpha = 0.22f * d)), startX = w - side, endX = w), topLeft = Offset(w - side, 0f), size = Size(side, h))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.46f * d)), startY = h - lip, endY = h), topLeft = Offset(0f, h - lip), size = Size(w, lip))
    }
}

/**
 * 1.14：胶囊风格选项已删，水光 0-400 一根滑杆走四段（四处调用点统一走这里，不再按风格分支）：
 * 0-50 纽扣（槽由深变浅）、50-100 轻透（底由实变淡、亮面由弱变强）、
 * 100-150 立体（底由淡变实、槽由浅出）、150-400 水滴（水光由弱到满）。
 * 各段都自己画底，调用方容器色改透明；交界处参数连续，防跳变。
 * 立体段的槽是纽扣槽的浅版（不再是原来微立体那两条 1dp 细线）。
 */
@Composable
fun Modifier.capsuleGlossByWater(shape: Shape, baseColor: Color, waterGloss: Int, seed: Int = 0): Modifier {
    val g = waterGloss.coerceIn(0, 400)
    return when {
        g < 50 -> {
            // 纽扣：槽强度 1 → 0，连续变浅。
            raisedCapsuleGloss(shape, baseColor, 0f, 1f - g / 50f)
        }
        g < 100 -> {
            // 轻透：底 1.0 → 0.06 变淡，亮面 f 0.4 → 2.0 变强（50 处底仍实色，接住纽扣段）。
            val t = (g - 50) / 50f
            background(baseColor.copy(alpha = 1f - 0.94f * t), shape)
                .then(lightCapsuleSkin(shape, 0.4f + 1.6f * t))
        }
        g < 150 -> {
            // 立体：底 0.06 → 1.0 变实，槽 0 → 0.22 由浅出（100 处底仍淡，接住轻透段）。
            val t = (g - 100) / 50f
            raisedCapsuleGloss(shape, baseColor.copy(alpha = 0.06f + 0.94f * t), 0f, t * 0.22f)
        }
        else -> {
            // 水滴：f 0 → 2.0；f=0 时只画实色底，防透明。
            val f = (g - 150) / 250f * 2f
            background(baseColor, shape)
                .then(if (f > 0f) waterDropGloss(shape, baseColor, f, seed) else Modifier)
        }
    }
}

/**
 * 凹的槽没有外影（3.44）：预览 groove2 明确“没有外影”，外阴影会读成浮起、不是凹槽。
 * 保留函数名供调用方挂接，实际不画。
 */
fun Modifier.concaveButtonShadow(shape: Shape): Modifier = this
