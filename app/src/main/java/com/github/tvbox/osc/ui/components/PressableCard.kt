package com.github.tvbox.osc.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.isLandscapeOrientation
import com.github.tvbox.osc.ui.theme.EffectsConfig
import com.github.tvbox.osc.ui.theme.EffectsState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // logtest40: null = follow the GLOBAL corner radius
    // (appCardShape), so the relief arc can never desync from the
    // card arc; an explicit shape still wins when a caller passes one.
    shape: Shape? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    // 2.13: true = 卡片里装的是会 warp 成球的海报（VodCard）。
    // 成球时卡片矩形上的边缘白洗（drawBulgeEdge）不再贴合，
    // 会在球周围留一圈白色框，所以成球时整层 fxCardShadow
    // 都跳过——球自带网格高光，立体感由球本身承担。
    warp: Boolean = false,
    // 3.08：true = 该卡由百变控件接管且原主题样式不该画（VodCard Stacked 海报层传）——
    // 跳过 fxCardShadow 浮雕亮边，只留控件主题那一层，避免双边。
    suppressRelief: Boolean = false,
    // 3.10：单元素浮雕 / 曲面 / 光感覆盖解析后的配置（VodCard 从 posterElementId
    // 解析后传进来）；null = 全局 EffectsState.config。只影响本卡的浮雕
    // （fxCardShadow）与成球判定（warpDegree），形状仍走全局 cardShape
    // （形状不在七个覆盖字段里）。
    effects: EffectsConfig? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // 2.70 卡片形状：只对海报卡生效（warp = true，即 VodCard），
    // 搜索结果那种横条行卡（warp = false）不动；形状 != 矩形时卡片轮廓
    // 改成对应形状（cardShape），替代传入的 shape；0 = 矩形，照旧。
    val cfg = effects ?: EffectsState.config
    val cardShapeValue = if (warp) cfg.cardShape else 0
    val resolvedShape = if (cardShapeValue != 0) cardShape(cardShapeValue) else (shape ?: appCardShape(16.dp))
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val landscape = isLandscapeOrientation()
    val remoteFocused = landscape && focused
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.97f
            remoteFocused -> 1.06f
            else -> 1f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    // 2.13: warp 成球时（warpDegree > 0）跳过边缘白洗，球周围
    // 不再留白色框；没成球（开关关 / 曲率为 0）照常走立体边。
    // 2.70：卡片形状时立体边沿形状走（用户定形状也要立体效果），
    // 成球也不跳过——形状框 + 球，立体由形状边承担。
    val warping = warp && cfg.warpDegree > 0f
    val skipRelief = suppressRelief || (warping && cardShapeValue == 0)
    Box(
        modifier = modifier
            // logtest42c: poster cards get the crisp perimeter rim
            // (plus the deeper concave inner shade) so -50 vs +50
            // reads on small busy poster art over any background.
            .then(
                if (skipRelief) Modifier
                else Modifier.fxCardShadow(resolvedShape, crispRim = true, effects = cfg)
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(resolvedShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) {
        content()
        if (remoteFocused) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(2.5.dp, MaterialTheme.colorScheme.primary, resolvedShape),
            )
        }
    }
}
