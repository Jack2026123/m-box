package com.github.tvbox.osc.ui.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private data class LyricUnit(val text: String, val startMs: Long, val endMs: Long)

private data class Slot(val xFrac: Float, val yFrac: Float, val sizeSp: Float, val rotDeg: Float)

// 2.90 第 3 项：measureCache 的 key（含 template，切模板不残留旧测量；密度已由 sizeSp 区分）。
private data class MeasureKey(val text: String, val sizeSp: Float, val bold: Boolean, val template: LyricVisualTemplate)

// 2.90 第 3 项：每词抽一次的布局参数（密度梯子各档共用）。
private data class WordDraw(val sizeSp: Float, val rotDeg: Float, val jitterX: Float, val jitterY: Float)

@Composable
fun MusicLyricVisualizer(
    lines: List<LyricLine>,
    positionMs: Long,
    durationMs: Long,
    seedArgb: Int?,
    themeRoll: Int,
    playing: Boolean,
    wordColors: Map<String, Int> = emptyMap(),
    textColorMode: Int = 0,
    // 2.90：辉光包络档位——0 自动（按行时长选档）、1 柔和 normal、2 快速 fast、3 脉冲 instant
    glowMode: Int = 0,
    // 2.90 第 5 项 5A：词级动效模板——默认流光（2.80 现状原样）；songKey 供辉光/种子相关逻辑用（Item 4）
    template: LyricVisualTemplate = LyricVisualTemplate.LiuGuang,
    songKey: String = "",
    modifier: Modifier = Modifier,
) {
    // 平滑时钟：播放器位置每 400ms 才推一次，扫字填充要逐帧走，所以在两次 tick 之间
    // 用帧时钟自己往前推；暂停时停住，切歌/Seek 时按 positionMs 重锚。
    var anchorPos by remember { mutableStateOf(positionMs) }
    var anchorNanos by remember { mutableStateOf(0L) }
    var displayPos by remember { mutableStateOf(positionMs) }
    LaunchedEffect(positionMs) {
        // 400ms 不需要跳：常规 tick 不按回位置，让帧时钟自己走顺；
        // 只有大跳变（切歌/拖动/校准，偏差 > 1000ms）才重锚。
        if (kotlin.math.abs(positionMs - displayPos) > 1000L) {
            anchorPos = positionMs
            anchorNanos = System.nanoTime()
            displayPos = positionMs
        }
    }
    LaunchedEffect(playing) {
        anchorNanos = 0L
        anchorPos = positionMs
        displayPos = positionMs
        if (!playing) return@LaunchedEffect
        while (true) {
            withFrameNanos { nanos ->
                if (anchorNanos == 0L) {
                    anchorNanos = nanos
                    anchorPos = positionMs
                }
                displayPos = anchorPos + (nanos - anchorNanos) / 1_000_000
            }
        }
    }
    val current = remember(lines, displayPos) { lyricIndex(lines, displayPos) }
    val line = lines.getOrNull(current)
    val nextStart = lines.getOrNull(current + 1)?.timeMs ?: durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
    val units = remember(line, nextStart) {
        if (line == null) emptyList() else unitsFor(line, nextStart)
    }
    // 3.01 换行位移进度：行开始后 400ms 内从 0 到 1（easeOut），供云阶/空间画布/商籁的切行位移动画；
    // 行内进度 0..1 供气泡框沿小气泡走；暂停时 displayPos 停住、进度停住；拖动/切歌/校准后 displayPos 重锚、进度立即重算。
    val lineStartMs = line?.timeMs ?: displayPos
    val lineAgeMs = (displayPos - lineStartMs).coerceAtLeast(0L)
    val transitionT = easeOutCubic((lineAgeMs / 400f).coerceIn(0f, 1f))
    val lineDurationMs = (nextStart - lineStartMs).coerceAtLeast(1L)
    val lineProgress = ((displayPos - lineStartMs).toFloat() / lineDurationMs).coerceIn(0f, 1f)
    val primary = MaterialTheme.colorScheme.primary
    // 2.83：动效字体颜色——0 自动（背景浅用黑字、深用白字）、1 白、2 黑、3 主题色
    // 2.85：之前只改了没唱到的底字，唱到的部分还是主色——用户设了黑色，唱到的字还是白色（主色浅），等于没生效。现在唱到的部分也跟设置走。
    val surfaceLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val baseColor = when (textColorMode) {
        1 -> Color.White.copy(alpha = 0.55f)
        2 -> Color.Black.copy(alpha = 0.7f)
        3 -> primary.copy(alpha = 0.55f)
        // 2.90 5A：自动档由模板定——霓虹淡青白 0.55；其余浅底黑（流光保持 0.7 原样）、深底白取模板 baseAlpha（流光 0.55 / 青澜 0.55 / 星图 0.50）；雾笺（2.99）深浅底都只用 baseAlpha 0.42（低对比柔和）
        else -> template.templateBaseColor(surfaceLight)
            ?: if (surfaceLight) Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = template.baseAlpha)
    }
    val textMeasurer = rememberTextMeasurer()
    val measureCache = remember(line, themeRoll) { mutableMapOf<MeasureKey, TextLayoutResult>() }
    // 2.90 第 3 项：确定性种子由 songKey 派生——同一首歌每次打开布局都一样，不再每次重抽 themeRoll。
    val baseSeed = hashVisualizerSeed(songKey)
    // 2.90 第 3 项：先量后排的量取 lambda，复用 measureCache，返回 px 尺寸。
    val measureWord: (String, Float, Boolean) -> Size = { text, sizeSp, bold ->
        val result = measureCache.getOrPut(MeasureKey(text, sizeSp, bold, template)) {
            textMeasurer.measure(
                text,
                TextStyle(fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            )
        }
        Size(result.size.width.toFloat(), result.size.height.toFloat())
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // 2.90 第 3 项：布局在框内算——重叠检查要 px 尺寸，需真实框宽高；minGap 4dp 精确换算。
        val minGapPx = with(density) { 4.dp.toPx() }
        // 2.99 行版式：内容区（px）——各版式词排区不同（气泡左右交替、町景居中收窄、其余全框）；行版式走横排词流，不走散词格子
        val isLineLayout = template.layout != "scatter"
        val contentRect = remember(line, current, template, widthPx, heightPx) {
            lineContentRect(template, current, widthPx, heightPx)
        }
        val slots = remember(line, units.size, baseSeed, template) {
            if (isLineLayout) {
                lineSlotsFor(current, units, template, measureWord, widthPx, heightPx, contentRect, density)
            } else {
                slotsFor(current, units, baseSeed, template, measureWord, widthPx, heightPx, minGapPx)
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 2.90 5A：霓虹先铺近黑底色洗白（其余模板 backgroundWash 为 null，不盖 surface）
            template.backgroundWash?.let { drawRect(it) }
            drawBlobs(seedArgb, themeRoll, size.width, size.height, template, displayPos / 1000f, baseSeed)
            // 3.01 后 8 款新背景（去圆之后）：每款各一个非圆背景动效，画在文字后面；前 4 款不动（色块圆还在）
            if (template.id in 7..14) {
                drawTemplateBackground(template, size.width, size.height, displayPos / 1000f, seedArgb, primary, surfaceLight, baseSeed)
            }
            // 2.99：歌词区不再画舞台光束——4 款舞台全改全屏 3D Dialog，选中即进全屏；spotlightColors 只给全屏舞台用
            if (template.halftone) drawHalftone(template, baseSeed, size.width, size.height, displayPos / 1000f)
            if (line != null) drawDecorations(current, baseSeed, size.width, size.height, template)
            // 3.01 行版式装饰：只剩景深上下行淡字与商籁旁注（歌词排版本身，不是背景方框）；其余版式无装饰
            if (isLineLayout) {
                drawLayoutDecorations(template, current, lines, size.width, size.height, contentRect, displayPos / 1000f, seedArgb, primary, surfaceLight, textMeasurer, transitionT, lineProgress)
            }
            // 3.01 词前特效（画在文字后面、词前面）：星图星点连线 / 青澜水波 / 雾笺局部清亮——先算词框（不加粗量取，装饰用近似位置即可）
            val wordRects = wordRectsFor(units, slots, measureWord, widthPx, heightPx)
            if (template.id == 2) drawStarMap(units, wordRects, displayPos)
            if (template.id == 1) drawWordRipples(units, wordRects, displayPos)
            if (template.id == 12) drawMistClear(units, wordRects, displayPos)
            // 2.90：辉光包络档位按行解析一次——自动时行时长 <0.10s 脉冲、<0.18s 快速、其余柔和（Folia renderHints 规则）
            val resolvedGlowMode = resolveGlowMode(glowMode, if (line != null) (nextStart - line.timeMs).coerceAtLeast(0L) else 0L)
            // 3.01 换行位移（切行时整行词一起动，400ms 内到位）：云阶上滑一阶、空间画布由远推近、商籁新页从右滑入（胶片逐格已随胶片换成四季而去掉）
            val lineShiftYPx = when (template.id) {
                8 -> (1f - transitionT) * 40.dp.toPx()
                else -> 0f
            }
            val lineShiftXPx = when (template.id) {
                14 -> (1f - transitionT) * 40.dp.toPx()
                else -> 0f
            }
            val lineExtraScale = when (template.id) {
                9 -> 0.95f + 0.05f * transitionT
                14 -> 0.96f + 0.04f * transitionT
                else -> 1f
            }
            units.forEachIndexed { i, unit ->
                val slot = slots.getOrNull(i) ?: return@forEachIndexed
                val sizeSp = slot.sizeSp * sizeScale(unit.text.length)
                val fill = fillFraction(displayPos, unit)
                // 2.90：逐词辉光包络（Folia getClassicGlowEnvelope）+ 当前唱到的词微脉冲 ±4%
                val glow = classicGlowEnvelope(displayPos, unit.startMs, unit.endMs, nextStart, resolvedGlowMode)
                val pulse = if (fill > 0f && fill < 1f) {
                    1f + sin(displayPos / 1000f * 10f + unit.startMs / 1000f * 5f) * 0.04f
                } else {
                    1f
                }
                // 2.90 5A：AI wordColors 只在流光生效；其余模板自动档由模板填色定（青澜绿/粉交替、星图金/白、霓虹五色轮换）
                val unitColor = if (template.id == 0) wordColorFor(unit.text, wordColors) ?: primary else primary
                // 2.85：唱到的字也跟字体颜色设置走——手动选了白/黑/主题色就用那个色；自动时用词色/主色，但跟背景撞色（都浅或都深）就改用自动底色，保证看得清
                // 3.01：霓虹七色按行轮换（传当前行号，同行一色），其余模板不变
                val fillColor = when (textColorMode) {
                    1 -> Color.White.copy(alpha = 0.95f)
                    2 -> Color.Black.copy(alpha = 0.95f)
                    3 -> primary.copy(alpha = 0.95f)
                    else -> template.templateFillColor(i, surfaceLight, current)
                        ?: if (surfaceLight == (unitColor.luminance() > 0.5f)) baseColor.copy(alpha = 0.95f) else unitColor.copy(alpha = 0.95f)
                }
                // 2.90 5A：fillBold 的模板（星图/霓虹）已唱字全程 Bold，不只唱到一半时加粗
                val bold = if (template.fillBold) fill > 0f else (fill > 0f && fill < 1f)
                val style = TextStyle(
                    fontSize = sizeSp.sp,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                )
                val measured = measureCache.getOrPut(MeasureKey(unit.text, sizeSp, bold, template)) {
                    textMeasurer.measure(unit.text, style)
                }
                val w = measured.size.width.toFloat()
                val h = measured.size.height.toFloat()
                // 2.82：字别画出框——按实测宽高把左上角钳回框内，旋转也不顶出去
                val x = (slot.xFrac * widthPx).coerceIn(0f, (widthPx - w).coerceAtLeast(0f))
                val y = (slot.yFrac * heightPx).coerceIn(0f, (heightPx - h).coerceAtLeast(0f))
                // 2.90：微脉冲整体缩放包在旋转外面，不改字号、不走 measureCache；辉光是独立第三遍，画在锐利底字/填充字下面，字本身永远 shadow = null
                // 2.99 行版式：气泡当前行放大 1.08 倍（乘在微脉冲外面）
                // 3.01：换行位移（云阶上滑、商籁右滑入）与纵深推拉（空间画布）乘在这里；词位移加到 drawX/drawY 上
                val layoutScale = if (template.layout == "bubble") 1.08f else 1f
                val drawX = x + lineShiftXPx
                val drawY = y + lineShiftYPx
                scale(pulse * layoutScale * lineExtraScale, pulse * layoutScale * lineExtraScale, pivot = Offset(drawX + w / 2f, drawY + h / 2f)) {
                    rotate(slot.rotDeg, pivot = Offset(drawX + w / 2f, drawY + h / 2f)) {
                        when (template.id) {
                            // 霓虹（3.01）：整词点亮——底字始终画，霓虹色整词淡入（不左右扫）；经过时短促辉光，唱完回稳定亮度
                            3 -> {
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = unit.text,
                                    topLeft = Offset(drawX, drawY),
                                    style = style.copy(color = baseColor, shadow = null),
                                )
                                if (fill > 0f) {
                                    drawText(
                                        textMeasurer = textMeasurer,
                                        text = unit.text,
                                        topLeft = Offset(drawX, drawY),
                                        style = style.copy(color = fillColor.copy(alpha = fillColor.alpha * fill), shadow = null),
                                    )
                                    val sinceDone = displayPos - unit.endMs
                                    val flash = when {
                                        fill >= 1f && sinceDone in 0..300L -> (1f - sinceDone / 300f) * 0.7f
                                        fill > 0f && fill < 1f -> 0.4f
                                        else -> 0f
                                    }
                                    if (flash > 0.01f && template.glowColors.isNotEmpty()) {
                                        drawText(
                                            textMeasurer = textMeasurer,
                                            text = unit.text,
                                            topLeft = Offset(drawX, drawY),
                                            style = style.copy(
                                                color = fillColor.copy(alpha = flash),
                                                shadow = Shadow(fillColor, Offset.Zero, template.glowBlurPx),
                                            ),
                                        )
                                    }
                                }
                            }
                            // 町景（3.01）：分段通电——词分三段依次点亮（每段占 1/3 填色），段内渐亮，正在亮的段带辉光闪
                            13 -> {
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = unit.text,
                                    topLeft = Offset(drawX, drawY),
                                    style = style.copy(color = baseColor, shadow = null),
                                )
                                if (fill > 0f) {
                                    for (seg in 0 until 3) {
                                        val segFill = (fill * 3f - seg).coerceIn(0f, 1f)
                                        if (segFill <= 0f) continue
                                        val segLeft = drawX + w * seg / 3f
                                        val segRight = drawX + w * (seg + 1) / 3f
                                        if (segFill < 1f && template.glowColors.isNotEmpty()) {
                                            clipRect(left = segLeft, top = drawY, right = segRight, bottom = drawY + h) {
                                                drawText(
                                                    textMeasurer = textMeasurer,
                                                    text = unit.text,
                                                    topLeft = Offset(drawX, drawY),
                                                    style = style.copy(
                                                        color = fillColor.copy(alpha = 0.45f * sin(segFill * Math.PI.toFloat())),
                                                        shadow = Shadow(fillColor, Offset.Zero, template.glowBlurPx),
                                                    ),
                                                )
                                            }
                                        }
                                        clipRect(left = segLeft, top = drawY, right = segRight, bottom = drawY + h) {
                                            drawText(
                                                textMeasurer = textMeasurer,
                                                text = unit.text,
                                                topLeft = Offset(drawX, drawY),
                                                style = style.copy(color = fillColor.copy(alpha = fillColor.alpha * segFill), shadow = null),
                                            )
                                        }
                                    }
                                }
                            }
                            else -> {
                                if (fill > 0f && glow > 0.01f && template.glowColors.isNotEmpty()) {
                                    drawText(
                                        textMeasurer = textMeasurer,
                                        text = unit.text,
                                        topLeft = Offset(drawX, drawY),
                                        style = style.copy(
                                            color = fillColor.copy(alpha = glow),
                                            shadow = Shadow(fillColor, Offset.Zero, glow * template.glowBlurPx),
                                        ),
                                    )
                                }
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = unit.text,
                                    topLeft = Offset(drawX, drawY),
                                    style = style.copy(
                                        // 2.82：底字加深、去掉模糊影——没唱到的字也要看得清、看得锐
                                        // 2.83：底字颜色按动效字体颜色走（自动按背景深浅换黑/白），别再白字撞浅背景
                                        color = baseColor,
                                        shadow = null,
                                    ),
                                )
                                if (fill > 0f) {
                                    val fillW = w * fill
                                    clipRect(left = drawX, top = drawY, right = drawX + fillW, bottom = drawY + h) {
                                        drawText(
                                            textMeasurer = textMeasurer,
                                            text = unit.text,
                                            topLeft = Offset(drawX, drawY),
                                            style = style.copy(
                                                // 2.82：唱到的字也是实心主色、不带任何模糊影——字体不许模糊
                                                // 2.85：唱到的字跟字体颜色设置走（见 fillColor），别再白字撞浅背景
                                                color = fillColor,
                                                shadow = null,
                                            ),
                                        )
                                    }
                                    // 流光（3.01）：唱到的词前沿有一道柔光带，从左到右掠过（只在正在唱的词上），唱完留清晰主题色文字
                                    if (template.id == 0 && fill < 1f) {
                                        val bandW = 10.dp.toPx()
                                        val bandX = drawX + fillW - bandW / 2f
                                        drawRect(
                                            color = primary.copy(alpha = 0.25f),
                                            topLeft = Offset(bandX - bandW, drawY - 2.dp.toPx()),
                                            size = Size(bandW * 3f, h + 4.dp.toPx()),
                                        )
                                        drawRect(
                                            color = Color.White.copy(alpha = 0.55f),
                                            topLeft = Offset(bandX, drawY - 2.dp.toPx()),
                                            size = Size(bandW, h + 4.dp.toPx()),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fillFraction(nowMs: Long, unit: LyricUnit): Float {
    if (nowMs <= unit.startMs) return 0f
    if (nowMs >= unit.endMs) return 1f
    val span = (unit.endMs - unit.startMs).coerceAtLeast(1L)
    return ((nowMs - unit.startMs).toFloat() / span).coerceIn(0f, 1f)
}

// 2.90：逐词辉光三档包络——Folia cadenzaEnvelopes.ts 的 getClassicGlowEnvelope / getClassicKeyframedGlow / easeOutCubic 逐字移植，单位换算成 ms。
// 档位 Int：1 柔和 normal、2 快速 fast、3 脉冲 instant；0 只在 resolveGlowMode 里出现（调用前已按行时长解析成 1/2/3）。
private fun easeOutCubic(v: Float): Float = 1f - (1f - v.coerceIn(0f, 1f)).pow(3)

private fun mix(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount

private fun classicKeyframedGlow(progress: Float): Float {
    if (progress <= 0f || progress >= 1f) return 0f
    if (progress < 0.3f) return easeOutCubic(progress / 0.3f)
    return 1f - ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
}

// 自动选档（Folia renderHints 规则）：行时长 <0.10s 脉冲、<0.18s 快速、其余柔和；手动 1/2/3 直接生效
private fun resolveGlowMode(glowMode: Int, lineDurationMs: Long): Int {
    if (glowMode in 1..3) return glowMode
    return when {
        lineDurationMs < 100L -> 3
        lineDurationMs < 180L -> 2
        else -> 1
    }
}

private fun classicGlowEnvelope(nowMs: Long, startMs: Long, endMs: Long, lineEndMs: Long, mode: Int): Float {
    if (nowMs < startMs) return 0f
    return when (mode) {
        3 -> {
            // instant：词开始后 67ms 一次脉冲，行外为 0，无词后拖尾
            if (nowMs > lineEndMs) return 0f
            classicKeyframedGlow(((nowMs - startMs) / 67f).coerceIn(0f, 1f))
        }
        2 -> {
            // fast：最短词长 45ms；上升 0–14%、保持 14–82%、收尾 82–100% 线性落到 0.92；词后 0.14s 淡出
            val duration = (endMs - startMs).toFloat().coerceAtLeast(45f)
            if (nowMs <= endMs) {
                val p = ((nowMs - startMs).toFloat() / duration).coerceIn(0f, 1f)
                when {
                    p < 0.14f -> easeOutCubic(p / 0.14f)
                    p < 0.82f -> 1f
                    else -> mix(1f, 0.92f, (p - 0.82f) / 0.18f)
                }
            } else {
                // 2.90 修平 Folia 原文的 fast 档词尾跳变：词内结束 0.92，原文词后起点却是 (1-0)^2 = 1.0（+0.08 上跳）；改为 0.92 * (1-fadeOut)^2
                val fadeOut = ((nowMs - endMs) / 140f).coerceIn(0f, 1f)
                0.92f * (1f - fadeOut) * (1f - fadeOut)
            }
        }
        else -> {
            // normal：最短词长 100ms；上升 0–18%、保持 18–90%、收尾 90–100% 线性落到 0.9；词后 0.9s 淡出
            val duration = (endMs - startMs).toFloat().coerceAtLeast(100f)
            if (nowMs <= endMs) {
                val p = ((nowMs - startMs).toFloat() / duration).coerceIn(0f, 1f)
                when {
                    p < 0.18f -> easeOutCubic(p / 0.18f)
                    p < 0.9f -> 1f
                    else -> mix(1f, 0.9f, (p - 0.9f) / 0.1f)
                }
            } else {
                val fadeOut = ((nowMs - endMs) / 900f).coerceIn(0f, 1f)
                0.9f * (1f - fadeOut) * (1f - fadeOut)
            }
        }
    }
}

private fun wordColorFor(text: String, wordColors: Map<String, Int>): Color? {
    if (wordColors.isEmpty()) return null
    wordColors[text]?.let { return Color(it) }
    for ((word, argb) in wordColors) {
        if (word.contains(text) || text.contains(word)) return Color(argb)
    }
    return null
}

private fun sizeScale(len: Int): Float = (2f / len.coerceAtLeast(1)).coerceIn(0.45f, 1f)

private fun unitsFor(line: LyricLine, nextStartMs: Long): List<LyricUnit> {
    val end = nextStartMs
    if (line.words.size > 1) {
        return line.words.mapIndexed { i, w ->
            val s = w.timeMs
            val e = if (i + 1 < line.words.size) line.words[i + 1].timeMs else end
            LyricUnit(w.text, s, e.coerceAtLeast(s + 200))
        }
    }
    val tokens = tokenize(line.text)
    if (tokens.isEmpty()) return emptyList()
    val span = (end - line.timeMs).coerceAtLeast(tokens.size * 300L)
    val per = span / tokens.size
    return tokens.mapIndexed { i, tok ->
        LyricUnit(tok, line.timeMs + i * per, line.timeMs + (i + 1) * per)
    }
}

private fun isCjk(ch: Char): Boolean =
    ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' ||
        ch in '\u3040'..'\u30FF' || ch in '\uAC00'..'\uD7A3'

private fun isCjkPunct(ch: Char): Boolean =
    ch in '\u3000'..'\u303F' || ch in '\uFF00'..'\uFFEF'

private fun tokenize(text: String): List<String> {
    val out = ArrayList<String>()
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(buf.toString())
            buf.clear()
        }
    }
    for (ch in text) {
        when {
            ch.isWhitespace() -> flush()
            isCjk(ch) -> {
                flush()
                out.add(ch.toString())
            }
            isCjkPunct(ch) -> {
                if (out.isNotEmpty()) out[out.lastIndex] = out.last() + ch
                else buf.append(ch)
            }
            else -> buf.append(ch)
        }
    }
    flush()
    return out
}

// 2.90 第 3 项：先量后排 + 带重叠检查的格子分配 + 密度梯子。
// 量纲注意：格心是框分数（0..1），实测宽高是 px——AABB 检查必须在 px 下做，所以要框宽高；
// plan 04 的伪代码把分数中心与 px 半宽直接比较是量纲混用，这里用 boxWidthPx/boxHeightPx 换算。
// box 尺寸 <= 0（未传）时跳过检查，退化为现状的纯网格分配。
// 签名注：plan 定的 5 参之外多 3 个可选尾参（框宽高/minGap，都有缺省值）——不加就没法把 px 尺寸
// 换算成框分数，见上；5 参调用仍能编过，private 函数无外部调用点。
private fun slotsFor(
    lineIndex: Int,
    units: List<LyricUnit>,
    baseSeed: Int,
    template: LyricVisualTemplate,
    measure: (text: String, sizeSp: Float, bold: Boolean) -> androidx.compose.ui.geometry.Size,
    boxWidthPx: Float = 0f,
    boxHeightPx: Float = 0f,
    minGapPx: Float = OVERLAP_MIN_GAP_PX,
): List<Slot> {
    if (units.isEmpty()) return emptyList()
    val count = units.size
    val cols = when {
        count <= 9 -> 3
        count <= 16 -> 4
        else -> 5
    }
    val rows = (count + cols - 1) / cols
    val cells = ArrayList<Pair<Float, Float>>()
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            cells.add((c + 0.5f) / cols to (r + 0.5f) / rows)
        }
    }
    val shrink = (3f / rows).coerceAtMost(1f)
    // 确定性布局流：行号混进种子（沿用现状 lineIndex * 1000003），行与行互不相关；
    // 同一首歌每次打开同一布局（baseSeed 由 songKey 派生），切歌/换行才重算。
    val layoutRng = createVisualizerRng(mixVisualizerSeed(baseSeed + lineIndex * 1000003, LAYOUT_SALT))
    // Fisher-Yates 洗牌（mulberry32 流，不用 kotlin Random；Float 舍入到 1.0f 时钳回 i 以内）
    for (i in cells.size - 1 downTo 1) {
        val j = (layoutRng() * (i + 1)).toInt().coerceIn(0, i)
        val tmp = cells[i]
        cells[i] = cells[j]
        cells[j] = tmp
    }
    // 每词先抽尺寸/旋转/抖动（固定顺序，只抽一次，密度梯子各档共用，只缩放尺寸）
    val draws = units.map {
        WordDraw(
            sizeSp = (template.sizeMinSp + layoutRng() * (template.sizeMaxSp - template.sizeMinSp)) * shrink,
            rotDeg = (layoutRng() - 0.5f) * 2f * template.rotMaxDeg,
            jitterX = (layoutRng() - 0.5f) * 0.12f,
            jitterY = (layoutRng() - 0.5f) * 0.14f,
        )
    }
    val checkOverlap = boxWidthPx > 0f && boxHeightPx > 0f

    // 单档分配：逐词试格，零重叠格优先，否则取重叠最小的格（退化为现状行为）。
    fun attempt(density: Float): Pair<List<Slot>, Boolean> {
        val placed = ArrayList<PlacedWord>()
        val used = BooleanArray(cells.size)
        var hadFallback = false
        val slots = ArrayList<Slot>(count)
        for ((index, unit) in units.withIndex()) {
            val draw = draws[index]
            val sizeSp = draw.sizeSp * density
            // 先量后排：按最终字号（已乘 sizeScale）实测；粗体按常规量，宽 × 1.08 安全系数
            val finalSizeSp = sizeSp * sizeScale(unit.text.length)
            val measured = measure(unit.text, finalSizeSp, false)
            val wReal = measured.width
            val h = measured.height
            val wSafe = wReal * 1.08f
            val (halfW, halfH) = glowWordReach(wSafe, h, WordPose(rotateDeg = draw.rotDeg))
            if (!checkOverlap) {
                val (cx, cy) = cells[index % cells.size]
                slots.add(
                    Slot(
                        // 2.85：只留 0.02–0.98 的宽松边，别再压网格（框内兜底在画时按实测宽高钳）。
                        xFrac = (cx + draw.jitterX).coerceIn(0.02f, 0.98f),
                        yFrac = (cy + draw.jitterY).coerceIn(0.02f, 0.98f),
                        sizeSp = sizeSp,
                        rotDeg = draw.rotDeg,
                    )
                )
                continue
            }
            var chosen = -1
            var bestCell = -1
            var bestOverlap = Float.MAX_VALUE
            var bestXFrac = 0f
            var bestYFrac = 0f
            var bestCenterX = 0f
            var bestCenterY = 0f
            var bestCellX = 0f
            for (cellIndex in cells.indices) {
                if (used[cellIndex]) continue
                val (cellX, cellY) = cells[cellIndex]
                val xFrac = (cellX + draw.jitterX).coerceIn(0.02f, 0.98f)
                val yFrac = (cellY + draw.jitterY).coerceIn(0.02f, 0.98f)
                // 与画时同一钳制：左上角按实测宽高钳回框内，包围盒中心按实际落点算
                val xPx = (xFrac * boxWidthPx).coerceIn(0f, (boxWidthPx - wReal).coerceAtLeast(0f))
                val yPx = (yFrac * boxHeightPx).coerceIn(0f, (boxHeightPx - h).coerceAtLeast(0f))
                val cxPx = xPx + wReal / 2f
                val cyPx = yPx + h / 2f
                var overlap = 0f
                for (other in placed) {
                    overlap += aabbOverlapArea(cxPx, cyPx, halfW, halfH, other, minGapPx)
                }
                if (overlap <= 0f) {
                    chosen = cellIndex
                    bestXFrac = xFrac
                    bestYFrac = yFrac
                    bestCenterX = cxPx
                    bestCenterY = cyPx
                    bestCellX = cellX
                    break
                }
                if (overlap < bestOverlap) {
                    bestOverlap = overlap
                    bestCell = cellIndex
                    bestXFrac = xFrac
                    bestYFrac = yFrac
                    bestCenterX = cxPx
                    bestCenterY = cyPx
                    bestCellX = cellX
                }
            }
            val cellIndex = if (chosen >= 0) chosen else bestCell
            if (chosen < 0) hadFallback = true
            if (cellIndex < 0) {
                // 无可用格（理论上不会发生：格数 >= 词数），退化为现状行为取框中心
                slots.add(Slot(xFrac = 0.5f, yFrac = 0.5f, sizeSp = sizeSp, rotDeg = draw.rotDeg))
                continue
            }
            used[cellIndex] = true
            placed.add(
                PlacedWord(
                    w = wSafe,
                    h = h,
                    pose = WordPose(rotateDeg = draw.rotDeg),
                    centerX = bestCenterX,
                    centerY = bestCenterY,
                    offsetX = bestCenterX - bestCellX * boxWidthPx,
                )
            )
            slots.add(Slot(xFrac = bestXFrac, yFrac = bestYFrac, sizeSp = sizeSp, rotDeg = draw.rotDeg))
        }
        return slots to hadFallback
    }

    // 密度梯子：逐档试排，首个零重叠档即返回；一档都放不下用最小档 0.58（宁可小也不叠）。
    var lastSlots: List<Slot>? = null
    for (density in DENSITY_LADDER) {
        val (slots, hadFallback) = attempt(density)
        lastSlots = slots
        if (!hadFallback) return slots
    }
    return lastSlots ?: emptyList()
}

// 2.90 5A：色块圆心——drawBlobs 与 drawHalftone 共用同一确定性序列（mulberry32，位置不随重组跳）；
// 流动时圆心加正弦漂移，相位由 displayPos 派生（暂停自动停，切歌/Seek 重锚，零新增协程）。
private fun blobCenters(
    template: LyricVisualTemplate,
    baseSeed: Int,
    width: Float,
    height: Float,
    flowTimeSec: Float,
): List<Offset> {
    // 2.90 第 3 项：色块圆心走确定性种子流（baseSeed 派生）；每调一次都从头取同一序列。
    val blobsRng = createVisualizerRng(mixVisualizerSeed(baseSeed, BLOBS_SALT))
    // 3.01：圆心区域按模板走 template.blobZones（默认 2.82 三区左上/右下/中上；8 款新模板各配不同区域，位置不再全一样）
    val zones = template.blobZones
    val phase = flowTimeSec * (2f * Math.PI.toFloat()) / template.blobFlowPeriodSec
    return (0 until template.blobCount).map { i ->
        val zone = zones.getOrElse(i) { zones.last() }
        val dx = if (template.blobFlow) kotlin.math.sin(phase + i * 2.1f) * template.blobFlowAmplitude else 0f
        val dy = if (template.blobFlow) kotlin.math.cos(phase * 0.9f + i * 1.7f) * template.blobFlowAmplitude else 0f
        Offset(
            width * (zone[0] + blobsRng() * zone[1]) + dx * width,
            height * (zone[2] + blobsRng() * zone[3]) + dy * height,
        )
    }
}

// 2.90 5A：单团色块颜色——固定色相模板（青澜 160°）不跟 seed 色相走；其余按 blobHueShifts 平移 seed 色相。
private fun blobColor(
    template: LyricVisualTemplate,
    seedArgb: Int?,
    themeRoll: Int,
    index: Int,
): Color? {
    val shift = template.blobHueShifts.getOrElse(index) { 0f }
    val fixedHue = template.blobFixedHue
    if (fixedHue != null) {
        // 青澜每团在固定色相 ±15° 内抖一个确定值（不叠加 blobHueShifts，否则第三团会落到 220° 蓝色区，脱离青绿段 150–175°）
        val jitter = if (template.id == 1) (Random(themeRoll + index * 131).nextFloat() - 0.5f) * 30f else 0f
        val hue = (fixedHue + jitter + 360f) % 360f
        val (sat, value) = if (seedArgb != null) {
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(seedArgb, hsv)
            hsv[1].coerceIn(0.35f, 0.85f) to hsv[2].coerceIn(0.55f, 0.9f)
        } else {
            0.6f to 0.85f
        }
        return Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))
    }
    if (seedArgb == null) return null
    return if (shift == 0f) Color(seedArgb) else Color(MusicPlayerTheme.shiftHue(seedArgb, shift))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlobs(
    seedArgb: Int?,
    themeRoll: Int,
    width: Float,
    height: Float,
    template: LyricVisualTemplate,
    flowTimeSec: Float,
    // 2.90 第 3 项：色块圆心种子（themeRoll 仍传给 blobColor 的青澜色相抖动，不动它）。
    baseSeed: Int,
) {
    // 固定色相模板（青澜）无封面色也能画；跟 seed 走的模板无 seed 时无色块（现状行为）
    if (seedArgb == null && template.blobFixedHue == null) return
    val centers = blobCenters(template, baseSeed, width, height, flowTimeSec)
    val r = minOf(width, height)
    for (i in 0 until template.blobCount) {
        val color = blobColor(template, seedArgb, themeRoll, i) ?: continue
        drawCircle(
            // 2.82：色块淡一点，别把白字洗没了
            color = color.copy(alpha = template.blobAlphas.getOrElse(i) { 0.1f }),
            radius = r * template.blobRadii.getOrElse(i) { 0.4f },
            center = centers.getOrElse(i) { Offset(width / 2f, height / 2f) },
        )
    }
}

// 2.90 5A 青澜：半调圆点——Canvas 点阵，只铺左上与右下两角区（不盖词区中心），间距 12dp；
// 点半径 1–3.5dp、alpha 0.10–0.18，均按到最近色块中心的距离渐变（越近越大/越亮）。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHalftone(
    template: LyricVisualTemplate,
    baseSeed: Int,
    width: Float,
    height: Float,
    flowTimeSec: Float,
) {
    val centers = blobCenters(template, baseSeed, width, height, flowTimeSec)
    if (centers.isEmpty()) return
    val spacing = template.halftoneSpacingDp.dp.toPx()
    val maxDist = kotlin.math.hypot(width, height) * 0.5f
    var y = 0f
    while (y <= height) {
        var x = 0f
        while (x <= width) {
            val inCorner = (x < width * 0.35f && y < height * 0.35f) || (x > width * 0.65f && y > height * 0.65f)
            if (inCorner) {
                var nearest = Float.MAX_VALUE
                for (c in centers) {
                    val d = kotlin.math.hypot(x - c.x, y - c.y)
                    if (d < nearest) nearest = d
                }
                val t = (1f - nearest / maxDist).coerceIn(0f, 1f)
                val dotRadius = (template.halftoneDotMinDp + (template.halftoneDotMaxDp - template.halftoneDotMinDp) * t).dp.toPx()
                val alpha = 0.10f + (template.halftoneAlpha - 0.10f) * t
                drawCircle(color = Color.White.copy(alpha = alpha), radius = dotRadius, center = Offset(x, y))
            }
            x += spacing
        }
        y += spacing
    }
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDecorations(
    lineIndex: Int,
    baseSeed: Int,
    width: Float,
    height: Float,
    template: LyricVisualTemplate,
) {
    // 2.90 第 3 项：装饰走确定性种子流（行号混进种子，行与行互不相关）；每调一次都从头取同一序列。
    val decorRng = createVisualizerRng(mixVisualizerSeed(baseSeed + lineIndex * 7919, DECOR_SALT))
    val stroke = Stroke(width = 1.5.dp.toPx())
    // 2.90 5A：装饰颜色/数量/尺寸按模板走——霓虹青，其余白；种类池按模板取，顺序与流光现状一致
    val decorColor = template.decorColor.copy(alpha = template.decorAlpha)
    val pool = template.decorKinds.toList()
    if (pool.isEmpty()) return
    val spots = listOf(
        0.12f to 0.16f, 0.82f to 0.12f, 0.08f to 0.62f,
        0.88f to 0.58f, 0.5f to 0.08f, 0.18f to 0.88f, 0.78f to 0.86f,
    )
    for ((fx, fy) in spots.take(template.decorCount)) {
        // Float 舍入到 1.0f 时钳回范围内（JS float64 恒 < 1，Kotlin Float 可能恰好取到 1.0f）
        val sizeSpan = template.decorSizeMaxDp - template.decorSizeMinDp + 1
        val size = (template.decorSizeMinDp + (decorRng() * sizeSpan).toInt().coerceIn(0, sizeSpan - 1)).dp.toPx()
        val cx = width * fx
        val cy = height * fy
        when (pool[(decorRng() * pool.size).toInt().coerceIn(0, pool.size - 1)]) {
            DecorKind.HEART -> drawHeart(cx, cy, size, decorColor, stroke)
            DecorKind.FLOWER -> drawFlower(cx, cy, size, decorColor, stroke)
            DecorKind.GRID -> drawGrid(cx, cy, size, decorColor, stroke)
            DecorKind.CIRCLE -> drawCircle(color = decorColor, radius = size / 2f, style = stroke, center = Offset(cx, cy))
            DecorKind.PLUS -> drawPlus(cx, cy, size, decorColor, stroke)
            DecorKind.HEXAGON -> drawHexagon(cx, cy, size, decorColor, stroke)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeart(
    cx: Float,
    cy: Float,
    size: Float,
    color: Color,
    stroke: Stroke,
) {
    val path = Path()
    val x = cx - size / 2f
    val y = cy - size / 2f
    path.moveTo(x + size * 0.5f, y + size * 0.9f)
    path.cubicTo(x + size * 0.1f, y + size * 0.6f, x, y + size * 0.35f, x + size * 0.15f, y + size * 0.2f)
    path.cubicTo(x + size * 0.25f, y + size * 0.1f, x + size * 0.4f, y + size * 0.1f, x + size * 0.5f, y + size * 0.25f)
    path.cubicTo(x + size * 0.6f, y + size * 0.1f, x + size * 0.75f, y + size * 0.1f, x + size * 0.85f, y + size * 0.2f)
    path.cubicTo(x + size, y + size * 0.35f, x + size * 0.9f, y + size * 0.6f, x + size * 0.5f, y + size * 0.9f)
    path.close()
    drawPath(path, color, style = stroke)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlower(
    cx: Float,
    cy: Float,
    size: Float,
    color: Color,
    stroke: Stroke,
) {
    for (i in 0 until 5) {
        val angle = i * 72f * (Math.PI.toFloat() / 180f)
        val px = cx + kotlin.math.cos(angle) * size * 0.28f
        val py = cy + kotlin.math.sin(angle) * size * 0.28f
        drawCircle(color = color, radius = size * 0.2f, style = stroke, center = Offset(px, py))
    }
    drawCircle(color = color, radius = size * 0.1f, style = stroke, center = Offset(cx, cy))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
    cx: Float,
    cy: Float,
    size: Float,
    color: Color,
    stroke: Stroke,
) {
    val x = cx - size / 2f
    val y = cy - size / 2f
    for (i in 0..2) {
        val t = size * i / 2f
        drawLine(color, Offset(x + t, y), Offset(x + t, y + size), strokeWidth = stroke.width)
        drawLine(color, Offset(x, y + t), Offset(x + size, y + t), strokeWidth = stroke.width)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlus(
    cx: Float,
    cy: Float,
    size: Float,
    color: Color,
    stroke: Stroke,
) {
    val half = size / 2f
    drawLine(color, Offset(cx - half, cy), Offset(cx + half, cy), strokeWidth = stroke.width)
    drawLine(color, Offset(cx, cy - half), Offset(cx, cy + half), strokeWidth = stroke.width)
}

// 2.90 5A 星图：正六边形描边，边长 ≈ size/2（= 外接圆半径）
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHexagon(
    cx: Float,
    cy: Float,
    size: Float,
    color: Color,
    stroke: Stroke,
) {
    val path = Path()
    val radius = size / 2f
    for (k in 0 until 6) {
        val angle = (-90f + k * 60f) * (Math.PI.toFloat() / 180f)
        val px = cx + kotlin.math.cos(angle) * radius
        val py = cy + kotlin.math.sin(angle) * radius
        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color, style = stroke)
}

// 2.99 行版式：内容区（px）——各版式词排区不同（气泡左右交替、町景居中收窄、其余全框）；下雨/四季落到 else 全区。
private fun lineContentRect(
    template: LyricVisualTemplate,
    lineIndex: Int,
    widthPx: Float,
    heightPx: Float,
): Rect {
    return when (template.layout) {
        "bubble" -> {
            // 气泡左右交替对齐（像聊天）：偶数行靠左、奇数行靠右
            val boxW = widthPx * 0.88f
            val boxH = heightPx * 0.56f
            val left = if (lineIndex % 2 == 0) widthPx * 0.04f else widthPx * 0.96f - boxW
            Rect(left, (heightPx - boxH) / 2f, left + boxW, (heightPx - boxH) / 2f + boxH)
        }
        "sign" -> {
            val boxW = widthPx * 0.88f
            val boxH = heightPx * 0.56f
            Rect((widthPx - boxW) / 2f, (heightPx - boxH) / 2f, (widthPx + boxW) / 2f, (heightPx + boxH) / 2f)
        }
        else -> Rect(widthPx * 0.04f, heightPx * 0.08f, widthPx * 0.96f, heightPx * 0.92f)
    }
}

// 2.99 行版式：横排词流——词按行从左到右排、每行居中、整块在内容区内垂直居中；不旋转、不散布。
// 云阶：整区按 (index % 4) * 24dp 右缩进、字号每级 -5%；空间画布：字号取上限 ×1.05；商籁：字号取上限、行距 18dp。
private fun lineSlotsFor(
    lineIndex: Int,
    units: List<LyricUnit>,
    template: LyricVisualTemplate,
    measure: (text: String, sizeSp: Float, bold: Boolean) -> Size,
    boxWidthPx: Float,
    boxHeightPx: Float,
    contentRect: Rect,
    density: androidx.compose.ui.unit.Density,
): List<Slot> {
    if (units.isEmpty()) return emptyList()
    val step = if (template.layout == "steps") lineIndex % 4 else 0
    val baseSizeSp = when (template.layout) {
        "depth" -> template.sizeMaxSp * 1.05f
        "magazine" -> template.sizeMaxSp
        "steps" -> (template.sizeMinSp + template.sizeMaxSp) / 2f * (1f - step * 0.05f)
        else -> (template.sizeMinSp + template.sizeMaxSp) / 2f
    }
    val rowGapPx = with(density) { (if (template.layout == "magazine") 18.dp else 10.dp).toPx() }
    val wordGapPx = with(density) { 8.dp.toPx() }
    val indentPx = with(density) { (step * 24).dp.toPx() }
    val regionLeft = contentRect.left + indentPx
    val regionRight = contentRect.right
    val regionTop = contentRect.top
    val regionBottom = contentRect.bottom
    val regionWidth = (regionRight - regionLeft).coerceAtLeast(1f)
    // 先量后排：按最终字号（已乘 sizeScale）实测，粗体按常规量，宽 × 1.08 安全系数（与 slotsFor 同口径）
    data class MeasuredWord(val text: String, val w: Float, val h: Float, val sizeSp: Float)
    val measured = units.map { unit ->
        val finalSizeSp = baseSizeSp * sizeScale(unit.text.length)
        val size = measure(unit.text, finalSizeSp, false)
        MeasuredWord(unit.text, size.width * 1.08f, size.height, baseSizeSp)
    }
    // 贪心分行：词从左到右排，超宽换行
    val rows = ArrayList<List<MeasuredWord>>()
    var cur = ArrayList<MeasuredWord>()
    var curW = 0f
    for (mw in measured) {
        val addW = if (cur.isEmpty()) mw.w else mw.w + wordGapPx
        if (cur.isNotEmpty() && curW + addW > regionWidth) {
            rows.add(cur)
            cur = ArrayList()
            curW = 0f
        }
        cur.add(mw)
        curW += addW
    }
    if (cur.isNotEmpty()) rows.add(cur)
    // 整块垂直居中，每行水平居中
    val rowHeights = rows.map { row -> row.maxOf { it.h } }
    val totalH = rowHeights.sum() + rowGapPx * (rows.size - 1).coerceAtLeast(0)
    var y = regionTop + (regionBottom - regionTop - totalH) / 2f
    val slots = ArrayList<Slot>(units.size)
    for ((rowIndex, row) in rows.withIndex()) {
        val rowW = row.sumOf { it.w.toDouble() }.toFloat() + wordGapPx * (row.size - 1).coerceAtLeast(0)
        var x = regionLeft + (regionWidth - rowW) / 2f
        val rowH = rowHeights[rowIndex]
        for (mw in row) {
            slots.add(
                Slot(
                    xFrac = (x / boxWidthPx).coerceIn(0.02f, 0.98f),
                    yFrac = (y / boxHeightPx).coerceIn(0.02f, 0.98f),
                    sizeSp = mw.sizeSp,
                    rotDeg = 0f,
                )
            )
            x += mw.w + wordGapPx
        }
        y += rowH + rowGapPx
    }
    return slots
}

// 3.01 行版式装饰（画在词下面）：只剩景深上下行淡字与商籁旁注。
// 只在对应版式画；云阶、商籁无额外装饰（版式特征在排词里）。
// 3.01：transitionT（换行 400ms 位移进度）与 lineProgress（行内 0..1）供商籁旁注用。
// 3.01 行版式装饰（画在词下面）：只剩景深上下行淡字与商籁旁注——这两处是歌词排版本身，不是背景方框。
// 气泡框、町景招牌框全部去掉（用户定：动效都去掉背景方框，歌词直接排在背景上）；
// 雾笺本就不铺底；框随换行上推的 frameShiftY 随框去掉而去掉（词循环的 lineShiftY 还在，词照样上推）。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLayoutDecorations(
    template: LyricVisualTemplate,
    lineIndex: Int,
    lines: List<LyricLine>,
    width: Float,
    height: Float,
    contentRect: Rect,
    timeSec: Float,
    seedArgb: Int?,
    primary: Color,
    surfaceLight: Boolean,
    textMeasurer: TextMeasurer,
    transitionT: Float = 1f,
    lineProgress: Float = 0f,
) {
    when (template.layout) {
        "depth" -> {
            // 景深：上下行按距离缩小变淡（0.72 倍字号、alpha 0.30），当前行在中间最大
            val neighborSizeSp = template.sizeMaxSp * 0.72f
            val neighborStyle = TextStyle(
                fontSize = neighborSizeSp.sp,
                fontWeight = FontWeight.Normal,
                color = (if (surfaceLight) Color.Black else Color.White).copy(alpha = 0.30f),
            )
            val centerY = contentRect.center.y
            val offsetY = height * 0.16f
            val prev = lines.getOrNull(lineIndex - 1)?.text?.trim().orEmpty()
            val next = lines.getOrNull(lineIndex + 1)?.text?.trim().orEmpty()
            if (prev.isNotEmpty()) {
                val measured = textMeasurer.measure(prev, neighborStyle)
                val w = measured.size.width.toFloat()
                val h = measured.size.height.toFloat()
                drawText(textMeasurer, prev, Offset((width - w) / 2f, centerY - offsetY - h / 2f), neighborStyle)
            }
            if (next.isNotEmpty()) {
                val measured = textMeasurer.measure(next, neighborStyle)
                val w = measured.size.width.toFloat()
                val h = measured.size.height.toFloat()
                drawText(textMeasurer, next, Offset((width - w) / 2f, centerY + offsetY - h / 2f), neighborStyle)
            }
        }
        "magazine" -> {
            // 3.01 商籁旁注：邻句为小号旁注（0.55 倍字号、alpha 0.35），上一句在上、下一句在下，当前句大标题在中间
            val noteSizeSp = template.sizeMaxSp * 0.55f
            val noteStyle = TextStyle(
                fontSize = noteSizeSp.sp,
                fontWeight = FontWeight.Normal,
                color = (if (surfaceLight) Color.Black else Color.White).copy(alpha = 0.35f),
            )
            val centerY = contentRect.center.y
            val offsetY = height * 0.20f
            val prev = lines.getOrNull(lineIndex - 1)?.text?.trim().orEmpty()
            val next = lines.getOrNull(lineIndex + 1)?.text?.trim().orEmpty()
            if (prev.isNotEmpty()) {
                val measured = textMeasurer.measure(prev, noteStyle)
                val w = measured.size.width.toFloat()
                val h = measured.size.height.toFloat()
                drawText(textMeasurer, prev, Offset((width - w) / 2f, centerY - offsetY - h / 2f), noteStyle)
            }
            if (next.isNotEmpty()) {
                val measured = textMeasurer.measure(next, noteStyle)
                val w = measured.size.width.toFloat()
                val h = measured.size.height.toFloat()
                drawText(textMeasurer, next, Offset((width - w) / 2f, centerY + offsetY - h / 2f), noteStyle)
            }
        }
    }
}

// 3.01 词框（词前特效用）：按 slot 与不加粗实测算每词的框；装饰用近似位置即可，不跟随加粗微胀。
private fun wordRectsFor(
    units: List<LyricUnit>,
    slots: List<Slot>,
    measure: (text: String, sizeSp: Float, bold: Boolean) -> Size,
    boxWidthPx: Float,
    boxHeightPx: Float,
): List<Rect> {
    if (units.isEmpty()) return emptyList()
    return units.mapIndexed { i, unit ->
        val slot = slots.getOrNull(i) ?: return@mapIndexed Rect.Zero
        val sizeSp = slot.sizeSp * sizeScale(unit.text.length)
        val m = measure(unit.text, sizeSp, false)
        val w = m.width
        val h = m.height
        val x = (slot.xFrac * boxWidthPx).coerceIn(0f, (boxWidthPx - w).coerceAtLeast(0f))
        val y = (slot.yFrac * boxHeightPx).coerceIn(0f, (boxHeightPx - h).coerceAtLeast(0f))
        Rect(x, y, x + w, y + h)
    }
}

// 3.01 星图：词语对应小星点，按阅读顺序逐个点亮，细线逐段连接；文字始终在连线前方（本函数画在词前，字盖在上面）。
// 星点：未唱暗（alpha 0.30），唱到亮（0.95）带光晕；连线：终点词已唱才亮（0.45），否则暗（0.14）。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStarMap(
    units: List<LyricUnit>,
    rects: List<Rect>,
    nowMs: Long,
) {
    if (units.isEmpty() || rects.size != units.size) return
    val centers = rects.map { it.center }
    val lineStroke = Stroke(width = 1.2.dp.toPx())
    for (i in 0 until centers.size - 1) {
        val endFill = fillFraction(nowMs, units[i + 1])
        val alpha = if (endFill > 0f) 0.45f else 0.14f
        drawLine(
            color = Color.White.copy(alpha = alpha),
            start = centers[i],
            end = centers[i + 1],
            strokeWidth = lineStroke.width,
        )
    }
    for (i in centers.indices) {
        val fill = fillFraction(nowMs, units[i])
        val lit = fill > 0f
        val r = if (lit) 3.2.dp.toPx() else 2.2.dp.toPx()
        // 光晕（比字大一圈，从字后透出来，星点才看得见）
        drawCircle(
            color = Color.White.copy(alpha = if (lit) 0.22f else 0.08f),
            radius = r * 3.2f,
            center = centers[i],
        )
        drawCircle(
            color = Color.White.copy(alpha = if (lit) 0.95f else 0.30f),
            radius = r,
            center = centers[i],
        )
        if (fill > 0f && fill < 1f) {
            val pulse = 1f + sin(nowMs / 1000f * 8f) * 0.25f
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = r * 2.2f * pulse,
                center = centers[i],
            )
        }
    }
}

// 3.01 青澜：当前词唱到时，从词下方扩散一圈浅青水波（700ms 内由小变大、由浓变淡，两圈）；波纹在文字后面。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWordRipples(
    units: List<LyricUnit>,
    rects: List<Rect>,
    nowMs: Long,
) {
    if (units.isEmpty() || rects.size != units.size) return
    val rippleColor = Color(0xFF7CFFB2)
    for (i in units.indices) {
        val unit = units[i]
        val age = nowMs - unit.startMs
        if (age < 0L || age > 700L) continue
        val t = (age / 700f).coerceIn(0f, 1f)
        val rect = rects[i]
        val cx = rect.center.x
        val cy = rect.bottom + 10.dp.toPx()
        val maxR = rect.width * 0.9f
        val r = maxR * (0.25f + 0.75f * t)
        drawOval(
            color = rippleColor.copy(alpha = 0.32f * (1f - t)),
            topLeft = Offset(cx - r, cy - r * 0.35f),
            size = Size(r * 2f, r * 0.7f),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        val t2 = ((age - 120L) / 700f).coerceIn(0f, 1f)
        if (t2 > 0f) {
            val r2 = maxR * (0.25f + 0.75f * t2)
            drawOval(
                color = rippleColor.copy(alpha = 0.20f * (1f - t2)),
                topLeft = Offset(cx - r2, cy - r2 * 0.35f),
                size = Size(r2 * 2f, r2 * 0.7f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

// 3.01 雾笺：当前词经过时局部变清晰——在当前词后画一团柔和亮斑（径向渐变），唱完保持可读；雾感由低对比底字（baseAlpha 0.42）与淡色块提供，不另铺白雾洗底。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMistClear(
    units: List<LyricUnit>,
    rects: List<Rect>,
    nowMs: Long,
) {
    if (units.isEmpty() || rects.size != units.size) return
    var target = -1
    for (i in units.indices) {
        if (nowMs >= units[i].startMs && nowMs < units[i].endMs) {
            target = i
            break
        }
    }
    if (target < 0) {
        for (i in units.indices.reversed()) {
            if (nowMs >= units[i].endMs) {
                target = i
                break
            }
        }
    }
    if (target < 0) return
    val rect = rects[target]
    val cx = rect.center.x
    val cy = rect.center.y
    val radius = maxOf(rect.width, rect.height) * 1.1f
    val brush = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
        center = Offset(cx, cy),
        radius = radius,
    )
    drawRect(brush = brush)
}

// 3.01 后 8 款新背景动效（去框、去圆之后）：每款各一个非圆背景，画在文字后面；全部由 timeSec 推导，暂停停住；
// 位置/大小/速度由 baseSeed + BG_SALT 确定性派生，同首歌每次打开一样。黑白下靠形状与运动区分，不靠颜色。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTemplateBackground(
    template: LyricVisualTemplate,
    width: Float,
    height: Float,
    timeSec: Float,
    seedArgb: Int?,
    primary: Color,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    when (template.id) {
        7 -> drawBubbleBackground(width, height, timeSec, primary, surfaceLight, baseSeed)
        8 -> drawCloudBackground(width, height, timeSec, surfaceLight, baseSeed)
        9 -> drawStarfieldBackground(width, height, timeSec, surfaceLight, baseSeed)
        10 -> drawRainBackground(width, height, timeSec, surfaceLight, baseSeed)
        11 -> drawSeasonsBackground(width, height, timeSec, surfaceLight, baseSeed)
        12 -> drawMistBandBackground(width, height, timeSec, surfaceLight, baseSeed)
        13 -> drawStreetLightBackground(width, height, timeSec, baseSeed)
        14 -> drawPaperBackground(width, height, timeSec, surfaceLight, baseSeed)
    }
}

// 气泡：满屏上升的气泡群——14 颗大小不一的透明气泡从底部缓缓上升、左右轻摆、到顶渐隐；
// 气泡是空心描边 + 小高光（不是实心色块圆），上升是竖向运动，与旧三团漂移色块完全不同。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBubbleBackground(
    width: Float,
    height: Float,
    timeSec: Float,
    primary: Color,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 7 * 1009, BG_SALT))
    val ink = if (surfaceLight) Color.Black else Color.White
    val count = 14
    for (i in 0 until count) {
        val xFrac = rng()
        val radius = (7f + rng() * 20f).dp.toPx()
        val riseSec = 14f + rng() * 10f
        val phase = rng()
        val swayAmp = (4f + rng() * 10f).dp.toPx()
        val swaySpeed = 0.4f + rng() * 0.6f
        val cycle = ((timeSec / riseSec) + phase) % 1f
        val y = height - cycle * (height + radius * 2f) + radius
        val x = width * xFrac + sin(timeSec * swaySpeed + i * 1.7f) * swayAmp
        val fade = (sin(cycle * Math.PI.toFloat()) * 0.9f + 0.1f).coerceIn(0f, 1f)
        drawCircle(
            color = primary.copy(alpha = 0.28f * fade),
            radius = radius,
            center = Offset(x, y),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        drawCircle(
            color = ink.copy(alpha = 0.35f * fade),
            radius = radius * 0.18f,
            center = Offset(x - radius * 0.35f, y - radius * 0.35f),
        )
    }
}

// 云阶：横向漂移的云团——5 团由椭圆拼成的云形左右缓慢漂移（不同高度、不同速度），不是圆色块。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloudBackground(
    width: Float,
    height: Float,
    timeSec: Float,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 8 * 1009, BG_SALT))
    val ink = if (surfaceLight) Color.Black else Color.White
    val count = 5
    for (i in 0 until count) {
        val yFrac = 0.08f + rng() * 0.84f
        val cloudW = width * (0.22f + rng() * 0.20f)
        val cloudH = cloudW * 0.32f
        val speed = 8f + rng() * 14f
        val phase = rng() * (width + cloudW * 2f)
        val total = width + cloudW * 2f
        val x = ((phase + timeSec * speed) % total) - cloudW
        val cy = height * yFrac
        val puffs = listOf(
            Triple(-0.32f, 0.10f, 0.55f),
            Triple(-0.10f, -0.12f, 0.75f),
            Triple(0.14f, 0.06f, 0.65f),
            Triple(0.34f, 0.12f, 0.50f),
        )
        for ((dxF, dyF, rF) in puffs) {
            val rx = cloudW * 0.5f * rF
            val ry = cloudH * 0.5f * rF
            drawOval(
                color = ink.copy(alpha = 0.10f),
                topLeft = Offset(x + cloudW * dxF - rx, cy + cloudH * dyF - ry),
                size = Size(rx * 2f, ry * 2f),
            )
        }
    }
}

// 空间画布：闪烁的星空——40 颗小星点明灭闪烁，约每 8 秒一道流星拖着细线划过；
// 星点是环境闪烁（不跟词走），与星图的“词间连线、随唱点亮”完全不同。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStarfieldBackground(
    width: Float,
    height: Float,
    timeSec: Float,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 9 * 1009, BG_SALT))
    val ink = if (surfaceLight) Color.Black else Color.White
    val count = 40
    for (i in 0 until count) {
        val xFrac = rng()
        val yFrac = rng()
        val radius = (0.8f + rng() * 1.6f).dp.toPx()
        val twSpeed = 0.6f + rng() * 1.8f
        val twPhase = rng() * 6.28f
        val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(timeSec * twSpeed + twPhase))
        drawCircle(
            color = ink.copy(alpha = (0.55f * twinkle).coerceIn(0f, 1f)),
            radius = radius,
            center = Offset(width * xFrac, height * yFrac),
        )
    }
    // 流星：每 8 秒一颗，1 秒内斜向下划过，拖一条渐隐细线；每轮换一个随机起点
    val cycleSec = 8f
    val cycleT = timeSec % cycleSec
    if (cycleT < 1f) {
        val cycleIndex = (timeSec / cycleSec).toInt()
        val meteorRng = createVisualizerRng(mixVisualizerSeed(baseSeed + cycleIndex * 7919, BG_SALT))
        val startXFrac = 0.15f + meteorRng() * 0.55f
        val startYFrac = 0.05f + meteorRng() * 0.25f
        val headX = width * startXFrac + cycleT * width * 0.45f
        val headY = height * startYFrac + cycleT * height * 0.28f
        val tailLen = width * 0.12f
        val tailX = headX - tailLen * 0.85f
        val tailY = headY - tailLen * 0.53f
        val brush = Brush.linearGradient(
            colors = listOf(ink.copy(alpha = 0.7f * (1f - cycleT)), Color.Transparent),
            start = Offset(headX, headY),
            end = Offset(tailX, tailY),
        )
        drawLine(
            brush = brush,
            start = Offset(headX, headY),
            end = Offset(tailX, tailY),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(color = ink.copy(alpha = 0.8f * (1f - cycleT)), radius = 2.5.dp.toPx(), center = Offset(headX, headY))
    }
}

// 下雨：雨天的氛围感——阴天灰蓝色调，偏冷、偏暗、柔和，不亮、不艳；三层画在文字后面：
// ① 细雨丝斜落（带风斜角、速度各不同）；② 落到底部化成小涟漪（扁椭圆扩散渐隐）；
// ③ 雨打窗玻璃——几颗水珠贴在玻璃上缓缓下滑、拖出水痕。安静、微凉、有点忧郁的雨天；整体对比度低，字用亮色保证可读。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRainBackground(
    width: Float,
    height: Float,
    timeSec: Float,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    val rainColor = if (surfaceLight) Color(0xFF5A6B7A) else Color(0xFFA8BCCB)
    // ① 细雨丝：55 根，斜落（风斜角约 10°），速度各不同，顶部淡入、底部淡出
    run {
        val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 10 * 1009, BG_SALT))
        val slant = 0.18f
        val count = 55
        for (i in 0 until count) {
            val xFrac = rng()
            val len = (18f + rng() * 27f).dp.toPx()
            val fallSec = 0.7f + rng() * 0.7f
            val phase = rng()
            val cycle = ((timeSec / fallSec) + phase) % 1f
            val y = cycle * (height + len) - len
            val x = width * xFrac + y * slant
            val fade = (sin(cycle * Math.PI.toFloat()) * 0.9f + 0.1f).coerceIn(0f, 1f)
            drawLine(
                color = rainColor.copy(alpha = 0.30f * fade),
                start = Offset(x, y),
                end = Offset(x - len * slant, y - len),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
    // ② 底部涟漪：8 个，扁椭圆扩散渐隐（宽扁，像水花溅开，不是圆）
    run {
        val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 10 * 1009 + 31, BG_SALT))
        val count = 8
        for (i in 0 until count) {
            val xFrac = rng()
            val yFrac = 0.93f + rng() * 0.04f
            val maxRx = (16f + rng() * 14f).dp.toPx()
            val period = 2.5f + rng() * 1.5f
            val phase = rng()
            val cycle = ((timeSec / period) + phase) % 1f
            val rx = maxRx * cycle
            val ry = rx * 0.32f
            if (rx < 1f) continue
            drawOval(
                color = rainColor.copy(alpha = 0.32f * (1f - cycle)),
                topLeft = Offset(width * xFrac - rx, height * yFrac - ry),
                size = Size(rx * 2f, ry * 2f),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
    }
    // ③ 玻璃水珠：5 颗，贴在玻璃上缓缓下滑（比雨丝慢得多），拖出水痕
    run {
        val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 10 * 1009 + 77, BG_SALT))
        val count = 5
        for (i in 0 until count) {
            val xFrac = rng()
            val radius = (3f + rng() * 3f).dp.toPx()
            val slideSec = 18f + rng() * 12f
            val phase = rng()
            val trailLen = (40f + rng() * 40f).dp.toPx()
            val cycle = ((timeSec / slideSec) + phase) % 1f
            val y = cycle * (height + radius * 2f) - radius
            val x = width * xFrac + sin(timeSec * 0.3f + i * 2.1f) * 3.dp.toPx()
            val fade = (sin(cycle * Math.PI.toFloat()) * 0.8f + 0.2f).coerceIn(0f, 1f)
            // 水痕（上淡下浓，贴在水珠后面）
            val trailBrush = Brush.linearGradient(
                colors = listOf(Color.Transparent, rainColor.copy(alpha = 0.25f * fade)),
                start = Offset(x, y - trailLen),
                end = Offset(x, y),
            )
            drawLine(
                brush = trailBrush,
                start = Offset(x, y - trailLen),
                end = Offset(x, y),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(
                color = rainColor.copy(alpha = 0.55f * fade),
                radius = radius,
                center = Offset(x, y),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.6f * fade),
                radius = radius * 0.28f,
                center = Offset(x - radius * 0.3f, y - radius * 0.3f),
            )
        }
    }
}

