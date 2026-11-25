package com.github.tvbox.osc.music

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// 封面取色内置双主题的颜色数学（2.90）：HSL 互转、WCAG 相对亮度/对比度、
// 沿亮度轴求解对比度的 adjustLightnessForContrast。对标 Folia 的
// src/utils/themeColorMath.ts，数值与公式逐项照抄。

data class Hsl(val h: Double, val s: Double, val l: Double)

fun clamp01(value: Double): Double = max(0.0, min(1.0, value))

fun normalizeHue(hue: Double): Double = ((hue % 360.0) + 360.0) % 360.0

private fun clampChannel(value: Double): Int = max(0, min(255, value.roundToInt()))

fun argbToHsl(argb: Int): Hsl {
    val red = ((argb shr 16) and 0xFF) / 255.0
    val green = ((argb shr 8) and 0xFF) / 255.0
    val blue = (argb and 0xFF) / 255.0
    val maxC = max(red, max(green, blue))
    val minC = min(red, min(green, blue))
    val delta = maxC - minC
    val lightness = (maxC + minC) / 2.0
    if (delta == 0.0) {
        return Hsl(0.0, 0.0, lightness)
    }
    val saturation = delta / (1.0 - abs(2.0 * lightness - 1.0))
    val hue = when {
        maxC == red -> ((green - blue) / delta) % 6.0
        maxC == green -> (blue - red) / delta + 2.0
        else -> (red - green) / delta + 4.0
    }
    return Hsl(normalizeHue(hue * 60.0), clamp01(saturation), clamp01(lightness))
}

fun hslToArgb(hsl: Hsl): Int {
    val hue = normalizeHue(hsl.h)
    val saturation = clamp01(hsl.s)
    val lightness = clamp01(hsl.l)
    val chroma = (1.0 - abs(2.0 * lightness - 1.0)) * saturation
    val secondary = chroma * (1.0 - abs(((hue / 60.0) % 2.0) - 1.0))
    val offset = lightness - chroma / 2.0
    val sector = (hue / 60.0).toInt() % 6
    val (r, g, b) = when (sector) {
        0 -> Triple(chroma, secondary, 0.0)
        1 -> Triple(secondary, chroma, 0.0)
        2 -> Triple(0.0, chroma, secondary)
        3 -> Triple(0.0, secondary, chroma)
        4 -> Triple(secondary, 0.0, chroma)
        else -> Triple(chroma, 0.0, secondary)
    }
    return (0xFF shl 24) or
        (clampChannel((r + offset) * 255.0) shl 16) or
        (clampChannel((g + offset) * 255.0) shl 8) or
        clampChannel((b + offset) * 255.0)
}

fun relativeLuminance(argb: Int): Double {
    fun transform(channel: Int): Double {
        val normalized = channel / 255.0
        return if (normalized <= 0.03928) {
            normalized / 12.92
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4)
        }
    }
    return 0.2126 * transform((argb shr 16) and 0xFF) +
        0.7152 * transform((argb shr 8) and 0xFF) +
        0.0722 * transform(argb and 0xFF)
}

fun contrastRatio(foreground: Int, background: Int): Double {
    val luminanceA = relativeLuminance(foreground)
    val luminanceB = relativeLuminance(background)
    val lighter = max(luminanceA, luminanceB)
    val darker = min(luminanceA, luminanceB)
    return (lighter + 0.05) / (darker + 0.05)
}

fun hueDistance(a: Double, b: Double): Double {
    val distance = abs(normalizeHue(a) - normalizeHue(b))
    return min(distance, 360.0 - distance)
}

// 沿最短弧在 baseHue 与 accentHue 之间插值，插值量 0..1。
fun lerpHue(from: Double, to: Double, amount: Double): Double {
    val delta = ((to - from + 540.0) % 360.0) - 180.0
    return normalizeHue(from + delta * amount)
}

// 沿亮度轴推颜色直到对比度达标，保持色相与饱和度不变；步进 0.02、
// 最多 50 步；达不到返回遍历中对比度最高的候选。方向：背景相对亮度
// > 0.35 则往暗走，否则往亮走。
fun adjustLightnessForContrast(
    color: Int,
    background: Int,
    minRatio: Double,
    step: Double = 0.02,
    maxSteps: Int = 50,
): Int {
    val hsl = argbToHsl(color)
    val goDarker = relativeLuminance(background) > 0.35
    val direction = if (goDarker) -1.0 else 1.0
    var bestColor = hslToArgb(hsl)
    var bestRatio = contrastRatio(bestColor, background)
    for (index in 0..maxSteps) {
        val lightness = clamp01(hsl.l + direction * step * index)
        val candidate = hslToArgb(hsl.copy(l = lightness))
        val ratio = contrastRatio(candidate, background)
        if (ratio > bestRatio) {
            bestRatio = ratio
            bestColor = candidate
        }
        if (ratio >= minRatio) {
            return candidate
        }
        if (index > 0 && (lightness == 0.0 || lightness == 1.0)) {
            break
        }
    }
    return bestColor
}
