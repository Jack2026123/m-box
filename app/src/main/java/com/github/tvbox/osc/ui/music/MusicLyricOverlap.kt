package com.github.tvbox.osc.ui.music

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// 2.90 第 3 项：散落字防重叠几何——Folia glowWordOverlap.ts 的 Kotlin 移植。
// 旋转包围盒（AABB 半宽/半高）+ 相邻词间距 + 单词溢出 + 密度梯子；布局时（slotsFor 内）检查，
// 不在绘制时检查。单位：AVBox 用 px（Folia 的单位是“任意同一单位”）。

/** GlowWord 的行高（em），溢出量计算用。 */
const val GLOW_WORD_LINE_HEIGHT = 1.22f

/** 已唱过状态慢转过程中的采样点数（含两端共 9 个采样点）。 */
const val PASSED_TURN_SAMPLES = 8

/** 密度梯子：与 Folia CLASSIC_DENSITY_LADDER 逐值相同；一档都放不下用最小档（宁可小也不叠）。 */
val DENSITY_LADDER = floatArrayOf(1f, 0.9f, 0.8f, 0.72f, 0.65f, 0.58f)

/**
 * 词与词之间至少留的空隙（plan 建议 4dp）。slotsFor 无 Density 时用此近似值（4dp @ xxhdpi ≈ 12px）；
 * composable 里有 Density 时按 4.dp.toPx() 精确换算后传入。
 */
const val OVERLAP_MIN_GAP_PX = 12f

/** 一个词的 transform：scale（AVBox 无 active 放大，恒为 1f）、旋转角、唱过后的慢转角（AVBox 为 0）。 */
data class WordPose(val scale: Float = 1f, val rotateDeg: Float, val passedRotateDeg: Float = 0f)

/** 已布局的词：实测宽高（px，宽已含 1.08 粗体安全系数）、pose、包围盒中心（px）、x 偏移（中心 − 格心）。 */
data class PlacedWord(
    val w: Float,
    val h: Float,
    val pose: WordPose,
    val centerX: Float,
    val centerY: Float,
    val offsetX: Float,
)

/** 一个词画出来伸出自己排版框四边的量（永不为负）。 */
data class GlowWordOverflow(val left: Float, val right: Float, val top: Float, val bottom: Float)

/** reachAt：旋转矩形的轴对齐包围盒半宽/半高。θ=0 时退化为 w/2、h/2；θ=90° 时宽高互换。 */
fun reachAt(width: Float, glyphHeight: Float, scale: Float, rotateDeg: Float): Pair<Float, Float> {
    val radians = Math.toRadians(rotateDeg.toDouble()).toFloat()
    val cos = abs(cos(radians))
    val sin = abs(sin(radians))
    val halfWidth = (scale * (width * cos + glyphHeight * sin)) / 2f
    val halfHeight = (scale * (glyphHeight * cos + width * sin)) / 2f
    return halfWidth to halfHeight
}

/**
 * getGlowWordReach：active 态与 passed 慢转 9 采样点并集的最大包围盒。
 * 注意：Folia 原文 active 分支是 pose.scale × 1.4（active 态再放大）；AVBox 没有 active 放大效果，
 * 按 plan 04 §5 问题 4 的选项 A（推荐）不乘 1.4，按实际尺寸算——将来辉光包络引入 active 放大时再同步。
 * passedRotate 在 AVBox 为 0，9 点采样退化成重复算同一值，仍保留循环保与 Folia 同构。
 */
fun glowWordReach(width: Float, glyphHeight: Float, pose: WordPose): Pair<Float, Float> {
    var (halfWidth, halfHeight) = reachAt(width, glyphHeight, pose.scale, pose.rotateDeg)
    for (step in 0..PASSED_TURN_SAMPLES) {
        val (w, h) = reachAt(
            width, glyphHeight, pose.scale,
            pose.rotateDeg + pose.passedRotateDeg * step / PASSED_TURN_SAMPLES,
        )
        halfWidth = maxOf(halfWidth, w)
        halfHeight = maxOf(halfHeight, h)
    }
    return halfWidth to halfHeight
}

/**
 * getGlowWordPairGap：两词排版框之间需要的间距；负值表示间距为 0 也碰不到。
 * 只算水平方向（LTR；AVBox 散落布局无 RTL）。
 */
fun glowWordPairGap(first: PlacedWord, second: PlacedWord): Float {
    val (firstHalfW, _) = glowWordReach(first.w, first.h, first.pose)
    val (secondHalfW, _) = glowWordReach(second.w, second.h, second.pose)
    val towardEachOther = first.offsetX - second.offsetX
    return (firstHalfW - first.w / 2f) + (secondHalfW - second.w / 2f) + towardEachOther
}

/** getGlowWordOverflow：词画出来伸出自己排版框四边的量（带自身 x/y 偏移修正，永不为负）。 */
fun glowWordOverflow(
    width: Float,
    fontSize: Float,
    pose: WordPose,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
): GlowWordOverflow {
    val (halfWidth, halfHeight) = glowWordReach(width, fontSize, pose)
    val overWidth = halfWidth - width / 2f
    val overHeight = halfHeight - (fontSize * GLOW_WORD_LINE_HEIGHT) / 2f
    return GlowWordOverflow(
        left = maxOf(0f, overWidth - offsetX),
        right = maxOf(0f, overWidth + offsetX),
        top = maxOf(0f, overHeight - offsetY),
        bottom = maxOf(0f, overHeight + offsetY),
    )
}

/**
 * 两词轴对齐包围盒（含 minGap 膨胀）的相交面积；不相交为 0。
 * 布局时用：候选格的总重叠 = 与已放词的相交面积之和；为 0 即零重叠。
 */
fun aabbOverlapArea(
    cx: Float,
    cy: Float,
    halfW: Float,
    halfH: Float,
    other: PlacedWord,
    minGap: Float,
): Float {
    val (otherHalfW, otherHalfH) = glowWordReach(other.w, other.h, other.pose)
    val overlapX = halfW + otherHalfW + minGap - abs(cx - other.centerX)
    val overlapY = halfH + otherHalfH + minGap - abs(cy - other.centerY)
    return if (overlapX > 0f && overlapY > 0f) overlapX * overlapY else 0f
}