// 四季：四季轮回——背景随时间循环春夏秋冬（30 秒一季），季与季之间淡入淡出、自由搭配（过渡时两季同屏，同一屏里混着两季的元素）；
// 春粉色花瓣飘落、夏阳光绿叶、秋金红枫叶飘落、冬雪花纷飞。全部画在文字后面。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeasonsBackground(
    width: Float,
    height: Float,
    timeSec: Float,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    // 季透明度：到本季中心的循环距离（季为单位，4 季循环）；中心附近满，边界处与相邻季各半——过渡 9 秒淡入淡出，无生硬切换
    val seasonFloat = timeSec / 30f
    fun seasonAlpha(s: Int): Float {
        var d = abs(seasonFloat - (s + 0.5f)) % 4f
        if (d > 2f) d = 4f - d
        return (1f - (d - 0.35f) / 0.3f).coerceIn(0f, 1f)
    }
    val springA = seasonAlpha(0)
    val summerA = seasonAlpha(1)
    val autumnA = seasonAlpha(2)
    val winterA = seasonAlpha(3)
    if (springA > 0.01f) drawSpringPetals(width, height, timeSec, springA, surfaceLight, baseSeed)
    if (summerA > 0.01f) drawSummerLeaves(width, height, timeSec, summerA, surfaceLight, baseSeed)
    if (autumnA > 0.01f) drawAutumnLeaves(width, height, timeSec, autumnA, baseSeed)
    if (winterA > 0.01f) drawWinterSnow(width, height, timeSec, winterA, surfaceLight, baseSeed)
}

