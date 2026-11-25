package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.theme.ControlTheme
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.EffectsState

/**
 * 3.07 百变控件 · 第一阶段补充：共享“控件表面”包装。
 *
 * 把同一套底子视觉（轻透淡底 + 白字 + 形状 + 选中提亮描边，
 * 见 [resolveButtonStyle]）接到非按钮元素上：卡片、海报、选集、
 * 线路、筛选 chip。按钮本身继续走 [ControlButton]，本文件不重复
 * 造一套颜色解析——颜色、大小、字号、形状全部复用
 * [resolveButtonStyle]（elementId 即原来的 buttonId）。
 *
 * 按种类收敛（下一阶段三页接线时调用方按种类传 kind）：
 * - BUTTON / CARD：完整形状裁剪（多边形 / 心形也裁剪）+ 全底 slate；
 * - POSTER：保持宽高比——不设宽高、不拉伸海报图，大小缩放只作用于
 *   圆角 / 描边 / 字号；slate 底只作图片未加载时的衬底（图片盖住它），
 *   形状裁剪 + 描边照常；
 * - EPISODE / LINE / CHIP：只用胶囊或圆角矩形（按预设圆角模式走）——
 *   多边形 / 心形会裁掉文字，解析时退回圆角矩形（见 [resolveSurfaceShape]）。
 *
 * 元素 id 稳定方案（换主题、重启后覆盖还在；列表顺序变化也不丢）：
 * - 按钮：沿用 buttonId（调用处写死的稳定 id，如 "home_search"）；
 * - 卡片：[cardElementId]，用内容 id（如 vodId），"card_" + 内容 id；
 * - 海报：[posterElementId]，用内容 id，"poster_" + 内容 id；
 *   [posterElementIdByPosition]（"poster_pos_" + 位置）不稳定——列表顺序
 *   一变覆盖就串位，仅在内容无 id 时兜底，不推荐；
 * - 选集：[episodeElementId]，用剧集序号（第 N 集的 N，如 "episode_12"），
 *   不是列表位置——集数本身稳定，顺序变化不影响；
 * - 线路：[lineElementId]，用源 key（如 spider key），"line_" + 源 key；
 * - 筛选 chip：[filterChipElementId]，用筛选值原文，"chip_" + 筛选值。
 * - 筛选面板 chip：[filterSheetChipElementId]，组 key + 值 key，
 *   "filterchip_" + 组 + "_" + 值（去空白）；跟首页分类 chip 的
 *   [filterChipElementId] 前缀不同，不会串覆盖。
 * - 源胶囊：[sourceCapsuleElementId]，用源 key，"source_" + 源 key；空 key 时兜底 "source_unknown"。
 *
 * 样式一律读组值（3.14 单元素覆盖已删）：_color 是组自定义强调色
 * （底色统一 slate，不改底色），_size 缩放内边距 / 最小高 /
 * 字号，_shape 是组形状（EPISODE / LINE / CHIP 的多边形 / 心形
 * 同样退回圆角矩形）。
 *
 * 不做三页接线、不做长按编辑弹窗、不做重排 / 隐藏 / 导出（下一阶段）。
 */

/** 控件表面元素种类。3.62 加 PLAYER：播放器按钮单独一种，覆盖范围里可独立开关。 */
enum class ControlElementKind {
    BUTTON,
    CARD,
    POSTER,
    EPISODE,
    LINE,
    CHIP,
    PLAYER,
}

/** 按钮 id 即元素 id（沿用调用处写死的 buttonId）。 */
fun buttonElementId(buttonId: String): String = buttonId

/** 卡片元素 id：内容 id（如 vodId）。 */
fun cardElementId(contentId: String): String = "card_" + contentId

/** 海报元素 id：内容 id（如 vodId）。 */
fun posterElementId(contentId: String): String = "poster_" + contentId

/**
 * 海报元素 id（位置兜底）：仅在内容无 id 时用。
 * 不稳定——列表顺序一变覆盖就串位，能用 [posterElementId] 时不要用它。
 */
fun posterElementIdByPosition(position: Int): String = "poster_pos_" + position

