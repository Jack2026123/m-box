package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.ControlEditState
import com.github.tvbox.osc.ui.components.ControlElementKind
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.components.appCardShape
import com.github.tvbox.osc.ui.components.controlContentColor
import com.github.tvbox.osc.ui.components.controlSelectedContentColor
import com.github.tvbox.osc.ui.components.controlSurfaceModifier
import com.github.tvbox.osc.ui.components.fxCardShadow
import com.github.tvbox.osc.ui.components.lineElementId
import com.github.tvbox.osc.ui.components.resolveElementEffects
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.ui.theme.ControlThemeStore

@Composable
internal fun SectionTitleIcon(painter: Painter) {
    Icon(
        painter = painter,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
internal fun SectionTitleIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

internal const val DetailCardAlpha = 0.5f

@Composable
internal fun detailCardColor(): Color =
    MaterialTheme.colorScheme.surfaceContainerLowest.copy(
        // 69: 高透明 fades detail cards too, fully transparent at 100.
        alpha = DetailCardAlpha * com.github.tvbox.osc.ui.theme.EffectsState.config.highTransparencyFactor,
    )

@Composable
internal fun detailChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = detailCardColor(),
    selectedContainerColor = detailCardColor(),
    labelColor = MaterialTheme.colorScheme.onSurface,
    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
internal fun detailChipBorder(selected: Boolean) = if (selected) {
    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
} else {
    BorderStroke(0.dp, Color.Transparent)
}

internal val DetailItemHeight = 70.dp
internal val DetailItemMinWidth = 128.dp
internal val DetailItemWidth = 128.dp

private const val DetailGlassGlossAlpha = 0.06f
private const val DetailGlassHighlightTopAlpha = 0.35f
private const val DetailGlassHighlightBottomAlpha = 0.06f

@Composable
internal fun Modifier.detailGlass(shape: Shape): Modifier = this
    .clip(shape)
    .background(detailCardColor())
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = DetailGlassGlossAlpha),
            0.45f to Color.Transparent,
        ),
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = DetailGlassHighlightTopAlpha),
                Color.White.copy(alpha = DetailGlassHighlightBottomAlpha),
            ),
        ),
        shape = shape,
    )