// 春：粉色花瓣飘落——10 片粉色椭圆花瓣缓缓飘落、左右轻摆、轻轻旋转（椭圆，不是圆）。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpringPetals(
    width: Float,
    height: Float,
    timeSec: Float,
    seasonAlpha: Float,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 11 * 1009, BG_SALT))
    val petalColor = if (surfaceLight) Color(0xFFE87A9E) else Color(0xFFF4A7C3)
    val count = 10
    for (i in 0 until count) {
        val xFrac = rng()
        val petalW = (10f + rng() * 8f).dp.toPx()
        val petalH = petalW * 0.7f
        val fallSec = 12f + rng() * 8f
        val phase = rng()
        val swayAmp = (10f + rng() * 16f).dp.toPx()
        val swaySpeed = 0.4f + rng() * 0.5f
        val rotAmpDeg = 20f + rng() * 25f
        val rotSpeed = 0.3f + rng() * 0.4f
        val cycle = ((timeSec / fallSec) + phase) % 1f
        val y = -petalH + cycle * (height + petalH * 2f)
        val x = width * xFrac + sin(timeSec * swaySpeed + i * 2.1f) * swayAmp
        val angle = sin(timeSec * rotSpeed + i * 1.7f) * rotAmpDeg
        val fade = (sin(cycle * Math.PI.toFloat()) * 0.8f + 0.2f).coerceIn(0f, 1f)
        rotate(angle, pivot = Offset(x, y)) {
            drawOval(
                color = petalColor.copy(alpha = 0.55f * seasonAlpha * fade),
                topLeft = Offset(x - petalW / 2f, y - petalH / 2f),
                size = Size(petalW, petalH),
            )
        }
    }
}

