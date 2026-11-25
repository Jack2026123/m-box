package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.theme.ControlGroup
import com.github.tvbox.osc.ui.theme.ControlTheme
import com.github.tvbox.osc.ui.theme.ControlThemePresets
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.EffectsState
import com.github.tvbox.osc.ui.theme.resolveUserKind

/**
 * 3.07 百变控件 · 第一阶段地基：共享按钮包装。
 *
 * 底子视觉（用户 2026-11-14 参考图定，六套预设统一）：
 * 深 slate 紫灰底（主题表面色加深推导，约 #3A3A48）+ 白字 + 干净；
 * 配色模式只管“强调色” accent——未选中时以 0.35 透明度的细描边
 * 露出强调色，选中时底色提亮 + 强调色提亮后的浅色描边 + 稍大。
 * 六套预设仍一眼分得开：大小、形状、强调色各不相同。
 *
 * 全 App 的按钮（下一阶段接线）都走这里：底色 / 字色 / 形状 /
 * 大小 / 字号按 [resolveButtonStyle] 的结果来，即按组主题
 * 解析后的结果。切预设即写 KV + 更新
 * [ControlThemeStore] 的 state，读 state 的按钮即时变；
 * 换主题后颜色取自当时的 [MaterialTheme.colorScheme]，跟着变；
 * 重启后 KV 恢复，同一预设还在。
 *
 * buttonId 稳定：同一按钮换主题、重启后 id 不变（调用方写死
 * 在调用处，如 "home_search"、"player_play"；不要用动态生成的 id）。
 * 彩色版轮换只认 buttonId 的哈希，id 不变则颜色不变。
 */

/** 组主题解析后的结果（颜色一律取自当前 colorScheme，不写死固定色）。 */
data class ResolvedButtonStyle(
    val buttonId: String,
    val sizeScale: Float,
    val fontScale: Float,
    val shape: Int,
    val cornerMode: Int,
    /** 底色：胶囊组 = 组底色（默认 res 未选中底 #383C73），卡片组 = 深 slate 紫灰（主题表面色加深推导）。 */
    val backgroundColor: Color,
    /** 字色：统一白字（深 slate 底上高对比）。 */
    val contentColor: Color,
    /** 选中态字色：胶囊组 = 深字/白字（按选中底亮度取，亮底上是深字）；卡片组 = contentColor（海报标题不改）。 */
    val selectedContentColor: Color = contentColor,
    /** 强调色：组自定义色（有就用），没有跟主题主色；未选中时以细描边露出，选中时提亮成浅色描边。 */
    val accentColor: Color = Color.White,
    val buttonShape: Shape,
    /** 选中态：胶囊组 = 选中颜色（跟主题主色），卡片组 = 底色提亮 1.5 倍。 */
    val selectedBackgroundColor: Color,
    /** 选中态：浅色描边颜色（强调色提亮后）。 */
    val selectedBorderColor: Color,
    /** 未选中态：强调色描边（0.8 透明度），让各预设的强调色一眼可见。 */
    val borderColor: Color = Color.Transparent,
    /** 选中态：整体稍大倍率。 */
    val selectedScale: Float = 1.05f,
    /** 水光强度（0..400，组值）：高光层用。 */
    val waterGloss: Int = 120,
    /** 胶囊风格（"water" 水滴 / "light" 轻透 / "raised" 纽扣，组值；只对胶囊类生效，卡片组不读）。 */
    val capsuleStyle: String = ControlTheme.CAPSULE_STYLE_WATER,
    /** 圆角倍率（0..2，组值）：跟主题分支的圆角半径乘它（3.18 接线，滑杆才看得出变化）。 */
    val cornerScale: Float = 1.0f,
)