/** 选集元素 id：剧集序号（第 N 集的 N），不是列表位置。 */
fun episodeElementId(episodeNumber: Int): String = "episode_" + episodeNumber

/** 线路元素 id：源 key（如 spider key）。 */
fun lineElementId(sourceKey: String): String = "line_" + sourceKey

/** 筛选 chip 元素 id：筛选值原文。 */
fun filterChipElementId(filterValue: String): String = "chip_" + filterValue

/**
 * 筛选面板 chip 元素 id：组 + 值，"filterchip_" + 组 + "_" + 值。
 * 组取筛选组 key（无 key 时取组名），值取筛选值 key（空白时取显示名）；
 * 组与值先去掉全部空白字符，保证 id 稳定、不含空格；去空白后为空时
 * 用 "group" / "value" 兜底，不会拼出空段。
 */
fun filterSheetChipElementId(group: String, value: String): String {
    val g = group.filter { !it.isWhitespace() }.ifEmpty { "group" }
    val v = value.filter { !it.isWhitespace() }.ifEmpty { "value" }
    return "filterchip_" + g + "_" + v
}

/** 源胶囊元素 id：源 key（如 spider key）；空 key 时兜底 "source_unknown"，不返回空 id。 */
fun sourceCapsuleElementId(key: String): String =
    if (key.isEmpty()) "source_unknown" else "source_" + key

/**
 * 按种类解析形状：CARD / POSTER / BUTTON 走完整形状（含多边形 / 心形）；
 * EPISODE / LINE / CHIP 只用胶囊或圆角矩形——多边形 / 心形会裁掉文字，
 * 不管预设 shape 是什么都退回圆角（圆角模式 pill = 胶囊，直角 = 直角，
 * 跟主题 = 主题圆角 12dp）。
 */
@Composable
private fun resolveSurfaceShape(style: ResolvedButtonStyle, kind: ControlElementKind): Shape {
    return when (kind) {
        ControlElementKind.EPISODE, ControlElementKind.LINE, ControlElementKind.CHIP -> when (style.cornerMode) {
            ControlTheme.CORNER_PILL -> RoundedCornerShape(percent = 50)
            ControlTheme.CORNER_SQUARE -> RoundedCornerShape(0.dp)
            // 3.18：跟主题分支的圆角半径乘组 cornerScale（圆角倍率滑杆）；胶囊组 cornerMode 恒为跟主题，
            // 所以胶囊描边形状只走这一支。appCardShape 读全局值，不用它。
            else -> RoundedCornerShape(12.dp * style.cornerScale)
        }
        else -> style.buttonShape
    }
}

/** 按种类解析内边距：POSTER 不垫内边距（图边缘贴合，不拉伸）；其余按 sizeScale 缩放。 */
private fun surfacePadding(kind: ControlElementKind, style: ResolvedButtonStyle): PaddingValues {
    return when (kind) {
        ControlElementKind.POSTER -> PaddingValues(0.dp)
        ControlElementKind.BUTTON -> PaddingValues(
            horizontal = (16.dp * style.sizeScale),
            vertical = (8.dp * style.sizeScale),
        )
        ControlElementKind.CARD -> PaddingValues(
            horizontal = (12.dp * style.sizeScale),
            vertical = (8.dp * style.sizeScale),
        )
        // 3.23：CHIP 横向边距 12dp×档 → 2dp×档（用户定「胶囊短一点」/「字是小红框，胶囊是长红框」/「字长18胶囊就20」）——
        // 框只比字大一点点：控制层 2dp + chip 自身 2dp，0.5 档每边共 2dp；CHIP 单列，EPISODE/LINE 不动。
        ControlElementKind.CHIP -> PaddingValues(
            horizontal = (2.dp * style.sizeScale),
            vertical = (6.dp * style.sizeScale),
        )
        else -> PaddingValues(
            horizontal = (12.dp * style.sizeScale),
            vertical = (6.dp * style.sizeScale),
        )
    }
}