// 夏：阳光与绿叶——右上暖黄阳光晕（柔和渐变，无硬边）+ 8 片绿叶缓慢漂移、上下轻浮。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSummerLeaves(
    width: Float,
    height: Float,
    timeSec: Float,
    seasonAlpha: Float,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    // 阳光晕：右上暖黄径向光晕，亮度轻脉动
    val sunPulse = 0.14f + 0.04f * sin(timeSec * 0.7f)
    val sunBrush = Brush.radialGradient(
        colors = listOf(Color(0xFFFFD98A).copy(alpha = sunPulse * seasonAlpha), Color.Transparent),
        center = Offset(width * 0.85f, height * 0.08f),
        radius = width * 0.45f,
    )
    drawRect(brush = sunBrush)
    // 绿叶：椭圆绿叶，横向缓慢漂移 + 上下轻浮 + 轻轻旋转
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 11 * 1009 + 13, BG_SALT))
    val leafColor = if (surfaceLight) Color(0xFF4A9E4F) else Color(0xFF7BC47F)
    val count = 8
    for (i in 0 until count) {
        val xFrac = rng()
        val yFrac = 0.10f + rng() * 0.80f
        val leafW = (12f + rng() * 8f).dp.toPx()
        val leafH = leafW * 0.55f
        val driftAmp = (16f + rng() * 20f).dp.toPx()
        val driftSpeed = 0.2f + rng() * 0.3f
        val bobAmp = (6f + rng() * 8f).dp.toPx()
        val bobSpeed = 0.4f + rng() * 0.4f
        val rotAmpDeg = 15f + rng() * 20f
        val rotSpeed = 0.25f + rng() * 0.35f
        val x = width * xFrac + sin(timeSec * driftSpeed + i * 1.9f) * driftAmp
        val y = height * yFrac + sin(timeSec * bobSpeed + i * 2.4f) * bobAmp
        val angle = sin(timeSec * rotSpeed + i * 1.5f) * rotAmpDeg
        rotate(angle, pivot = Offset(x, y)) {
            drawOval(
                color = leafColor.copy(alpha = 0.50f * seasonAlpha),
                topLeft = Offset(x - leafW / 2f, y - leafH / 2f),
                size = Size(leafW, leafH),
            )
        }
    }
}

