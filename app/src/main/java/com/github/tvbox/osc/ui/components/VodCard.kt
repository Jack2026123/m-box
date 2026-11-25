package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.EffectsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.bean.Movie

enum class VodCardStyle { Overlay, Stacked }

@Composable
fun VodCard(
    video: Movie.Video,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: VodCardStyle = VodCardStyle.Overlay,
    onPosterImage: ((coil3.Image) -> Unit)? = null,
    // 3.07 第二阶段 C-edit：海报层表面 modifier（只用于 Stacked 的
    // PressableCard 层）：多边形 / 心形只裁海报图，不裁下方标题字。
    // 默认空 = 不动；Overlay 仍走外层 modifier（标题在卡内，本就该一起裁）。
    posterSurfaceModifier: Modifier = Modifier,
    // 3.09 更正：true = 该海报由随心所欲接管（HomePage 海报行传）；总开关开时跳过
    // PressableCard 的原浮雕亮边（只留随心所欲那一层，避免双边）；总开关关时原浮雕照常画。
    controlThemeManaged: Boolean = false,
    // 海报卡标题长按出小编辑框：调用处传该海报的元素 id（posterElementId，
    // 与 posterSurfaceModifier 用同一个 id）；null = 不接长按（其他调用处）。
    // 只在 Stacked 样式里用——标题字在 PressableCard 外面，长按它不碰海报图。
    posterElementId: String? = null,
) {
    // 3.10：单元素浮雕 / 曲面 / 光感覆盖的唯一解析点（海报）：posterElementId
    // 非空时按它解析（与 posterSurfaceModifier 同一个 id）；Overlay 与无 id
    // 的调用处为 null，走全局。解析一次，同时传给 PressableCard（浮雕 +
    // 成球判定）与 VodPoster（曲面 warp），两边用同一份配置，不会各读各的。
    // 首页 Stacked 接管海报（controlThemeManaged）总开关开时 PressableCard
    // 仍跳过 fxCardShadow——浮雕 / 光感覆盖在那条路下没有画的地方，见回报；
    // 曲面覆盖照常走 VodPoster。
    val posterEffects = if (posterElementId != null) {
        resolveElementEffects(posterElementId, ControlElementKind.POSTER)
    } else {
        null
    }
    // 3.10 海报曲率大方框：图片到手时按同一个 elementId 上报给
    // PosterLoadState，controlSurfaceModifier 读到后在曲率开着时把 slate
    // 底 + 矩形描边收掉；没到手时底留着当衬底。只在 posterElementId 非空
    // 时上报（Stacked 首页/网格）；Overlay 与无 id 的调用处不报。
    // 调用方传的 onPosterImage 照常调（首页网格第 0 张的环境光种子）。
    val posterImageLoaded: (coil3.Image) -> Unit = { image ->
        if (posterElementId != null) PosterLoadState.markLoaded(posterElementId)
        onPosterImage?.invoke(image)
    }
    // 73 海报透明: only the poster image fades (graphicsLayer alpha on
    // the VodPoster itself); title/subtitle/badge siblings stay fully
    // opaque, and the relief face (drawn by PressableCard's outer
    // modifier) is untouched. The card has no opaque background of
    // its own, so a faded poster lets the wallpaper show through.
    val posterAlpha = EffectsState.config.posterTransparencyFactor
    // 标题长按的 interactionSource：放函数顶层无条件 remember，避免条件分支里
    // remember 顺序变（posterElementId 为 null 时 combinedClickable 不挂它）。
    val titleInteractionSource = remember { MutableInteractionSource() }
    if (style == VodCardStyle.Stacked) {
        Column(modifier = modifier) {
            PressableCard(
                onClick = onClick,
                onLongClick = onLongClick,
                // 3.07 第二阶段 C-edit：表面 modifier 套在海报卡这层（标题在外层 Column，
                // 不进裁剪）；外层 modifier 不再带表面 modifier。
                modifier = posterSurfaceModifier,
                shape = appCardShape(16.dp),
                warp = true,
                // 3.09 更正：接管的海报：总开关开 → 随心所欲层画、原浮雕不画；
                // 总开关关 → 原浮雕画（恢复最初的 AVBox）。没接管的海报一律不动。
                suppressRelief = controlThemeManaged && !ControlThemeStore.shouldDrawOriginalStyle(),
                effects = posterEffects,
            ) {
                Box(modifier = Modifier.aspectRatio(2f / 3f)) {
                    VodPoster(video.name, video.pic, year = video.year, sourceKey = video.sourceKey, tmdbCacheOnly = true, altName = video.en, onImage = posterImageLoaded, warp = true, effects = posterEffects, modifier = Modifier.graphicsLayer { alpha = posterAlpha })
                    RatingBadge(video, Modifier.align(Alignment.TopEnd))
                }
            }
            Text(
                text = video.name ?: "",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                // 海报卡标题长按出小编辑框（posterElementId 非空时才挂）：
                // 点标题字仍无反应（onClick 空实现、无涟漪），海报图点按播片 /
                // 长按出菜单一律不动（PressableCard 那边，没碰）。
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .then(
                        if (posterElementId != null) {
                            Modifier.combinedClickable(
                                interactionSource = titleInteractionSource,
                                indication = null,
                                onClick = {},
                                onLongClick = { ControlEditState.requestEdit(posterElementId) },
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        return
    }

    PressableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        // 3.11 水光：Overlay 路没走 posterSurfaceModifier（无 id / 不上报
        // PosterLoadState），高光直接挂这里——总开关开且有解析值且强度 > 0
        // 才画，形状与卡片同款 16dp 圆角；Stacked 路走 controlSurfaceModifier
        // 里的中心水光，不重复挂。
        modifier = modifier.then(
            if (posterEffects != null && ControlThemeStore.isControlThemeEnabled() && posterEffects.waterGloss > 0) {
                Modifier.waterGlossSheen(appCardShape(16.dp), posterEffects.waterGloss / 200f)
            } else {
                Modifier
            },
        ),
        shape = appCardShape(16.dp),
        warp = true,
        effects = posterEffects,
    ) {
        Box(modifier = Modifier.aspectRatio(2f / 3f)) {
            VodPoster(video.name, video.pic, year = video.year, sourceKey = video.sourceKey, tmdbCacheOnly = true, altName = video.en, onImage = posterImageLoaded, warp = true, effects = posterEffects, modifier = Modifier.graphicsLayer { alpha = posterAlpha })
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.8f),
                        )
                    ),
            )
            RatingBadge(video, Modifier.align(Alignment.TopEnd))
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(
                    text = video.name ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    if (video.year > 0) append(video.year)
                    if (!video.area.isNullOrBlank()) {
                        if (isNotEmpty()) append(" / ")
                        append(video.area)
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingBadge(video: Movie.Video, modifier: Modifier = Modifier) {
    val badge = ratingBadgeText(video.note) ?: return
    Text(
        text = badge,
        // 悬浮立体: the badge text is engraved like the other
        // subtitles (RowSubtitle / subscription-card URL rows):
        // plain at strength 0, carved at +/-50. The pill itself
        // keeps its position, padding and clipping untouched — no
        // container bevel or shadow is added here.
        style = fxEngravedTextStyle(MaterialTheme.typography.labelMedium),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier
            .padding(8.dp)
            .clip(appCardShape(12.dp))
            .background(wallpaperCardBackground(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), highTransparent = true))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private val RATING_SCORE_REGEX = Regex("评分[:：]?\\s*(\\d+(?:\\.\\d+)?)") // i18n: keep(源备注评分提取)
private val RATING_SCORE_SUFFIX_REGEX = Regex("^(\\d+(?:\\.\\d+)?)\\s*分$") // i18n: keep(源备注评分提取)

internal fun ratingBadgeText(note: String?): String? {
    val n = note?.trim().orEmpty()
    if (n.isEmpty()) return null
    val m = RATING_SCORE_REGEX.find(n)
        ?: RATING_SCORE_SUFFIX_REGEX.find(n)
    if (m != null) {
        val num = m.groupValues[1]
        return if (num.toFloatOrNull() == 0f) null else num
    }
    return n.takeIf { it.toFloatOrNull() != 0f }
}

internal fun isRatingNote(note: String?): Boolean {
    val n = note?.trim().orEmpty()
    if (n.isEmpty()) return false
    if (n.toFloatOrNull() != null) return true
    return ratingBadgeText(n) != n
}