/**
 * 3.10 海报曲率大方框：海报图片到没到的会话态登记（按元素 id）。
 *
 * slate 底画在 [controlSurfaceModifier] 里（调用方组合作用域），图片
 * 到没到只在 VodPoster 内部知道（onImage 回调），两者隔着 VodCard，
 * 改签名传不进去，改用这张快照态小表搭桥：VodCard 在 onImage 里按
 * 同一个 elementId 上报，[controlSurfaceModifier] 读它（快照态读，
 * 变了自动重组，底随图片到手一起收掉）。
 * 只记“到过”，不记“没到”：同一内容换图时旧标记可能残留，VodPoster
 * 换图会先回平铺加载态，短暂透明一张卡而已，不另清（清的时机和缓存
 * 命中的上报有竞态，反而会把已到手的底再画出来）。
 * 会话态不进 KV，进程死掉即空；条目只有“到过”的海报才有，一张卡
 * 一个布尔值，不设上限（清掉会让已到手的海报底重新露出来）。
 */
internal object PosterLoadState {
    private val loadedIds = mutableStateMapOf<String, Boolean>()
    fun markLoaded(elementId: String) {
        loadedIds[elementId] = true
    }
    fun isLoaded(elementId: String): Boolean = loadedIds[elementId] == true
}

/**
 * 共享控件表面 Modifier：slate 底 + 白字（字色在 [ControlSurface] 里给，
 * Modifier 改不了字色）+ 形状裁剪 + 强调色描边；选中时 slate 底提亮 +
 * 强调色提亮后的浅色描边 + 视觉稍大 1.05 倍（graphicsLayer 缩放，
 * 只影响绘制、不改布局占位，列表项选中不会把邻项顶开）。
 *
 * POSTER：不设宽高、不垫内边距、不拉伸图片；slate 底只作图片未加载
 * 时的衬底，形状裁剪 + 描边照常；大小缩放只作用于圆角 / 描边 / 字号。
 *
 * 3.17 轻透胶囊：胶囊组（EPISODE / LINE / CHIP / BUTTON）底只画 0.15 透明度
 * （选中态满底选中颜色），与 controlButtonModifier 同款配方（淡底 + 1dp 描边 +
 * 顶部亮面）；卡片组（CARD / POSTER，海报）仍是平板底 + [waterGlossSheen]
 * 顶部亮面，不动。水滴实色底已删。强度读该元素的解析后值（组值，见
 * [resolveElementEffects]：waterGloss 字段）；0 = 不画（纯平）。
 * 曲率开且图片已到手时底已收掉，高光也一起收掉——否则矩形高光会把
 * 椭圆海报的四角重新框出来，和 3.10 大阴影框同款问题。
 *
 * 覆盖范围（3.07 第二阶段）：自己的 [kind] 传给 [resolveButtonStyle]，
 * 不在当前主题 coveredKinds 里的种类回落默认主题（大小 / 形状 / 配色全按默认）。
 */