// 秋：金红枫叶飘落——10 片金/红/橙菱形枫叶翻滚飘落（持续旋转，不是轻摆），与春花瓣的椭圆轻转完全不同。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAutumnLeaves(
    width: Float,
    height: Float,
    timeSec: Float,
    seasonAlpha: Float,
    baseSeed: Int,
) {
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 11 * 1009 + 29, BG_SALT))
    val leafColors = listOf(Color(0xFFFFB84D), Color(0xFFE85D3D), Color(0xFFFF8C42))
    val count = 10
    for (i in 0 until count) {
        val xFrac = rng()
        val size = (10f + rng() * 8f).dp.toPx()
        val fallSec = 10f + rng() * 6f
        val phase = rng()
        val swayAmp = (8f + rng() * 14f).dp.toPx()
        val swaySpeed = 0.5f + rng() * 0.5f
        val tumbleSpeed = (40f + rng() * 60f) // 度/秒，持续翻滚
        val cycle = ((timeSec / fallSec) + phase) % 1f
        val y = -size + cycle * (height + size * 2f)
        val x = width * xFrac + sin(timeSec * swaySpeed + i * 2.2f) * swayAmp
        val angle = timeSec * tumbleSpeed + phase * 360f
        val fade = (sin(cycle * Math.PI.toFloat()) * 0.8f + 0.2f).coerceIn(0f, 1f)
        rotate(angle, pivot = Offset(x, y)) {
            drawRect(
                color = leafColors[i % 3].copy(alpha = 0.60f * seasonAlpha * fade),
                topLeft = Offset(x - size / 2f, y - size / 2f),
                size = Size(size, size),
            )
        }
    }
}

