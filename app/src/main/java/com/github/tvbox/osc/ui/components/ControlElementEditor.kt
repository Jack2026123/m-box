package com.github.tvbox.osc.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.theme.ControlGroup
import com.github.tvbox.osc.ui.theme.ControlTheme
import com.github.tvbox.osc.ui.theme.ControlThemePresets
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.displayName
import com.github.tvbox.osc.ui.theme.resolveCodeKind
import com.github.tvbox.osc.ui.theme.resolveUserKind
import com.github.tvbox.osc.ui.theme.EffectsState
import kotlin.math.roundToInt

/**
 * 3.07 百变控件 · 第二阶段 C-edit：长按编辑弹窗 + 宿主。
 *
 * 接线方长按调 [ControlEditState.requestEdit] 后，[ControlEditHost]
 * 弹出对应元素的迷你编辑器：改大小（滑杆 0.8–1.5）、形状（5 选）、
 * 强调色（主题协调色 + 自定义入口）、浮雕（开关 + 档位 + 强度滑杆
 * 0..200）、曲面（开关 + 曲率滑杆 0..200 + 向外 / 向内）、光感
 * （亮度滑杆 0..200），另有“隐藏这个元素”与“关闭”。
 * 点空白处退出（Dialog onDismissRequest）。
 *
 * 3.14 改组值：编辑器改的是整组，不再写单元素覆盖——从任意胶囊打开
 * 改整个胶囊组，从任意海报打开改整个卡片组；单元素覆盖机制已删，
 * 已存覆盖由 ControlThemeStore.load() 的一次性迁移清掉。
 * 自定义色用 [ThemeColorPickerSheet]，它是底部表（与 Dialog 不在
 * 同一窗口），所以放宿主层：点“自定义…”先关编辑器再出选色器，
 * 确认后把颜色写回同一组（setGroupCustomColorArgb / setGroupFontColorArgb /
 * setGroupBaseColorArgb / setGroupSelectedContentColorArgb）。
 * 编辑时不触发元素的 onClick（弹窗是独立窗口，盖在元素上面）。
 *
 * 3.11 按组只显管用的项：入口只拿 elementId，先 [resolveCodeKind] 再
 * [resolveUserKind]；值读 ControlThemeStore.groupConfig(userKind)（本组
 * 主题），不再读全局 config。
 * 卡片组：大小 / 形状 / 浮雕 / 曲面 / 光感 / 水光；胶囊组：大小 /
 * 字体颜色 / 强调色 / 底色 / 选中颜色 / 水光（3.18：边框形状已删，恒为跟主题）。
 */

