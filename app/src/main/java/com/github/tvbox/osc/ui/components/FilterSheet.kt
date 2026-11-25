package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.ui.theme.ControlThemeStore

/**
 * Filter sheet: tapping a chip applies that filter group immediately
 * (the list behind refreshes at once); tapping the selected chip again
 * clears the group. There is no 清除/确定 step — dismissing the sheet
 * (scrim, back, swipe) just leaves. The sheet opens at about half
 * height, its height is adjustable by long-press-dragging the handle.
 * Chip spacing is uniform 8dp on all sides (horizontal 8dp,
 * row gap 8dp, group title 8dp above and below) so the gaps
 * between chips read as one even rhythm instead of tight in
 * some places and loose in others; 8dp is the panel's grid
 * unit (it also matches the chip's own horizontal content
 * padding). The old height-follows-density curve is retired
 * for chip rows.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    sort: MovieSort.SortData,
    onDismiss: () -> Unit,
    onApply: (Map<String, String>) -> Unit,
) {
    var selection by remember(sort.id) { mutableStateOf(sort.filterSelect.toMap()) }
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.filter_title, sort.name ?: ""),
        resizable = true,
        // 3.24：筛选面板改实色（用户 2026-11-16 定「背景太透明，能看到下面的卡片」）——
        // 之前跟全局透明走（wallpaperAwareContainerColor），有壁纸时半透明，底下海报卡片透出来；
        // 现在直接用实色 surfaceContainer，不透，卡片看不见了。
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            sort.filters.forEach { filter ->
                Text(
                    text = filter.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    filter.values.orEmpty().forEach { (valueKey, valueName) ->
                        // 3.10 小编辑框：筛选面板 chip 接控件主题（kind=CHIP，
                        // id=组 key + 值 key）。FilterChip 自带 onClick、无
                        // onLongClick，外层 Box 用 combinedClickable 只加长按
                        // 调编辑；点按仍走 FilterChip 自身 onClick（内层先消
                        // 费点按，外层 onClick 空实现兜底，不会重复触发筛选）。
                        val chipId = filterSheetChipElementId(
                            filter.key?.takeIf { it.isNotBlank() } ?: filter.name.orEmpty(),
                            valueKey.ifBlank { valueName },
                        )
                        val isSelected = selection[filter.key.orEmpty()] == valueKey
                        // 3.10：单元素浮雕 / 光感覆盖：筛选面板 chip 的 fxGroupBevel
                        // 按该 chip 解析；曲面不画在 chip 上（无 warp），无可见效果。
                        // 3.18：总开关开时 bevel 不挂——控制层已画底 + 描边 + 亮面，
                        // bevel 的面 + 内嵌框是第二道边（HomeCapsule 同款门控）；关时走原样。
                        val chipFx = resolveElementEffects(chipId, ControlElementKind.CHIP)
                        // 3.11 字体颜色：总开关开时字走胶囊组字体颜色（默认白字），
                        // 关时走原字色（水胶囊字 / onSurface）。
                        val controlOn = ControlThemeStore.isControlThemeEnabled()
                        val chipLabelColor = if (controlOn) {
                            controlContentColor(chipId, ControlElementKind.CHIP)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        // 3.19：字跟框一起缩——胶囊大小档（sizeScale）同时缩字号与框高，
                        // 居中按缩后尺寸算；总开关关时回原样（14sp 字、32dp 框、8/2dp 内边距）。
                        val chipSize = resolveButtonStyle(chipId, ControlElementKind.CHIP).sizeScale
                        // 3.23：框跟字走——14sp 字行高 20sp（1.15 字体档 23sp），框不能小于 28dp 否则切字。
                        val chipHeight = if (controlOn) maxOf(40.dp * chipSize, 28.dp) else 32.dp
                        // 3.23：胶囊短一点（用户定）——横向内边距 8dp×档 → 4dp×档，字不变、框变窄；
                        // 总开关关时回原样（8/2dp）。
                        val chipContentPadding = if (controlOn) PaddingValues(horizontal = (2.dp * chipSize), vertical = (2.dp * chipSize)) else PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        val chipLabelStyle = if (controlOn) {
                            val baseLabel = MaterialTheme.typography.labelLarge
                            // 3.23：筛选字都改 14sp（用户定「都改成14sp吧」）——不再跟大小档走，固定 14sp；
                            // 行高 20sp（14×20/14），不切字；框高 max(40dp×档, 28dp)，1.15 字体档也不切。
                            // 字上下左右居中：CJK 字在默认 includeFontPadding 下字形偏下（上多下少），
                            // 关掉 fontPadding 让字形贴紧行高，FilterChip 的居中才准。
                            // TextUnit 不是 Comparable，coerceAtLeast 用不上——14sp 是定值，不用钳。
                            val fontSize = 14.sp
                            val lineHeight = fontSize * 20f / 14f
                            baseLabel.copy(
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                            )
                        } else {
                            MaterialTheme.typography.labelLarge
                        }
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { ControlEditState.requestEdit(chipId) },
                            ),
                        ) {
                            FilterChip(
                                // Small-tier relief: bevel + sheen only, no
                                // platform halo, sized to the chip. The
                                // bevel reads the 悬浮立体 slider value
                                // and profile like every content card
                                // (logtest43: it was near-invisible
                                // because the unselected chip container
                                // was transparent, leaving the bevel
                                // nothing to land on — now a translucent
                                // surfaceBright pill under it).
                                modifier = Modifier.then(if (controlOn) Modifier else Modifier.fxGroupBevel(RoundedCornerShape(18.dp), effects = chipFx)).height(chipHeight)
                                    .then(controlSurfaceModifier(chipId, ControlElementKind.CHIP, isSelected)),
                                selected = isSelected,
                                onClick = {
                                    val next = if (selection[filter.key.orEmpty()] == valueKey) {
                                        selection - filter.key.orEmpty()
                                    } else {
                                        selection + (filter.key.orEmpty() to valueKey)
                                    }
                                    selection = next
                                    onApply(next)
                                },
                                label = {
                                    // 3.29：筛选胶囊字居中——实测字偏下 7px（上空 41px、下空 27px，
                                    // 截图 945px 宽，约合 2dp），数字字形的上沿度量大于下沿所致；
                                    // 只把 label 字上移 2dp（上 41-7=34px、下 27+7=34px，上下相等），
                                    // 不动字号/框高/横向内边距；只动筛选 chip，首页分类 chip 与源胶囊不受影响。
                                    Text(valueName, style = chipLabelStyle, modifier = Modifier.offset(y = -2.dp))
                                },
                                shape = RoundedCornerShape(18.dp),
                                // 3.13：总开关开时 FilterChip 自身容器色透明，只留控制层
                                // （slate 底 + 水光）；选中态由 controlSurfaceModifier 已传的
                                // isSelected 承担（提亮底 + 浅色描边 + 1.05 倍）。关时走原样。
                                // 3.16：选中字走参考图黄字（controlSelectedContentColor），
                                // 未选中走 chipLabelColor（组字体颜色 / 白字）。
                                colors = if (controlOn) {
                                    FilterChipDefaults.filterChipColors(
                                        containerColor = Color.Transparent,
                                        labelColor = chipLabelColor,
                                        selectedContainerColor = Color.Transparent,
                                        selectedLabelColor = controlSelectedContentColor(chipId, ControlElementKind.CHIP),
                                    )
                                } else {
                                    // 3.11：filterChipColors 扩展只认 containerColor，字色要单列
                                    // 参数时改走 FilterChipDefaults（值与扩展的默认一致：选中容器
                                    // primaryContainer、选中字 onPrimaryContainer、平时字 onSurface）。
                                    FilterChipDefaults.filterChipColors(
                                        containerColor = wallpaperCardBackground(MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.7f), highTransparent = true),
                                        labelColor = if (controlOn) chipLabelColor else MaterialTheme.colorScheme.onSurface,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = if (controlOn) chipLabelColor else MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                },
                                contentPadding = chipContentPadding,
                                // 3.16：总开关开时 FilterChip 自身默认 1dp 描边去掉，只留控制层那圈描边
                                // （3.13 只改了容器色、没动描边，所以筛选胶囊一直两层）；关时走默认描边，原样不动。
                                border = if (controlOn) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected),
                            )
                        }
                    }
                }
            }
        }
    }
}