// 冬：雪花纷飞——40 片雪花缓缓飘落、左右轻摆；大片画三线星芒（六角雪花），小片画圆点。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWinterSnow(
    width: Float,
    height: Float,
    timeSec: Float,
    seasonAlpha: Float,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 11 * 1009 + 47, BG_SALT))
    val ink = if (surfaceLight) Color.Black else Color.White
    val count = 40
    for (i in 0 until count) {
        val xFrac = rng()
        val radius = (1.2f + rng() * 2.6f).dp.toPx()
        val fallSec = 10f + rng() * 8f
        val phase = rng()
        val swayAmp = (6f + rng() * 12f).dp.toPx()
        val swaySpeed = 0.4f + rng() * 0.5f
        val cycle = ((timeSec / fallSec) + phase) % 1f
        val y = -radius + cycle * (height + radius * 2f)
        val x = width * xFrac + sin(timeSec * swaySpeed + i * 1.8f) * swayAmp
        val fade = (sin(cycle * Math.PI.toFloat()) * 0.8f + 0.2f).coerceIn(0f, 1f)
        val alpha = (0.75f * fade * seasonAlpha).coerceIn(0f, 1f)
        if (radius > 2.5f.dp.toPx()) {
            // 六角雪花：三线星芒
            for (k in 0 until 3) {
                val a = k * 60f * Math.PI.toFloat() / 180f
                val dx = cos(a) * radius
                val dy = sin(a) * radius
                drawLine(
                    color = ink.copy(alpha = alpha),
                    start = Offset(x - dx, y - dy),
                    end = Offset(x + dx, y + dy),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
        } else {
            drawCircle(color = ink.copy(alpha = alpha), radius = radius, center = Offset(x, y))
        }
    }
}

// 雾笺：横向漂移的雾带——3 条半透明横向雾带缓慢左右漂移（来回摆动），字始终清晰；雾感只在背景，不模糊文字。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMistBandBackground(
    width: Float,
    height: Float,
    timeSec: Float,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 12 * 1009, BG_SALT))
    val ink = if (surfaceLight) Color.Black else Color.White
    val count = 3
    for (i in 0 until count) {
        val yFrac = 0.18f + rng() * 0.64f
        val bandH = (22f + rng() * 18f).dp.toPx()
        val bandW = width * (0.75f + rng() * 0.45f)
        val driftAmp = (30f + rng() * 40f).dp.toPx()
        val driftSpeed = 0.25f + rng() * 0.25f
        val phase = rng() * 6.28f
        val cx = width * 0.5f + sin(timeSec * driftSpeed + phase) * driftAmp
        val cy = height * yFrac
        val brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, ink.copy(alpha = 0.08f), Color.Transparent),
            startX = cx - bandW / 2f,
            endX = cx + bandW / 2f,
        )
        drawRect(
            brush = brush,
            topLeft = Offset(cx - bandW / 2f, cy - bandH / 2f),
            size = Size(bandW, bandH),
        )
    }
}

