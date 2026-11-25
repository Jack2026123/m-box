package com.github.tvbox.osc.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.theme.ControlTheme

enum class SegmentStyle { Connected, Track, Separated }

private val TrackShape = RoundedCornerShape(percent = 50)

private val TrackPadding = 4.dp

private val TrackSegmentSpacing = 4.dp

private val TrackContentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)

private val SeparatedSegmentSpacing = 8.dp

private val SeparatedContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

data class SegmentOption<T>(
    val label: String,
    val value: T,
    val icon: ImageVector? = null,
    val iconPainter: Painter? = null,
    val badge: String? = null,
    /** True = size to content instead of sharing the row width equally. */
    val hugContent: Boolean = false,
)

/** Spring-stiffness multiplier for the global 动画速度 factor. */
internal fun animSpeedScale(factor: Float): Float =
    if (factor <= 0f) 100f else (1f / (factor * factor)).coerceIn(0.2f, 6f)

/**
 * Width math for the distributeLeftover tab row, kept pure so the
 * measure contract can be unit-tested: given the children's hug-content
 * widths, the inter-segment gap and the row's incoming max width,
 * returns the fixed target width for each child plus the row width to
 * report. The reported row width NEVER exceeds [maxWidth] — the
 * previous policy reported maxOf(maxWidth, contentTotal), so whenever
 * the capsules' total hug width overflowed the row (config-manage tab
 * row on narrow screens / big fonts) `layout()` was handed a size
 * larger than the constraint and the first layout threw.
 *
 * - unbounded ([maxWidth] == [Constraints.Infinity]): targets are the
 *   content widths, row = content total (contents + gaps).
 * - bounded, leftover >= 0: each child gains an equal share of the
 *   leftover; the LAST child absorbs the division remainder so targets
 *   + gaps sum EXACTLY to [maxWidth]; row = [maxWidth].
 * - bounded, leftover < 0: contents are scaled proportionally into
 *   (maxWidth - gaps) with floor rounding; the rounding residue is
 *   returned pixel-by-pixel to the widest children first, never
 *   pushing a child past its own content width; row = [maxWidth].
 */