/** 提亮：选中态底色用，明度乘系数后钳制。3.17：CapsuleSegmentedButton 选中底复用，改 internal。 */
internal fun brighten(color: Color, factor: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * 胶囊版深 slate 紫灰底（用户 2026-11-14 参考图定，约 #3A3A48）：
 * 以主题表面色为基调加深——色相往 slate 紫（约 242°）靠 35%，
 * 饱和度收进 0.10–0.20，明度压到 0.24–0.34；换主题后仍是深 slate，但色调跟着主题走。
 * 3.17 轻透胶囊：胶囊组底就是这层 slate（或组底色），只画 0.15 透明度；
 * 3.13 的 waterSlate 平板色相/明度平移已删，不再两套颜色打架。
 */
private fun capsuleSlate(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    val slateHue = 242f
    var delta = slateHue - hsv[0]
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    hsv[0] = (hsv[0] + delta * 0.35f + 360f) % 360f
    hsv[1] = (hsv[1] * 0.5f).coerceIn(0.10f, 0.20f)
    hsv[2] = (hsv[2] * 0.55f).coerceIn(0.24f, 0.34f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 按形状值 + 圆角模式解析 Shape：多边形 / 心形走 cardShape，矩形走圆角（跟主题 / 直角 / pill）。ControlSurface 复用。
 * 3.18：跟主题分支的圆角半径乘组 cornerScale（圆角倍率滑杆）；默认全局值，老调用点行为不变；
 * 直角 / 大圆角分支不动（大圆角是 percent=50，倍率乘不上去）。 */
@Composable
fun resolveButtonShape(shape: Int, cornerMode: Int, cornerScale: Float = EffectsState.config.cornerScale): Shape {
    return when (shape) {
        // 多边形 / 心形 / 圆形：形状本身就是轮廓，圆角模式不再叠加。
        // 心形只作为单按钮可选项（会裁掉文字），不做预设默认。
        5, 6, 8, 99, 100 -> cardShape(shape)
        else -> when (cornerMode) {
            ControlTheme.CORNER_SQUARE -> RoundedCornerShape(0.dp)
            ControlTheme.CORNER_PILL -> RoundedCornerShape(percent = 50)
            else -> RoundedCornerShape(12.dp * cornerScale)
        }
    }
}

/**
 * 解析出按钮的全部样式（3.14：单元素覆盖已删，一律读组值）。
 * 颜色一律从 [MaterialTheme.colorScheme] 取（或由它色相偏移推导），
 * 不写死固定色——换主题后控件颜色跟着变。
 *
 * 覆盖范围门控（3.07 第二阶段，3.11 按组取底）：[kind] 在当前主题的
 * coveredKinds 里才套该组的当前主题（[ControlThemeStore.groupConfig]，
 * 组由 [resolveUserKind] 定）；不在时回落到 [ControlThemePresets.DEFAULT]
 * 的大小 / 形状 / 配色，不套当前主题。
 * [kind] 默认 BUTTON，[ControlButton] 传 BUTTON，[ControlSurface] 传自己的 kind。
 */
@Composable
fun resolveButtonStyle(buttonId: String, kind: ControlElementKind = ControlElementKind.BUTTON): ResolvedButtonStyle {
    val covered = ControlThemeStore.isKindCovered(kind)
    val theme = if (covered) ControlThemeStore.groupConfig(resolveUserKind(kind, buttonId)) else ControlThemePresets.DEFAULT
    val scheme = MaterialTheme.colorScheme
    // 3.14：单元素覆盖已删，样式一律读组值。
    val sizeScale = theme.sizeScale.coerceIn(ControlThemeStore.SIZE_RANGE)
    val fontScale = theme.fontScale.coerceIn(ControlThemeStore.FONT_SCALE_RANGE)
    val shape = theme.shape
    // 3.11 胶囊边框形状走 cornerMode：EPISODE / LINE / CHIP 的
    // 边框只认 cornerMode（resolveSurfaceShape），shape 的多边形会裁掉文字。
    val cornerMode = theme.cornerMode
    val customArgb = theme.customColorArgb

    // 底子视觉统一（用户 2026-11-14 参考图定）：底色六套共用深 slate 紫灰
    // （主题表面色加深推导，约 #3A3A48，换主题后仍是深 slate、色调跟着走），
    // 字色默认白字；字体颜色覆盖 / 组值非 0 时用自定义字色。
    // 3.25 底色改 res：胶囊组底色 = res 未选中底 #383C73——0（跟主题 slate）与旧默认
    // #6F74F2 都落到 res（slate 灰紫永远出不了参考图的鲜艳蓝紫）；真正自定义过
    // 的底色（非 0、非旧默认）保留。卡片组一律 slate（海报底只是图片未加载时的衬底）。
    // 3.17 轻透胶囊：胶囊组底只画 0.15 透明度（controlButtonModifier / ControlButton /
    // controlSurfaceModifier 三处同款配方：淡底 + 1dp 描边 + 顶部亮面），水滴实色底已删；
    // 卡片组（海报）底仍是平板（图片未加载时的衬底），不变色。
    val waterGloss = theme.waterGloss.coerceIn(0, 400)
    val isCapsuleKind = resolveUserKind(kind, buttonId) == ControlGroup.CAPSULE
    // 1.14：胶囊风格已删，风格分支改按当前水光段取——0-50 纽扣、50-100 轻透、100-150 立体、150-400 水滴。
    val isWaterStyle = isCapsuleKind && waterGloss >= 150
    val isLightStyle = isCapsuleKind && waterGloss in 50..99
    val isRaisedStyle = isCapsuleKind && (waterGloss < 50 || waterGloss in 100..149)
    val backgroundColor: Color = if (isCapsuleKind) {
        if (theme.baseColorArgb != 0 && theme.baseColorArgb != ControlTheme.OLD_DEFAULT_BASE_ARGB) {
            Color(theme.baseColorArgb)
        } else {
            Color(ControlTheme.RES_CHIP_BASE_ARGB)
        }
    } else {
        capsuleSlate(scheme.surfaceContainerHigh)
    }
    val fontColorArgb = theme.fontColorArgb
    // 3.41 纽扣样式：字色按底色亮度取——自定义字色时不动；亮底上白字看不见，改深字
    // （与 selectedContentColor 同款 0.5 阈值）；水滴/轻透仍是白字。
    val contentColor: Color = if (fontColorArgb != 0) {
        Color(fontColorArgb)
    } else if (isRaisedStyle && android.graphics.Color.luminance(backgroundColor.toArgb()) > 0.5f) {
        Color(0xFF1A1A1A.toInt())
    } else {
        Color.White
    }
    // 3.30 选中底色跟主题走：胶囊组选中态底色改用「胶囊选中颜色」当底（0 或透明回落
    // 主题主色），不再回落暖金；真正自定义过的选中色保留。
    // 选中字也随之改深色——亮底上亮字看不见；底亮用深字、底暗用白字（按亮度取），
    // 只改胶囊组，卡片组不动。
    // 3.17 选中颜色单独可调：胶囊组读组值 selectedContentColorArgb——0 = 跟随主题
    // （解析处映回主题主色），非 0 = 自定义选中颜色；0 值与全透明值都回落主色，
    // 不会画出透明/黑字。
    val selectedGold: Color = run {
        val argb = theme.selectedContentColorArgb
        if (argb != 0 && (argb ushr 24) != 0) Color(argb) else scheme.primary
    }
    val selectedContentColor: Color = if (isCapsuleKind) {
        if (android.graphics.Color.luminance(selectedGold.toArgb()) > 0.5f) Color(0xFF1A1A1A.toInt()) else Color.White
    } else {
        contentColor
    }
    // 3.17：胶囊配色已删——强调色只剩两档：有自定义强调色用自定义，没有就跟主题主色。
    val accentColor: Color = if (customArgb != 0) Color(customArgb) else scheme.primary
    // 3.19 描边颜色：胶囊组读组值 borderColorArgb——0 = 跟随主题强调色，
    // 非 0 = 自定义描边颜色；卡片组不读它，描边仍走强调色。
    // 选中 / 未选中透明度都是 0.8（3.25 未选中由 0.35 提到 0.8——0.35 在浅色底上
    // 几乎看不见），只换颜色。
    val borderAccentColor: Color =
        if (isCapsuleKind && theme.borderColorArgb != 0) Color(theme.borderColorArgb) else accentColor

    // 选中态统一三件套：
    // 3.25：胶囊组选中底 = 选中颜色，卡片组仍是底色提亮 1.5 倍；
    // 强调色提亮后的浅色描边（0.8 透明度）+ 稍大 1.05 倍。
    val selectedBackgroundColor: Color = if (isCapsuleKind) {
        if (isLightStyle) selectedGold.copy(alpha = 0.20f) else selectedGold
    } else brighten(backgroundColor, 1.5f)
    // 3.25 描边改 res（只在描边颜色没自定义时；自定义描边仍走上面 0.8 那套）：
    // 水滴未选中 #9B9FD0 24%、选中 #73DDF7 80%；清透未选中 40% 白；
    // 清透选中与卡片组仍走强调色提亮 0.8。res 选中底 #3A7088 只作参考——
    // 本 App 选中底仍是选中颜色（3.25 第 3 项不动），描边用 res 选中那支亮青。
    val useResStroke = isCapsuleKind && theme.borderColorArgb == 0
    val selectedBorderColor: Color = if (useResStroke && isWaterStyle) {
        Color(ControlTheme.RES_CHIP_SELECTED_STROKE_ARGB)
    } else {
        brighten(borderAccentColor, 1.5f).copy(alpha = 0.8f)
    }
    val borderColor: Color = when {
        useResStroke && isWaterStyle -> Color(ControlTheme.RES_CHIP_STROKE_ARGB)
        useResStroke && isLightStyle -> Color(ControlTheme.RES_LIGHT_STROKE_ARGB)
        // 3.41 纽扣样式：描边只作 faint 边缘——亮底用黑 10%、暗底用白 15%；
        // 描边自定义过走 else 分支（跟主题强调色）。
        // 3.42 黑底适配：暗底描边白 15% → 35%，黑底上钮边看得见；亮底黑 10% 不动
        //（浅色底靠外圈影子认边）。
        useResStroke && isRaisedStyle -> if (android.graphics.Color.luminance(backgroundColor.toArgb()) > 0.5f) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.35f)
        }
        else -> borderAccentColor.copy(alpha = 0.8f)
    }

    return ResolvedButtonStyle(
        buttonId = buttonId,
        sizeScale = sizeScale,
        fontScale = fontScale,
        shape = shape,
        cornerMode = cornerMode,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        selectedContentColor = selectedContentColor,
        accentColor = accentColor,
        buttonShape = resolveButtonShape(shape, cornerMode, theme.cornerScale.coerceIn(EffectsState.CORNER_RANGE)),
        selectedBackgroundColor = selectedBackgroundColor,
        selectedBorderColor = selectedBorderColor,
        borderColor = borderColor,
        selectedScale = 1.05f,
        waterGloss = waterGloss,
        capsuleStyle = ControlTheme.normalizeCapsuleStyle(theme.capsuleStyle),
        cornerScale = theme.cornerScale.coerceIn(EffectsState.CORNER_RANGE),
    )
}

/**
 * 胶囊类元素写死白字的地方改走这里：组字体颜色（没设 = 白字）。
 * Modifier 改不了字色，调用方 Text 的 color 参数读它。
 * [ControlButton] / [ControlSurface] 内部已用 [ResolvedButtonStyle.contentColor]，不用再调。
 */
@Composable
fun controlContentColor(elementId: String, kind: ControlElementKind = ControlElementKind.BUTTON): Color =
    resolveButtonStyle(elementId, kind).contentColor

/**
 * 胶囊类元素选中态的字色：调用方有 selected 状态的 Text / chip 读它
 * （FilterChip 的 selectedLabelColor、线路卡 / 选集卡选中时的标题）。
 * 未选中态仍走 [controlContentColor]；卡片组选中字 = 平时字，不改。
 */
@Composable
fun controlSelectedContentColor(elementId: String, kind: ControlElementKind = ControlElementKind.BUTTON): Color =
    resolveButtonStyle(elementId, kind).selectedContentColor

/**
 * 3.17 轻透胶囊共享值：设置卡 / 分段钮等非按钮元素复用胶囊组底色与强调色，
 * 不走 [resolveButtonStyle]（它有按元素 id 的覆盖门控）。
 * 底色 = 组 baseColorArgb（真正自定义过的用它；0 与旧默认 #6F74F2 落到 res 底 #383C73，
 * 与 [resolveButtonStyle] 同款规则）；0 时走 slate 的旧义已废；
 * 强调色 = 组 customColorArgb（非 0 时用它），没有跟主题主色；
 * 字色 = 组 fontColorArgb（非 0 时用它），没有走白字；
 * 选中字色 = 组 selectedContentColorArgb（0/透明回落主题主色）。
 */
@Composable
fun capsuleGroupBaseColor(): Color {
    val groupTheme = ControlThemeStore.groupConfig(ControlGroup.CAPSULE)
    val argb = groupTheme.baseColorArgb
    return if (argb != 0 && argb != ControlTheme.OLD_DEFAULT_BASE_ARGB) Color(argb)
    else Color(ControlTheme.RES_CHIP_BASE_ARGB)
}

@Composable
fun capsuleGroupAccentColor(): Color {
    val groupTheme = ControlThemeStore.groupConfig(ControlGroup.CAPSULE)
    return if (groupTheme.customColorArgb != 0) Color(groupTheme.customColorArgb)
    else MaterialTheme.colorScheme.primary
}

@Composable
fun capsuleGroupContentColor(): Color {
    val groupTheme = ControlThemeStore.groupConfig(ControlGroup.CAPSULE)
    if (groupTheme.fontColorArgb != 0) return Color(groupTheme.fontColorArgb)
    // 1.14：胶囊风格已删，字色改按当前水光段取——纽扣段（0-50）与立体段（100-150）是实色槽底，
    // 亮底上白字看不见，改深字；轻透 / 水滴段仍是白字。本函数只分段钮用，不影响别处。
    val gloss = groupTheme.waterGloss.coerceIn(0, 400)
    if ((gloss < 50 || gloss in 100..149) &&
        android.graphics.Color.luminance(capsuleGroupBaseColor().toArgb()) > 0.5f
    ) return Color(0xFF1A1A1A.toInt())
    return Color.White
}

@Composable
fun capsuleGroupSelectedContentColor(): Color {
    val groupTheme = ControlThemeStore.groupConfig(ControlGroup.CAPSULE)
    val argb = groupTheme.selectedContentColorArgb
    return if (argb != 0 && (argb ushr 24) != 0) Color(argb) else MaterialTheme.colorScheme.primary
}

/**
 * 胶囊组描边颜色：组 borderColorArgb（非 0 时用它），0 = 跟随主题强调色。
 * 分段钮 lightDeco 与设置页描边色块读它；描边本身（1dp、0.8 透明度）不变。
 */
@Composable
fun capsuleGroupBorderColor(): Color {
    val groupTheme = ControlThemeStore.groupConfig(ControlGroup.CAPSULE)
    return if (groupTheme.borderColorArgb != 0) Color(groupTheme.borderColorArgb)
    else capsuleGroupAccentColor()
}

@Composable
fun capsuleGroupWaterGloss(): Int =
    ControlThemeStore.groupConfig(ControlGroup.CAPSULE).waterGloss.coerceIn(0, 400)

/** 胶囊组风格（"water" 水滴 / "light" 轻透 / "raised" 纽扣；非法值归一到水滴），供分段钮等复用。 */
@Composable
fun capsuleGroupStyle(): String =
    ControlTheme.normalizeCapsuleStyle(ControlThemeStore.groupConfig(ControlGroup.CAPSULE).capsuleStyle)

/**
 * 给包不下完整按钮的地方用（如 LyricControlRow 的 Text 项、NavigationBarItem）：
 * 只做大小 / 颜色 / 形状的 Modifier 叠加——形状裁剪 + slate 淡底 + 按 sizeScale 的内边距。
 * 字号不在 Modifier 里（Modifier 改不了字号），要改字号用 [ControlButton]；
 * 完整底色也不在这里（宿主已有自己的底），要完整底色同样用 [ControlButton]。
 * 未选中时也有一圈 0.35 透明度的强调色细描边（各预设强调色可见）；
 * selected = true 时用选中态：提亮 slate 底色 + 强调色提亮后的浅色描边。
 */
@Composable
fun controlButtonModifier(buttonId: String, selected: Boolean = false, kind: ControlElementKind = ControlElementKind.BUTTON, drawBorder: Boolean = true): Modifier {
    // 3.09 更正：总开关关时空操作——不裁剪、不画淡底、不画描边、不垫内边距（恢复最初的 AVBox）。
    if (!ControlThemeStore.isControlThemeEnabled()) return Modifier
    // 3.62 播放器开关：PLAYER 种类的元素在覆盖范围里没勾选时，不画胶囊（空 Modifier，
    // 恢复最初的 AVBox），而不是回落默认主题——默认主题本身也是水滴胶囊，关了开关必须真没胶囊。
    if (kind == ControlElementKind.PLAYER && !ControlThemeStore.isKindCovered(ControlElementKind.PLAYER)) return Modifier
    val style = resolveButtonStyle(buttonId, kind)
    val isCapsuleKind = resolveUserKind(kind, buttonId) == ControlGroup.CAPSULE
    // 1.14：胶囊风格选项已删，水光 0-400 一根滑杆走四段（capsuleGlossByWater，自己画底）；
    // 卡片组/海报走原样平板底 + 顶部亮面。
    val baseForState = if (selected) style.selectedBackgroundColor else style.backgroundColor
    val result: Modifier = Modifier
        .clip(style.buttonShape)
        .then(
            if (isCapsuleKind) {
                Modifier.capsuleGlossByWater(style.buttonShape, baseForState, style.waterGloss, buttonId.hashCode())
            } else {
                Modifier.background(
                    if (selected) style.selectedBackgroundColor else style.backgroundColor.copy(alpha = 0.15f),
                    style.buttonShape,
                )
            }
        )
        // drawBorder = false 时不画这道 1dp 描边（底色 / 亮面 / 选中提亮都不动）；
        // 默认 true。描边颜色走组 borderColorArgb（0 = 跟随主题强调色），见 resolveButtonStyle。
        .then(
            if (drawBorder) Modifier.border(
                1.dp,
                if (selected) style.selectedBorderColor else style.borderColor,
                style.buttonShape,
            ) else Modifier
        )
        .padding(
            horizontal = (8.dp * style.sizeScale),
            vertical = (4.dp * style.sizeScale),
        )
        // 1.14：胶囊类的亮面已在 morph 里；卡片组/海报仍走顶部一道亮面（组值），0 = 不画。
        .then(
            if (!isCapsuleKind && style.waterGloss > 0) {
                Modifier.waterGlossSheen(style.buttonShape, style.waterGloss / 200f)
            } else {
                Modifier
            },
        )
    return result
}

/**
 * 吃控件主题的按钮。
 * 长按手势预留 [onLongPress] 参数，默认空（null = 不接长按）：
 * 下一阶段接编辑 UI。长按不吞正常点按——有长按时走 Surface +
 * combinedClickable（点按仍走 onClick），无长按时走标准 Button。
 * selected = true 时用选中态：slate 底色提亮 + 强调色提亮后的浅色描边 + 稍大；
 * 未选中时也有一圈强调色细描边（0.35 透明度），各预设的强调色一眼可见。
 * [kind] 默认 BUTTON；不在当前主题覆盖范围里时回落默认主题（见 [resolveButtonStyle]）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControlButton(
    buttonId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    selected: Boolean = false,
    kind: ControlElementKind = ControlElementKind.BUTTON,
    content: @Composable RowScope.() -> Unit,
) {
    // 3.09 更正：总开关关时随心所欲完全不生效——退回普通 Material3 Button（默认配色与形状），
    // 不画 slate 底与强调色描边，不放大字号；长按编辑由 requestEdit 自身门控，不触发。
    if (!ControlThemeStore.isControlThemeEnabled()) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            content = content,
        )
        return
    }
    val style = resolveButtonStyle(buttonId, kind)
    // 1.14：胶囊风格选项已删，水光 0-400 一根滑杆走四段；胶囊类容器色一律透明、底由 morph 一道画。
    val isCapsuleKind = resolveUserKind(kind, buttonId) == ControlGroup.CAPSULE
    val baseForState = if (selected) style.selectedBackgroundColor else style.backgroundColor
    val selScale = if (selected) style.selectedScale else 1f
    val containerColor = when {
        isCapsuleKind -> Color.Transparent
        selected -> style.selectedBackgroundColor
        else -> style.backgroundColor
    }
    val contentPadding = PaddingValues(
        horizontal = (16.dp * style.sizeScale * selScale),
        vertical = (8.dp * style.sizeScale * selScale),
    )
    val textStyle = MaterialTheme.typography.labelLarge.copy(
        fontSize = MaterialTheme.typography.labelLarge.fontSize * style.fontScale,
    )
    val baseModifier = modifier.heightIn(min = (40.dp * style.sizeScale * selScale))
    // 1.14：胶囊类走 morph（底 + 亮面一道画）；卡片组/海报仍走顶部一道亮面。
    val borderedModifier = baseModifier.border(
        1.dp,
        if (selected) style.selectedBorderColor else style.borderColor,
        style.buttonShape,
    ).then(
        when {
            isCapsuleKind -> Modifier.capsuleGlossByWater(style.buttonShape, baseForState, style.waterGloss, buttonId.hashCode())
            style.waterGloss > 0 -> Modifier.waterGlossSheen(style.buttonShape, style.waterGloss / 200f)
            else -> Modifier
        },
    )
    CompositionLocalProvider(LocalTextStyle provides textStyle) {
        // 3.16：选中态字走 selectedContentColor（胶囊组 = 深字，亮底上可读），未选中走 contentColor。
        val textColor = if (selected) style.selectedContentColor else style.contentColor
        if (onLongPress == null) {
            Button(
                onClick = onClick,
                modifier = borderedModifier,
                enabled = enabled,
                shape = style.buttonShape,
                contentPadding = contentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = textColor,
                    disabledContainerColor = containerColor.copy(alpha = 0.38f),
                    disabledContentColor = textColor.copy(alpha = 0.38f),
                ),
                content = content,
            )
        } else {
            Surface(
                modifier = borderedModifier,
                shape = style.buttonShape,
                color = containerColor,
                contentColor = textColor,
            ) {
                Row(
                    modifier = Modifier
                        .combinedClickable(
                            enabled = enabled,
                            onClick = onClick,
                            onLongClick = onLongPress,
                        )
                        .padding(contentPadding),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }
}