// 町景：夜间街景灯光——底部一排临街店铺，小窗光/霓虹竖条亮度逐个明灭（像店铺次第开灯）；
// 灯光是小方块与竖条，不是圆；歌词的逐段通电（词循环）保留，招牌框已去掉。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStreetLightBackground(
    width: Float,
    height: Float,
    timeSec: Float,
    baseSeed: Int,
) {
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 13 * 1009, BG_SALT))
    val stripTop = height * 0.86f
    drawRect(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = Offset(0f, stripTop),
        size = Size(width, height - stripTop),
    )
    val shopCount = 8
    val shopW = width / shopCount
    val neonColors = listOf(Color(0xFFFF8FD0), Color(0xFF7FE3FF), Color(0xFFFFCF7A))
    for (i in 0 until shopCount) {
        val winW = shopW * 0.52f
        val winH = (26f + rng() * 14f).dp.toPx()
        val winX = i * shopW + (shopW - winW) / 2f
        val winY = stripTop + (height - stripTop - winH) / 2f
        // 每间铺子 4 秒一轮，依次错开 0.5 秒：亮 3 秒、灭 1 秒（灭时平滑渐暗，亮时直接点亮，像霓虹招牌）
        val cycle = 4f
        val phase = i * 0.5f
        val ct = ((timeSec + phase) % cycle) / cycle
        val on = if (ct < 0.75f) 1f else 1f - (ct - 0.75f) / 0.25f
        val neon = neonColors[i % 3]
        drawRoundRect(
            color = neon.copy(alpha = (0.75f * on).coerceIn(0f, 1f)),
            topLeft = Offset(winX, winY),
            size = Size(winW, winH),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
        drawRect(
            color = neon.copy(alpha = (0.5f * on).coerceIn(0f, 1f)),
            topLeft = Offset((i + 1) * shopW - 4.dp.toPx(), stripTop + 6.dp.toPx()),
            size = Size(3.dp.toPx(), height - stripTop - 12.dp.toPx()),
        )
    }
}