@Composable
internal fun DetailItemCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = DetailItemHeight,
    // 3.07 百变控件：稳定元素 id（调用处传，如 episodeElementId(n) / lineElementId(key)）；
    // 为 null 时不接线，原样。kind 决定形状收敛（EPISODE / LINE 只用胶囊或圆角矩形）。
    controlId: String? = null,
    controlKind: ControlElementKind = ControlElementKind.CARD,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）；线路卡与无缩略图
    // 选集卡都走这里，一处守卫全覆盖。
    if (controlId != null && ControlThemeStore.isElementHidden(controlId)) return
    // 3.10：单元素浮雕 / 光感覆盖：controlId 非空时按它解析，传给下面的
    // fxCardShadow；controlId 为 null（不接线）时传 null，走全局。曲面不画
    // 在这类卡上（无 warp），curvature 覆盖无可见效果。
    val elementEffects = if (controlId != null) resolveElementEffects(controlId, controlKind) else null
    val shape = appCardShape(16.dp)
    // 3.13：原层只在画原样式时留着——总开关开且已接线时只留控制层；
    // controlId 为 null（没接线）时没有控制层，原玻璃必须留着。
    val drawOriginal = controlId == null || ControlThemeStore.shouldDrawOriginalStyle()
    Column(
        modifier = modifier
            // 3.07：点按处理放最外层（点按区含表面内边距）；接线时 combinedClickable
            // 同时处理点按与长按（长按调编辑），不接线时沿用原 clickable，点按不变。
            .then(
                if (controlId != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = { ControlEditState.requestEdit(controlId) },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            // 3.07：表面外观放点按内侧（读控件主题：形状 / 描边 / 大小缩放），
            // 在玻璃底之前，玻璃层仍盖在上面、原有视觉保留。
            .then(if (controlId != null) controlSurfaceModifier(controlId, controlKind, selected) else Modifier)
            .height(height)
            .defaultMinSize(minWidth = DetailItemMinWidth)
            // 3.18：总开关开时浮雕亮边不画——控制层已画底 + 描边，fxCardShadow 的
            // 厚圆边是第二道边（PressableCard 同款做法：控件主题层在时跳过）；关时走原样。
            .then(if (drawOriginal) Modifier.fxCardShadow(shape, effects = elementEffects) else Modifier)
            // 3.13：总开关开时 detailGlass 不画，只留控制层。
            .then(if (drawOriginal) Modifier.detailGlass(shape) else Modifier)
            // 3.13：1.5dp primary 选中蓝边已删——选中态由控制层承担
            // （提亮底 + 浅色描边 + 1.05 倍）。
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun SourceCard(name: String, selected: Boolean, onClick: () -> Unit, controlId: String) {
    DetailItemCard(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.width(DetailItemWidth),
        height = 56.dp,
        // 3.07 百变控件：线路卡（换源）按源 key 编号。
        controlId = controlId,
        controlKind = ControlElementKind.LINE,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // 3.11 字体颜色：总开关开时字走胶囊组字体颜色（默认白字），关时走原字色。
            // 3.16：选中态字走参考图黄字（controlSelectedContentColor），未选中走组字体颜色。
            color = if (ControlThemeStore.isControlThemeEnabled()) {
                if (selected) controlSelectedContentColor(controlId, ControlElementKind.LINE) else controlContentColor(controlId, ControlElementKind.LINE)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SourceSection(vm: DetailViewModel, currentSourceName: String?, revision: Int) {
    @Suppress("UNUSED_EXPRESSION") revision
    val sourceChips by vm.sourceChips.collectAsState()
    val sourcesSearching by vm.sourcesSearching.collectAsState()
    if (!sourcesSearching && sourceChips.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(currentSourceName) {
        if (currentSourceName != null) listState.scrollToItem(0)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_switch_source))
            Text(
                text = stringResource(R.string.detail_switch_source),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            if (sourcesSearching) {
                Text(
                    text = stringResource(R.string.detail_finding_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (currentSourceName != null) {
                item(key = "current") {
                    SourceCard(
                        name = currentSourceName,
                        selected = true,
                        onClick = {},
                        // 3.07 百变控件：当前源卡按首选源 key 编号，无 key 时用 "current" 兜底。
                        controlId = lineElementId(vm.firstsourceKey.takeIf { it.isNotEmpty() } ?: "current"),
                    )
                }
            }
            itemsIndexed(sourceChips, key = { _, c -> c.key }) { _, chip ->
                SourceCard(
                    name = chip.name,
                    selected = false,
                    onClick = { vm.candidateForKey(chip.key)?.let { vm.switchSource(it) } },
                    // 3.07 百变控件：换源卡按源 key 编号。
                    controlId = lineElementId(chip.key),
                )
            }
        }
    }
}

@Composable
internal fun RelatedSection(
    activity: DetailActivity,
    vm: DetailViewModel,
    onCardLongClick: (Movie.Video) -> Unit = {},
) {
    val relatedVideos by vm.relatedVideos.collectAsState()
    if (relatedVideos.isEmpty()) return
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_recommend))
            Text(
                text = stringResource(R.string.detail_recommend),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                relatedVideos,
                key = { _, v -> (v.sourceKey ?: "") + "|" + (v.id ?: "") },
            ) { _, video ->
                VodCard(
                    video = video,
                    onClick = { activity.openVodCardOrDetail(video) },
                    onLongClick = { onCardLongClick(video) },
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}

private val CR_LINK_REGEX = Regex("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])/](.*?)\\[/a]")
private val WHITESPACE_REGEX = Regex("\\s")

internal fun removeHtmlTag(info: String?): String {
    if (info.isNullOrEmpty()) return ""
    var text = info.replace(CR_LINK_REGEX, "$1")
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace(WHITESPACE_REGEX, "")
}