@Composable
fun controlSurfaceModifier(
    elementId: String,
    kind: ControlElementKind = ControlElementKind.CARD,
    selected: Boolean = false,
): Modifier {
    // 3.09 更正：总开关关时空操作——不裁剪、不画 slate 底、不画描边、不垫内边距。
    if (!ControlThemeStore.isControlThemeEnabled()) return Modifier
    val style = resolveButtonStyle(elementId, kind)
    val shape = resolveSurfaceShape(style, kind)
    val selScale = if (selected) style.selectedScale else 1f
    // 3.10 海报曲率大方框：开了曲率且图片已到手时，海报图鼓成椭圆从四角
    // 缩进去，矩形的 slate 底露出来就是截图里那个深灰大圆角矩形框——不是
    // 阴影，是实打实的底色层。这时底不画，椭圆海报直接浮在壁纸上。
    // “开了曲率”走单元素解析后的值（resolveElementEffects：全局关、
    // 单元素开也算开；全局开、单元素明确关也算没开；种类不在 coveredKinds
    // 里时回全局，和 VodPoster 实际画的 warp 是同一份值）。
    // 图片到没到走 PosterLoadState（VodCard 在 onImage 里按同一个 id 上报）。
    // 曲率关着、图片没来时底照常留着当衬底，卡片不是空的。
    // 描边：跟着 buttonShape 走，默认形状（矩形）时是圆角矩形框，同样会
    // 框住椭圆海报，曲率开着时一起收掉；多边形 / 心形 / 圆形等形状框是
    // 用户 2.70 定过的“形状框 + 球”，描边留着。形状裁剪、选中缩放不动。
    // 3.11 水光用同一份解析值（组值），不另调一次。
    val elementFx = resolveElementEffects(elementId, kind)
    val hidePosterBox = kind == ControlElementKind.POSTER &&
        elementFx.warpDegree > 0f &&
        PosterLoadState.isLoaded(elementId)
    val hidePosterBorder = hidePosterBox && style.shape == EffectsState.CARD_SHAPE_RECT
    // 1.14：胶囊风格选项已删，水光 0-400 一根滑杆走四段（capsuleGlossByWater，自己画底）；
    // 卡片组/海报走原样平板底。
    val isLightKind = kind == ControlElementKind.EPISODE || kind == ControlElementKind.LINE ||
        kind == ControlElementKind.CHIP || kind == ControlElementKind.BUTTON
    val baseForState = if (selected) style.selectedBackgroundColor else style.backgroundColor
    var result: Modifier = Modifier
        .clip(shape)
        .then(
            when {
                hidePosterBox -> Modifier
                isLightKind -> Modifier.capsuleGlossByWater(shape, baseForState, elementFx.waterGloss, elementId.hashCode())
                else -> Modifier.background(
                    if (selected) style.selectedBackgroundColor else style.backgroundColor,
                    shape,
                )
            }
        )
        .then(
            if (hidePosterBorder) Modifier
            else Modifier.border(
                1.dp,
                if (selected) style.selectedBorderColor else style.borderColor,
                shape,
            )
        )
    if (kind == ControlElementKind.BUTTON) {
        result = result.heightIn(min = (40.dp * style.sizeScale * selScale))
    } else if (kind == ControlElementKind.EPISODE || kind == ControlElementKind.LINE || kind == ControlElementKind.CHIP) {
        result = result.heightIn(min = (32.dp * style.sizeScale * selScale))
    }
    // 1.14：胶囊类的亮面已在 morph 里；卡片组/海报仍走顶部一道亮面（组水光值）。曲率开且图片已到手时
    // 底已收掉，高光也一起收掉。
    if (!isLightKind && elementFx.waterGloss > 0 && !hidePosterBox) {
        result = result.then(Modifier.waterGlossSheen(shape, elementFx.waterGloss / 200f))
    }
    result = result.padding(surfacePadding(kind, style))
    if (selected) {
        result = result.graphicsLayer {
            scaleX = selScale
            scaleY = selScale
        }
    }
    return result
}

/**
 * 共享控件表面容器：[controlSurfaceModifier] + 按 fontScale 缩放的字号
 *（通过 LocalTextStyle 提供，容器内文字跟预设字号走）。
 * onClick 为 null 时不接点击（宿主自己处理点击，如海报卡、列表项）；
 * 非 null 时整面可点。下一阶段三页接线时用。
 */
@Composable
fun ControlSurface(
    elementId: String,
    kind: ControlElementKind = ControlElementKind.CARD,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val surfaceModifier = modifier.then(controlSurfaceModifier(elementId, kind, selected))
    val clickableModifier = if (onClick != null) {
        surfaceModifier.clickable { onClick() }
    } else {
        surfaceModifier
    }
    // 3.09 更正：总开关关时不改字号（controlSurfaceModifier 已是空操作，这里也不套 LocalTextStyle）。
    if (!ControlThemeStore.isControlThemeEnabled()) {
        Box(
            modifier = clickableModifier,
            content = content,
        )
        return
    }
    val style = resolveButtonStyle(elementId, kind)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = MaterialTheme.typography.bodyMedium.fontSize * style.fontScale,
    )
    CompositionLocalProvider(LocalTextStyle provides textStyle) {
        Box(
            modifier = clickableModifier,
            content = content,
        )
    }
}