/** 长按编辑弹窗宿主：放三页根部（MainScreen / DetailActivity / MusicPlayerActivity 各一个）。 */
@Composable
fun ControlEditHost() {
    val editId by ControlEditState.editRequest.collectAsState()
    // 自定义色选色器挂在宿主层（见文件头注释）；3.14 改组值：存正在改色的组，
    // 不再存元素 id。强调色、字体颜色、底色与选中颜色各一个（3.11 胶囊组新增字体颜色，
    // 3.16 新增底色，3.17 新增选中颜色）：点“自定义…”先关编辑器再出选色器，确认后把颜色写回同一组。
    var customColorGroup by remember { mutableStateOf<ControlGroup?>(null) }
    var customFontColorGroup by remember { mutableStateOf<ControlGroup?>(null) }
    var customBaseColorGroup by remember { mutableStateOf<ControlGroup?>(null) }
    var customSelectedColorGroup by remember { mutableStateOf<ControlGroup?>(null) }
    val customGroup = customColorGroup
    val customFontGroup = customFontColorGroup
    val customBaseGroup = customBaseColorGroup
    val customSelectedGroup = customSelectedColorGroup
    if (editId != null) {
        ControlElementEditor(
            elementId = editId!!,
            onPickCustomColor = { group ->
                customColorGroup = group
                ControlEditState.dismiss()
            },
            onPickCustomFontColor = { group ->
                customFontColorGroup = group
                ControlEditState.dismiss()
            },
            onPickCustomBaseColor = { group ->
                customBaseColorGroup = group
                ControlEditState.dismiss()
            },
            onPickCustomSelectedColor = { group ->
                customSelectedColorGroup = group
                ControlEditState.dismiss()
            },
        )
    }
    if (customGroup != null) {
        val groupTheme = ControlThemeStore.groupConfig(customGroup)
        ThemeColorPickerSheet(
            title = "自定义强调色",
            initialColor = if (groupTheme.customColorArgb != 0) groupTheme.customColorArgb else MaterialTheme.colorScheme.primary.toArgb(),
            onConfirm = { argb ->
                ControlThemeStore.setGroupCustomColorArgb(customGroup, argb)
                customColorGroup = null
            },
            onDismiss = { customColorGroup = null },
        )
    }
    if (customFontGroup != null) {
        val groupTheme = ControlThemeStore.groupConfig(customFontGroup)
        ThemeColorPickerSheet(
            title = "自定义字体颜色",
            initialColor = if (groupTheme.fontColorArgb != 0) groupTheme.fontColorArgb else Color.White.toArgb(),
            onConfirm = { argb ->
                ControlThemeStore.setGroupFontColorArgb(customFontGroup, argb)
                customFontColorGroup = null
            },
            onDismiss = { customFontColorGroup = null },
        )
    }
    if (customBaseGroup != null) {
        val groupTheme = ControlThemeStore.groupConfig(customBaseGroup)
        ThemeColorPickerSheet(
            title = "自定义底色",
            initialColor = if (groupTheme.baseColorArgb != 0) groupTheme.baseColorArgb else ControlThemePresets.DEFAULT.baseColorArgb,
            onConfirm = { argb ->
                ControlThemeStore.setGroupBaseColorArgb(customBaseGroup, argb)
                customBaseColorGroup = null
            },
            onDismiss = { customBaseColorGroup = null },
        )
    }
    if (customSelectedGroup != null) {
        val groupTheme = ControlThemeStore.groupConfig(customSelectedGroup)
        ThemeColorPickerSheet(
            title = "自定义选中颜色",
            initialColor = if (groupTheme.selectedContentColorArgb != 0) groupTheme.selectedContentColorArgb else ControlThemePresets.DEFAULT.selectedContentColorArgb,
            onConfirm = { argb ->
                ControlThemeStore.setGroupSelectedContentColorArgb(customSelectedGroup, argb)
                customSelectedColorGroup = null
            },
            onDismiss = { customSelectedColorGroup = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlElementEditor(
    elementId: String,
    onPickCustomColor: (ControlGroup) -> Unit,
    onPickCustomFontColor: (ControlGroup) -> Unit,
    onPickCustomBaseColor: (ControlGroup) -> Unit,
    onPickCustomSelectedColor: (ControlGroup) -> Unit,
) {
    val context = LocalContext.current
    // 3.11：组判定——userKind 决定显示哪几组与值读哪一组。
    // 卡片组（POSTER / CARD）：大小 / 形状 / 浮雕 / 曲面 / 光感 / 水光；
    // 胶囊组（其余）：大小 / 字体颜色 / 强调色 / 底色 / 水光。
    // 3.14：elementId 只用来判定组与隐藏单个元素，值一律读写本组主题。
    val codeKind = resolveCodeKind(elementId)
    val userKind: ControlGroup = resolveUserKind(codeKind, elementId)
    val isCardGroup = userKind == ControlGroup.CARD
    val groupTheme = ControlThemeStore.groupConfig(userKind)
    val showRelief = isCardGroup
    val showCurvature = isCardGroup
    val showLight = isCardGroup
    val showAccent = !isCardGroup
    val showFontColor = !isCardGroup
    val showBaseColor = !isCardGroup
    val showSelectedColor = !isCardGroup
    // 3.14：本地值就是本组值，改动直接整组回写（setGroupTheme），
    // 不再有单元素覆盖与“跟随本组”——这里改的就是本组。
    var shapeValue by remember(elementId) { mutableStateOf(groupTheme.shape) }
    // 强调色：0 = 跟随主题（主色）；点色点即写本组自定义色。
    var colorValue by remember(elementId) { mutableStateOf(groupTheme.customColorArgb) }
    // 边框形状：卡片组不用它（卡片形状走 shape 字段）；胶囊组恒为跟主题（3.18），
    // 这里只原样带回卡片组的值，胶囊组写回时强制跟主题（见 writeCurrent）。
    var cornerModeValue by remember(elementId) { mutableStateOf(groupTheme.cornerMode) }
    // 字体颜色（胶囊组）：0 = 跟主题白字；点“自定义…”出选色盘。
    var fontColorValue by remember(elementId) { mutableStateOf(groupTheme.fontColorArgb) }
    // 底色（胶囊组）：0 = 跟随主题 slate；非 0 = 自定义底色 ARGB；点“自定义…”出选色盘。
    var baseColorValue by remember(elementId) { mutableStateOf(groupTheme.baseColorArgb) }
    // 选中颜色（胶囊组）：0 = 跟随主题主色；非 0 = 自定义选中字色 ARGB；点“自定义…”出选色盘。
    var selectedContentColorValue by remember(elementId) { mutableStateOf(groupTheme.selectedContentColorArgb) }
    var reliefEnabledValue by remember(elementId) { mutableStateOf(groupTheme.reliefEnabled) }
    var reliefProfileValue by remember(elementId) { mutableStateOf(groupTheme.reliefProfile) }
    var reliefMagnitudeSlider by remember(elementId) { mutableStateOf(groupTheme.reliefMagnitude) }
    var curvatureSlider by remember(elementId) { mutableStateOf(groupTheme.curvature.toFloat()) }
    var curvatureEnabledValue by remember(elementId) { mutableStateOf(groupTheme.curvatureEnabled) }
    var curvatureOutwardValue by remember(elementId) { mutableStateOf(groupTheme.curvatureOutward) }
    var lightSlider by remember(elementId) { mutableStateOf(groupTheme.lightBrightness.toFloat()) }
    var waterGlossSlider by remember(elementId) { mutableStateOf(groupTheme.waterGloss.toFloat()) }
    var cornerScaleSlider by remember(elementId) { mutableStateOf(groupTheme.cornerScale) }

    fun writeCurrent() {
        // 3.14：整组回写——本地十四项全写进本组主题；没在编辑器里露出的
        // 字段（sizeScale / fontScale / cornerScale 等）照 groupTheme 原样带回。
        ControlThemeStore.setGroupTheme(
            userKind,
            groupTheme.copy(
                shape = shapeValue,
                customColorArgb = colorValue,
                // 3.18：胶囊组边框形状恒为跟主题——卡片组原样带回，胶囊组强制写 0，
                // 长按编辑器里改了别的项也不会把旧 pill 值带回来。
                cornerMode = if (isCardGroup) cornerModeValue else ControlTheme.CORNER_FOLLOW_THEME,
                cornerScale = cornerScaleSlider,
                fontColorArgb = fontColorValue,
                baseColorArgb = baseColorValue,
                selectedContentColorArgb = selectedContentColorValue,
                reliefEnabled = reliefEnabledValue,
                reliefProfile = reliefProfileValue,
                reliefMagnitude = reliefMagnitudeSlider,
                curvature = curvatureSlider.toInt(),
                curvatureEnabled = curvatureEnabledValue,
                curvatureOutward = curvatureOutwardValue,
                lightBrightness = lightSlider.toInt(),
                waterGloss = waterGlossSlider.toInt(),
            ),
        )
    }

    Dialog(
        onDismissRequest = { ControlEditState.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            // 3.09 修：默认平台宽会让弹窗右缘出屏被裁；照 OpenSourceFileActivity 的
            // 做法（usePlatformDefaultWidth = false + fillMaxWidth(0.92f)），
            // 宽屏再用 560.dp 上限兜住（Material 对话框规范上限）。
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 560.dp),
        ) {
            Column(
                // 3.10：六组变高了，矮屏纵向可滚，不裁底部按钮；弹窗宽度修复不动。
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            ) {
                Text(
                    text = if (isCardGroup) "编辑卡片组" else "编辑胶囊组",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 3.14：标题即组名，副标题说清改的是整组；元素 id 不再显示。
                Text(
                    text = "改的是整组，所有" + userKind.displayName() + "一起变",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // 1.14：胶囊样式整块不显示（选项 + 实时预览已删）；水光滑杆走四段。
                // 1.15：大小滑杆不显示（胶囊/卡片统一 0.5 默认，见 ControlTheme 读入）。
                // 1.14：形状行不显示（五边形 / 六边形 / 八边形 / 圆形已删，只剩默认矩形）。
                if (isCardGroup) {
                    Spacer(Modifier.height(12.dp))
                    Text("设计感预设", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EditorOptionChip("悬浮海报", false) {
                            reliefEnabledValue = true
                            reliefProfileValue = EffectsState.RELIEF_PROFILE_DOME
                            reliefMagnitudeSlider = 65f
                            curvatureEnabledValue = false
                            writeCurrent()
                        }
                        EditorOptionChip("柔和曲面", false) {
                            reliefEnabledValue = true
                            reliefProfileValue = EffectsState.RELIEF_PROFILE_DOME
                            reliefMagnitudeSlider = 45f
                            curvatureEnabledValue = true
                            curvatureOutwardValue = true
                            curvatureSlider = 55f
                            writeCurrent()
                        }
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("圆角", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("×" + String.format("%.2f", cornerScaleSlider), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = cornerScaleSlider,
                        onValueChange = { cornerScaleSlider = it },
                        onValueChangeFinished = { writeCurrent() },
                        valueRange = EffectsState.CORNER_RANGE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("胶囊装饰（水滴显示）", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "无" to ControlTheme.DECORATION_NONE,
                            "雪花" to ControlTheme.DECORATION_SNOW,
                        ).forEach { (label, value) ->
                            EditorOptionChip(
                                label = label,
                                selected = ControlThemeStore.capsuleDecoration() == value,
                                onClick = { ControlThemeStore.setCapsuleDecoration(value) },
                            )
                        }
                    }
                }
                // 3.11：强调色组只在胶囊组显（卡片组没有强调色）。
                if (showAccent) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "强调色",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                val scheme = MaterialTheme.colorScheme
                val colorOptions: List<Pair<String, Color>> = listOf(
                    "主色" to scheme.primary,
                    "次色" to scheme.secondary,
                    "第三色" to scheme.tertiary,
                    "警示" to scheme.error,
                    "反色" to scheme.inversePrimary,
                )
                // 3.10：色点排改走 FlowRow，行不够折两行，不裁。
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for ((_, color) in colorOptions) {
                        val selected = colorValue == color.toArgb()
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable {
                                    colorValue = color.toArgb()
                                    writeCurrent()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Text(
                                    text = "✓",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 3.10：「跟随主题 / 自定义…」改走 FlowRow，行不够折两行，不裁。
                // 3.17：跟随主题 = 自定义色清回 0，强调色回落跟主题主色（胶囊配色已删）。
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            colorValue = 0
                            writeCurrent()
                        },
                    ) {
                        Text(text = "跟随主题")
                    }
                    TextButton(onClick = { onPickCustomColor(userKind) }) {
                        Text(text = "自定义…")
                    }
                }
                }
                // 3.11：字体颜色组只在胶囊组显（跟主题白字 / 自定义…）。
                if (showFontColor) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "字体颜色",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (fontColorValue != 0) Color(fontColorValue) else Color.White,
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                fontColorValue = 0
                                writeCurrent()
                            },
                        ) {
                            Text(text = "跟主题")
                        }
                        TextButton(onClick = { onPickCustomFontColor(userKind) }) {
                            Text(text = "自定义…")
                        }
                    }
                }
                // 3.16：底色组只在胶囊组显（跟随主题 slate / 自定义…）。
                // 0 = 跟随主题（slate 底）；非 0 = 自定义底色，水光从这个底色往透亮走。
                if (showBaseColor) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "底色",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (baseColorValue != 0) Color(baseColorValue) else resolveButtonStyle(elementId, codeKind).backgroundColor,
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                baseColorValue = 0
                                writeCurrent()
                            },
                        ) {
                            Text(text = "跟随主题")
                        }
                        TextButton(onClick = { onPickCustomBaseColor(userKind) }) {
                            Text(text = "自定义…")
                        }
                    }
                }
                // 3.17：选中颜色组只在胶囊组显（跟随主题主色 / 自定义…）。
                // 0 = 跟随主题主色（选中态优先于字体颜色）；非 0 = 自定义选中字色。
                if (showSelectedColor) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "选中颜色",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedContentColorValue != 0) Color(selectedContentColorValue) else controlSelectedContentColor(elementId, codeKind),
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                selectedContentColorValue = 0
                                writeCurrent()
                            },
                        ) {
                            Text(text = "跟随主题")
                        }
                        TextButton(onClick = { onPickCustomSelectedColor(userKind) }) {
                            Text(text = "自定义…")
                        }
                    }
                }
                // 3.11：浮雕组只在卡片组显。
                if (showRelief) {
                    Spacer(Modifier.height(16.dp))
                    // —— 3.10：浮雕组（开关 + 档位 + 强度滑杆；强度 0..200，跟本组一致） ——
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "浮雕",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = (if (reliefEnabledValue) reliefMagnitudeSlider else 0f).roundToInt().toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    EditorSwitchRow(
                        title = "启用浮雕",
                        checked = reliefEnabledValue,
                        onCheckedChange = {
                            reliefEnabledValue = it
                            writeCurrent()
                        },
                    )
                    Slider(
                        value = if (reliefEnabledValue) reliefMagnitudeSlider else 0f,
                        onValueChange = { reliefMagnitudeSlider = it },
                        onValueChangeFinished = { writeCurrent() },
                        valueRange = EffectsState.ELEVATION_RANGE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for ((label, value) in listOf(
                            stringResource(R.string.fx_relief_flat) to EffectsState.RELIEF_PROFILE_FLAT,
                            stringResource(R.string.fx_relief_dome) to EffectsState.RELIEF_PROFILE_DOME,
                            stringResource(R.string.fx_relief_facet) to EffectsState.RELIEF_PROFILE_FACETED,
                        )) {
                            EditorOptionChip(
                                label = label,
                                selected = reliefProfileValue == value,
                                onClick = {
                                    reliefProfileValue = value
                                    writeCurrent()
                                },
                            )
                        }
                    }
                }
                // 3.11：曲面组只在卡片组显。
                if (showCurvature) {
                    Spacer(Modifier.height(16.dp))
                    // —— 3.10：曲面组（开关 + 曲率滑杆 0..200 + 向外 / 向内） ——
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "曲面",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = (if (curvatureEnabledValue) curvatureSlider else 0f).roundToInt().toString() + "%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    EditorSwitchRow(
                        title = "启用曲面",
                        checked = curvatureEnabledValue,
                        onCheckedChange = {
                            curvatureEnabledValue = it
                            writeCurrent()
                        },
                    )
                    Slider(
                        value = if (curvatureEnabledValue) curvatureSlider else 0f,
                        onValueChange = { curvatureSlider = it },
                        onValueChangeFinished = { writeCurrent() },
                        valueRange = EffectsState.CURVATURE_RANGE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for ((label, value) in listOf("向外" to true, "向内" to false)) {
                            EditorOptionChip(
                                label = label,
                                selected = curvatureOutwardValue == value,
                                onClick = {
                                    curvatureOutwardValue = value
                                    writeCurrent()
                                },
                            )
                        }
                    }
                }
                // 3.11：光感组只在卡片组显（显隐同浮雕组）。
                if (showLight) {
                    Spacer(Modifier.height(16.dp))
                    // —— 3.10：光感组（亮度滑杆；范围见下方注释） ——
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "光感",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = lightSlider.roundToInt().toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = lightSlider,
                        onValueChange = { lightSlider = it },
                        onValueChangeFinished = { writeCurrent() },
                        // 注意：本组光感就是 0..200（EffectsState.LIGHT_BRIGHTNESS_RANGE，
                        // setLightBrightness / setGroupTheme 全钳
                        // 0..200），不用 0..300——300 的滑杆写进去也会被钳回 200，滑杆值
                        // 与存值对不上。默认 140。
                        valueRange = EffectsState.LIGHT_BRIGHTNESS_RANGE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 3.11：水光组两组都显（强度滑杆 0..200；0 = 无水光）。
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "水光",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = waterGlossSlider.roundToInt().toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = waterGlossSlider,
                    onValueChange = { waterGlossSlider = it },
                    onValueChangeFinished = { writeCurrent() },
                    valueRange = EffectsState.WATER_GLOSS_RANGE,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // 3.10：底部操作行改走 FlowRow——窄屏下「关闭」折到第二行，不裁；
                // FlowRow 不支持 weight Spacer，关闭不再右对齐，依次排在后面。
                // 3.14：“清除覆盖”已随单元素覆盖机制一起删掉，只剩隐藏与关闭。
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            ControlThemeStore.setElementHidden(elementId, true)
                            Toast.makeText(context, "已隐藏，可在设置页恢复显示", Toast.LENGTH_LONG).show()
                            ControlEditState.dismiss()
                        },
                    ) {
                        Text(text = "隐藏这个元素")
                    }
                    TextButton(
                        onClick = {
                            ControlThemeStore.setGroupTheme(
                                userKind,
                                ControlThemePresets.DEFAULT.copy(
                                    waterGloss = if (isCardGroup) ControlThemeStore.DEFAULT_CARD_WATER_GLOSS else ControlThemeStore.DEFAULT_CAPSULE_WATER_GLOSS,
                                ),
                            )
                            if (!isCardGroup) ControlThemeStore.setCapsuleDecoration(ControlTheme.DECORATION_NONE)
                            ControlEditState.dismiss()
                        },
                    ) { Text("恢复本组默认") }
                    TextButton(onClick = { ControlEditState.dismiss() }) {
                        Text(text = "关闭")
                    }
                }
            }
        }
    }
}

/**
 * 小编辑框里的紧凑开关行：标题（可折行）+ Switch。
 * 不用 SettingsSwitchRow——它带 16.dp 横边距与 64.dp 最小高，弹窗里太占地方。
 */
@Composable
private fun EditorSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 可折行选项 chip（形状 / 浮雕档位 / 曲面方向共用）：选中态主色容器 +
 * 主色描边，未选中表面色；放 FlowRow 里，行不够就折下一行，不裁字。
 * 样式与 ThemeSettingsPage 的 ReliefProfileChip 同款。
 */
@Composable
fun EditorOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
        ),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