// 商籁：飘落的纸片——6 片小矩形纸片缓慢旋转飘落（从顶到底循环），留白感；纸片是矩形，不是圆。
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPaperBackground(
    width: Float,
    height: Float,
    timeSec: Float,
    surfaceLight: Boolean,
    baseSeed: Int,
) {
    val rng = createVisualizerRng(mixVisualizerSeed(baseSeed + 14 * 1009, BG_SALT))
    val ink = if (surfaceLight) Color.Black else Color.White
    val count = 6
    for (i in 0 until count) {
        val xFrac = rng()
        val paperW = (22f + rng() * 14f).dp.toPx()
        val paperH = paperW * 1.35f
        val fallSec = 22f + rng() * 12f
        val phase = rng()
        val swayAmp = (8f + rng() * 14f).dp.toPx()
        val swaySpeed = 0.4f + rng() * 0.4f
        val rotAmpDeg = 15f + rng() * 20f
        val rotSpeed = 0.3f + rng() * 0.4f
        val cycle = ((timeSec / fallSec) + phase) % 1f
        val y = -paperH + cycle * (height + paperH * 2f)
        val x = width * xFrac + sin(timeSec * swaySpeed + i * 2.3f) * swayAmp
        val angle = sin(timeSec * rotSpeed + i * 1.9f) * rotAmpDeg
        rotate(angle, pivot = Offset(x, y)) {
            drawRect(
                color = ink.copy(alpha = 0.16f),
                topLeft = Offset(x - paperW / 2f, y - paperH / 2f),
                size = Size(paperW, paperH),
            )
            drawRect(
                color = ink.copy(alpha = 0.28f),
                topLeft = Offset(x - paperW / 2f, y - paperH / 2f),
                size = Size(paperW, paperH),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