internal fun computeDistributedWidths(
    contentWidths: List<Int>,
    gapPx: Int,
    maxWidth: Int,
): Pair<List<Int>, Int> {
    val count = contentWidths.size
    if (count == 0) return emptyList<Int>() to 0
    val totalGaps = gapPx.coerceAtLeast(0) * (count - 1)
    val contentSum = contentWidths.sum()
    val contentTotal = contentSum + totalGaps
    if (maxWidth == Constraints.Infinity) {
        return contentWidths to contentTotal
    }
    val boundedMax = maxWidth.coerceAtLeast(0)
    val leftover = boundedMax - contentTotal
    if (leftover >= 0) {
        val share = leftover / count
        val remainder = leftover % count
        val targets = contentWidths.mapIndexed { index, width ->
            width + share + if (index == count - 1) remainder else 0
        }
        return targets to boundedMax
    }
    val available = (boundedMax - totalGaps).coerceAtLeast(0)
    if (contentSum <= 0) {
        return List(count) { 0 } to boundedMax
    }
    val targets = contentWidths
        .map { ((it.toLong() * available) / contentSum).toInt().coerceAtLeast(0) }
        .toMutableList()
    var residue = available - targets.sum()
    if (residue > 0) {
        // Widest first; a child never grows past its own content width.
        val order = contentWidths.indices.sortedByDescending { contentWidths[it] }
        var i = 0
        while (residue > 0 && i < order.size) {
            val index = order[i]
            val room = contentWidths[index] - targets[index]
            if (room > 0) {
                val give = minOf(room, residue)
                targets[index] += give
                residue -= give
            }
            i++
        }
    }
    return targets to boundedMax
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> CapsuleSegmentedButton(
    options: List<SegmentOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: SegmentStyle = SegmentStyle.Connected,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: PaddingValues? = null,
    iconSize: Dp = 24.dp,
    iconSpacing: Dp = ToggleButtonDefaults.IconSpacing,
    labelStyle: TextStyle? = null,
    /**
     * Connected style only: every segment keeps its full hug-content
     * width (nothing can truncate) and additionally gains an equal
     * share of the row's leftover width, so no segment hoards the
     * leftover (used by the config-manage tab row).
     */
    distributeLeftover: Boolean = false,
) {
    val selectedIndex = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)

    if (style == SegmentStyle.Track) {
        // 3.18：总开关开时轨道外层底不画——段自己画底（轻透 0.15 / 水滴），轨道再铺
        // 一层 surfaceContainer 就是两层底；fxCapsuleLift 的鼓起边也和段描边重一道，
        // 一并只在总开关关时挂。关时走原样。
        val trackControlOn = com.github.tvbox.osc.ui.theme.ControlThemeStore.isControlThemeEnabled()
        Surface(
            modifier = if (trackControlOn) modifier else modifier.fxCapsuleLift(TrackShape, strength = 0.65f),
            shape = TrackShape,
            color = if (trackControlOn) Color.Transparent else containerColor,
            shadowElevation = 0.dp, // 投影改由 fxCapsuleLift 负责
        ) {
            Row(
                modifier = Modifier.padding(TrackPadding),
                horizontalArrangement = Arrangement.spacedBy(TrackSegmentSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { index, option ->
                    CapsuleToggleButton(
                        option = option,
                        checked = selectedIndex == index,
                        onCheckedChange = { onOptionSelected(option.value) },
                        index = index,
                        count = options.size,
                        style = style,
                        iconSize = iconSize,
                        iconSpacing = iconSpacing,
                        labelStyle = labelStyle,
                        modifier = if (option.hugContent) Modifier else Modifier.weight(1f),
                    )
                }
            }
        }
        return
    }

    if (style == SegmentStyle.Separated) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(SeparatedSegmentSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, option ->
                CapsuleToggleButton(
                    option = option,
                    checked = selectedIndex == index,
                    onCheckedChange = { onOptionSelected(option.value) },
                    index = index,
                    count = options.size,
                    style = style,
                    containerColor = containerColor,
                    iconSize = iconSize,
                    iconSpacing = iconSpacing,
                    labelStyle = labelStyle,
                    modifier = if (option.hugContent) Modifier else Modifier.weight(1f),
                )
            }
        }
        return
    }

    if (distributeLeftover) {
        Layout(
            modifier = modifier,
            content = {
                options.forEachIndexed { index, option ->
                    CapsuleToggleButton(
                        option = option,
                        checked = selectedIndex == index,
                        onCheckedChange = { onOptionSelected(option.value) },
                        index = index,
                        count = options.size,
                        style = style,
                        containerColor = containerColor,
                        contentPadding = contentPadding,
                        iconSize = iconSize,
                        iconSpacing = iconSpacing,
                        labelStyle = labelStyle,
                    )
                }
            },
        ) { measurables, constraints ->
            // 3.19：去缝——段与段之间不留间距，贴在一起成一根整条。
            val gapPx = 0
            // logtest40: this Compose version throws
            // IllegalStateException if a Measurable is measured twice
            // in one pass, so the content widths come from an
            // intrinsic probe ONLY (maxIntrinsicWidth) and each child
            // is then measured EXACTLY ONCE at its fixed target
            // width. The logtest39 loose-probe + re-measure sequence
            // crashed config-manage entry on every launch.
            val contentWidths = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
            val (targetWidths, rowWidth) = computeDistributedWidths(
                contentWidths = contentWidths,
                gapPx = gapPx,
                maxWidth = constraints.maxWidth,
            )
            val placeables = measurables.mapIndexed { index, measurable ->
                measurable.measure(
                    Constraints(
                        minWidth = targetWidths[index],
                        maxWidth = targetWidths[index],
                        minHeight = 0,
                        maxHeight = constraints.maxHeight,
                    ),
                )
            }
            // The reported size must satisfy the incoming constraints
            // in both axes, or the first layout throws.
            val width = rowWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = (placeables.maxOfOrNull { it.height } ?: 0)
                .coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                var x = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(x, 0)
                    x += placeable.width + gapPx
                }
            }
        }
        return
    }

    Row(
        modifier = modifier,
        // 3.19：去缝——段间距改 0.dp，贴在一起成一根整条。
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            CapsuleToggleButton(
                option = option,
                checked = selectedIndex == index,
                onCheckedChange = { onOptionSelected(option.value) },
                index = index,
                count = options.size,
                style = style,
                containerColor = containerColor,
                contentPadding = contentPadding,
                iconSize = iconSize,
                iconSpacing = iconSpacing,
                labelStyle = labelStyle,
                modifier = if (option.hugContent) Modifier else Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> CapsuleToggleButton(
    option: SegmentOption<T>,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
    style: SegmentStyle,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentPadding: PaddingValues? = null,
    iconSize: Dp = 24.dp,
    iconSpacing: Dp = ToggleButtonDefaults.IconSpacing,
    labelStyle: TextStyle? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val animFactor = com.github.tvbox.osc.ui.theme.LocalAnimFactor.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = if (animFactor <= 0f) {
            androidx.compose.animation.core.snap()
        } else {
            spring(
                dampingRatio = 0.45f,
                stiffness = Spring.StiffnessMediumLow * animSpeedScale(animFactor),
            )
        },
        label = "capsuleScale",
    )

    // 1.14：胶囊风格选项已删，水光 0-400 一根滑杆走四段；选中段满底提亮（与胶囊组一致）。
    val controlOn = com.github.tvbox.osc.ui.theme.ControlThemeStore.isControlThemeEnabled()
    val capBase = capsuleGroupBaseColor()
    val capSelectedBase = brighten(capBase, 1.5f)
    val capContent = capsuleGroupContentColor()
    val capSelectedContent = capsuleGroupSelectedContentColor()
    val capGloss = capsuleGroupWaterGloss()
    // 3.19 描边颜色：段描边改读胶囊组描边颜色（0 = 跟随主题强调色）；透明度不变
    //（0.35 / 0.8）。描边仍在 lightDeco 同一链里与底同一 bounds、同一形状一次画完，不另起一层。
    val capBorder = capsuleGroupBorderColor().copy(alpha = 0.35f)
    val capSelectedBorder = brighten(capsuleGroupBorderColor(), 1.5f).copy(alpha = 0.8f)
    // 3.19（补）：容器色一律透明——底色改由下面 lightDeco 一道画（与描边同一 bounds、
    // 同一形状），ToggleButton 自身 Surface 不再另画一层底。之前水滴水光=0 时
    // 平板底、轻透时 0.15 淡底都由 Surface 画，与 lightDeco 不是一路，底色层
    // （长方形框）与胶囊层（正方形框）重叠不到一起、中间留缝。只影响总开关开的路；
    // 关时走另一套容器色，不动。
    val capContainerColor = Color.Transparent
    // 3.18(C)：选中段底色改由修饰符显式画（见下面 lightDeco），ToggleButton 自身的
    // checkedContainerColor 一律改透明，避免双画。根因（M3 1.5.0-alpha29 ToggleButtonKt
    // 反编译实证）：ToggleButton 自身是包一层 Surface 画 checkedContainerColor，
    // 底色与 lightDeco 描边不是同一路画出来的，选中底没铺满整段、外面露一圈框。
    val capCheckedContainerColor = Color.Transparent
    @Composable
    fun lightDeco(shape: Shape): Modifier =
        if (controlOn) {
            Modifier.border(1.dp, if (checked) capSelectedBorder else capBorder, shape)
                .then(
                    // 1.14：胶囊风格选项已删，水光 0-400 一根滑杆走四段；选中段用提亮底色、未选中段用组底色。
                    Modifier.capsuleGlossByWater(shape, if (checked) capSelectedBase else capBase, capGloss, option.label.hashCode())
                )
        } else Modifier

    val segmentModifier = modifier
        .semantics { role = Role.RadioButton }
        .scale(scale)

    if (style == SegmentStyle.Track) {
        ToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = segmentModifier.then(lightDeco(TrackShape)),
            shapes = ToggleButtonShapes(
                shape = TrackShape,
                pressedShape = TrackShape,
                checkedShape = TrackShape,
            ),
            colors = if (controlOn) {
                ToggleButtonDefaults.colors(
                    containerColor = capContainerColor,
                    contentColor = capContent,
                    checkedContainerColor = capCheckedContainerColor,
                    checkedContentColor = capSelectedContent,
                )
            } else ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            elevation = null,
            contentPadding = TrackContentPadding,
            interactionSource = interactionSource,
        ) {
            SegmentContent(option, MaterialTheme.typography.labelSmall, iconSize, iconSpacing, labelStyle)
        }
    } else if (style == SegmentStyle.Separated) {
        ToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = segmentModifier.then(lightDeco(TrackShape)),
            shapes = ToggleButtonShapes(
                shape = TrackShape,
                pressedShape = TrackShape,
                checkedShape = TrackShape,
            ),
            colors = if (controlOn) {
                ToggleButtonDefaults.colors(
                    containerColor = capContainerColor,
                    contentColor = capContent,
                    checkedContainerColor = capCheckedContainerColor,
                    checkedContentColor = capSelectedContent,
                )
            } else ToggleButtonDefaults.colors(containerColor = containerColor),
            contentPadding = SeparatedContentPadding,
            interactionSource = interactionSource,
        ) {
            SegmentContent(option, MaterialTheme.typography.bodySmall, iconSize, iconSpacing, labelStyle)
        }
    } else {
        val connectedShapes = when (index) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        }
        val connectedColors = if (controlOn) {
            ToggleButtonDefaults.colors(
                containerColor = capContainerColor,
                contentColor = capContent,
                checkedContainerColor = capCheckedContainerColor,
                checkedContentColor = capSelectedContent,
            )
        } else ToggleButtonDefaults.colors(containerColor = containerColor)
        if (contentPadding != null) {
            ToggleButton(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = segmentModifier.then(lightDeco(connectedShapes.shape)),
                shapes = connectedShapes,
                colors = connectedColors,
                contentPadding = contentPadding,
                interactionSource = interactionSource,
            ) {
                SegmentContent(option, MaterialTheme.typography.bodySmall, iconSize, iconSpacing, labelStyle)
            }
        } else {
            ToggleButton(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = segmentModifier.then(lightDeco(connectedShapes.shape)),
                shapes = connectedShapes,
                colors = connectedColors,
                interactionSource = interactionSource,
            ) {
                SegmentContent(option, MaterialTheme.typography.bodySmall, iconSize, iconSpacing, labelStyle)
            }
        }
    }
}

@Composable
private fun <T> SegmentContent(
    option: SegmentOption<T>,
    badgeStyle: TextStyle,
    iconSize: Dp = 24.dp,
    iconSpacing: Dp = ToggleButtonDefaults.IconSpacing,
    labelStyle: TextStyle? = null,
) {
    if (option.icon != null) {
        Icon(imageVector = option.icon, contentDescription = null, modifier = Modifier.size(iconSize))
        Spacer(Modifier.size(iconSpacing))
    } else if (option.iconPainter != null) {
        Icon(painter = option.iconPainter, contentDescription = null, modifier = Modifier.size(iconSize))
        Spacer(Modifier.size(iconSpacing))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = option.label,
            style = labelStyle ?: TextStyle.Default,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        option.badge?.let { badge ->
            Text(
                text = badge,
                style = badgeStyle,
                color = LocalContentColor.current.copy(alpha = 0.75f),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}
