package com.github.tvbox.osc.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.wallpaperAwarePageBackground
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionMenuRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsSliderRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.AppLanguage
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryMerge
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.restartApp
import kotlin.math.roundToInt
import org.greenrobot.eventbus.EventBus

@Composable
fun PreferenceSettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    val context = LocalContext.current
    var sliderSpeed by remember(state.longPressSpeed) { mutableStateOf(state.longPressSpeed) }
    var sliderBuffer by remember(state.bufferTimes) { mutableStateOf(state.bufferTimes) }
    var sliderThreads by remember(state.searchThreads) { mutableStateOf(state.searchThreads) }
    var danmuApiDialog by remember { mutableStateOf(false) }
    var subtitleSourceDialog by remember { mutableStateOf(false) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        containerColor = wallpaperAwarePageBackground(MaterialTheme.colorScheme.surfaceContainer),
        titleContent = {
            Text(
                text = stringResource(R.string.settings_preference_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onNavigateBack)
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            Spacer(Modifier.height(topPad + 8.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_language_layout)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    LanguageRow()
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    ScreenOrientationRow(
                        mode = state.screenOrientation,
                        onSelect = { mode ->
                            vm.put(HawkConfig.SCREEN_ORIENTATION, mode)
                            // Applies to this screen at once; every
                            // other activity re-reads the policy on
                            // its next resume.
                            (context as? com.github.tvbox.osc.base.BaseActivity)
                                ?.applyOrientationPolicy()
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    CollectColumnsRow(
                        columns = state.collectColumns,
                        onSelect = { columns ->
                            vm.put(HawkConfig.COLLECT_COLUMNS, columns)
                            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_LAYOUT_CHANGE))
                        },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_privacy)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_history_merge),
                        leadingIconRes = R.drawable.ic_pref_history_merge,
                        checked = state.historyMerge,
                        onCheckedChange = {
                            HistoryMerge.setEnabled(it)
                            vm.refresh()
                            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_incognito),
                        leadingIconRes = R.drawable.ic_pref_incognito,
                        checked = state.incognito,
                        onCheckedChange = {
                            vm.put(HawkConfig.INCOGNITO, it)
                            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_hide_status_bar),
                        leadingIconRes = R.drawable.ic_pref_hide_status_bar,
                        subtitle = stringResource(R.string.settings_hide_status_bar_subtitle),
                        checked = state.hideStatusBar,
                        onCheckedChange = { vm.put(HawkConfig.HIDE_STATUS_BAR, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_gesture_disable),
                        leadingIconRes = R.drawable.ic_pref_gesture,
                        subtitle = stringResource(R.string.settings_gesture_disable_subtitle),
                        checked = state.gestureControlDisabled,
                        onCheckedChange = { vm.put(HawkConfig.GESTURE_CONTROL_DISABLED, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_nav_animation_disable),
                        leadingIconRes = R.drawable.ic_pref_nav_animation,
                        checked = state.navAnimationDisabled,
                        onCheckedChange = { vm.put(HawkConfig.NAV_ANIMATION_DISABLED, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_nav_live_hidden),
                        leadingIconRes = R.drawable.ic_pref_nav_live_hidden,
                        checked = state.navLiveHidden,
                        onCheckedChange = { vm.put(HawkConfig.NAV_LIVE_HIDDEN, it) },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_play_search)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_switch_line),
                        leadingIconRes = R.drawable.ic_pref_auto_switch_line,
                        checked = state.autoSwitchLine,
                        onCheckedChange = { vm.put(HawkConfig.AUTO_SWITCH_LINE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_m3u8_purify),
                        leadingIconRes = R.drawable.ic_pref_m3u8_purify,
                        checked = state.m3u8Purify,
                        onCheckedChange = { vm.put(HawkConfig.M3U8_PURIFY, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_danmu_switch),
                        leadingIconRes = R.drawable.ic_pref_danmu,
                        checked = state.danmuOpen,
                        onCheckedChange = { vm.put(HawkConfig.DANMU_OPEN, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_subtitle_switch),
                        leadingIconRes = R.drawable.ic_pref_danmu,
                        checked = state.subtitleEnabled,
                        onCheckedChange = { vm.put(HawkConfig.SUBTITLE_ENABLED, it) },
                    )
                }
                // 1.45 字幕源：播放与搜索一节新增行（弹幕开关与弹幕 API 行原样保留，
                // 字幕与弹幕并存、各自开关、各自渲染层）。
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "字幕源",
                        leadingIconRes = R.drawable.ic_pref_danmu_api,
                        valueText = when (state.subtitleSource) {
                            "assrt" -> "Assrt"
                            "shooter" -> "射手网"
                            "xunlei" -> "迅雷"
                            else -> "Assrt"
                        },
                        onClick = { subtitleSourceDialog = true },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_danmu_api),
                        leadingIconRes = R.drawable.ic_pref_danmu_api,
                        valueText = stringResource(if (state.danmuApi.isEmpty()) R.string.common_not_set else R.string.common_set),
                        onClick = { danmuApiDialog = true },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_long_press_speed),
                        leadingIconRes = R.drawable.ic_pref_long_press_speed,
                        value = sliderSpeed.toFloat(),
                        valueText = "${sliderSpeed}x",
                        valueRange = 2f..10f,
                        steps = 7,
                        onValueChange = { sliderSpeed = (it - 2).roundToInt() + 2 },
                        onValueChangeFinished = {
                            if (sliderSpeed != state.longPressSpeed) {
                                vm.put(HawkConfig.LONG_PRESS_SPEED, sliderSpeed)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_buffer_time),
                        leadingIconRes = R.drawable.ic_pref_buffer_time,
                        value = sliderBuffer.toFloat(),
                        valueText = "${sliderBuffer}x",
                        valueRange = 1f..10f,
                        steps = 8,
                        onValueChange = { sliderBuffer = (it - 1).roundToInt() + 1 },
                        onValueChangeFinished = {
                            if (sliderBuffer != state.bufferTimes) {
                                vm.put(HawkConfig.BUFFER_TIMES, sliderBuffer)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_search_threads),
                        leadingIconRes = R.drawable.ic_pref_search_threads,
                        value = sliderThreads.toFloat(),
                        valueText = "$sliderThreads",
                        valueRange = 16f..64f,
                        steps = 2,
                        onValueChange = { sliderThreads = ((it - 16) / 16).roundToInt() * 16 + 16 },
                        onValueChangeFinished = {
                            if (sliderThreads != state.searchThreads) {
                                vm.put(HawkConfig.SEARCH_THREADS, sliderThreads)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }

    if (danmuApiDialog) {
        TextEditDialog(
            title = stringResource(R.string.settings_danmu_api),
            initialText = state.danmuApi,
            onDismiss = { danmuApiDialog = false },
            onConfirm = { text ->
                vm.put(HawkConfig.DANMU_API, text)
                danmuApiDialog = false
            },
        )
    }

    if (subtitleSourceDialog) {
        SubtitleSourceDialog(
            initialSource = state.subtitleSource,
            initialToken = state.subtitleAssrtToken,
            initialAuto = state.subtitleAutoMatch,
            initialLang = state.subtitleAutoLang,
            onDismiss = { subtitleSourceDialog = false },
            onConfirm = { src, token, auto, lang ->
                vm.put(HawkConfig.SUBTITLE_SOURCE, src)
                vm.put(HawkConfig.SUBTITLE_ASSRT_TOKEN, token)
                vm.put(HawkConfig.SUBTITLE_AUTO_MATCH, auto)
                vm.put(HawkConfig.SUBTITLE_AUTO_LANG, lang)
                subtitleSourceDialog = false
            },
        )
    }

}

@Composable
private fun ScreenOrientationRow(mode: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        stringResource(R.string.settings_screen_orientation_system),
        stringResource(R.string.settings_screen_orientation_portrait),
        stringResource(R.string.settings_screen_orientation_landscape),
    )
    val selectedIndex = mode.coerceIn(0, 2)
    SettingsOptionMenuRow(
        title = stringResource(R.string.settings_screen_orientation),
        leadingIconRes = R.drawable.ic_pref_screen_orientation,
        valueText = options[selectedIndex],
        options = options,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
    )
}

@Composable
private fun CollectColumnsRow(columns: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        stringResource(R.string.settings_collect_columns_three),
        stringResource(R.string.settings_collect_columns_two),
    )
    val selectedIndex = if (columns == 3) 0 else 1
    SettingsOptionMenuRow(
        title = stringResource(R.string.settings_collect_columns),
        leadingIconRes = R.drawable.ic_pref_collect_columns,
        valueText = options[selectedIndex],
        options = options,
        selectedIndex = selectedIndex,
        onSelect = { idx -> onSelect(if (idx == 0) 3 else 2) },
    )
}

@Composable
private fun LanguageRow() {
    val available = LanguageManager.available()
    val current = LanguageManager.current()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<AppLanguage?>(null) }
    var rollback by remember { mutableStateOf(AppLanguage.System) }
    var restarting by remember { mutableStateOf(false) }
    SettingsOptionMenuRow(
        title = stringResource(R.string.settings_language),
        leadingIconRes = R.drawable.ic_pref_language,
        valueText = stringResource(languageLabelRes(current)),
        options = available.map { stringResource(languageLabelRes(it)) },
        selectedIndex = available.indexOf(current),
        onSelect = { idx ->
            val target = available.getOrNull(idx)
            if (target != null && target != current) {
                rollback = current
                LanguageManager.set(target)
                pending = target
            }
        },
    )
    val cancel = {
        LanguageManager.set(rollback)
        pending = null
    }
    pending?.let {
        AVBoxAlertDialog(
            onDismissRequest = cancel,
            text = { Text(stringResource(R.string.settings_language_restart_message)) },
            dismissButton = {
                val dismissAnimated = LocalSheetDismiss.current
                TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
            },
            confirmButton = {
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = {
                    dismissThen {
                        pending = null
                        restarting = true
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }
    if (restarting) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            restartApp(context.applicationContext)
        }
    }
}

private fun languageLabelRes(lang: AppLanguage): Int = when (lang) {
    AppLanguage.System -> R.string.settings_language_system
    AppLanguage.SimplifiedChinese -> R.string.settings_language_zh_hans
    AppLanguage.English -> R.string.settings_language_en
    AppLanguage.TraditionalTW -> R.string.settings_language_zh_hant_tw
    AppLanguage.TraditionalHK -> R.string.settings_language_zh_hant_hk
}

// 1.45 字幕源对话框：源切换（迅雷/Assrt/射手置灰）、Assrt Token 输入、
// 自动匹配开关（默认开）、优先语言（可空）。保存即写 KV，弹幕行不受影响。
// 1.44 适配：本页是全屏不是 BottomSheet，按钮直接走 onDismiss/onConfirm，
// 不依赖 LocalSheetDismiss/LocalSheetDismissThen；内容是普通 Column、无内层
// 滚动，不踩 AVBoxAlertDialog 外层滚动 + 内层滚动的嵌套闪退。
@Composable
fun SubtitleSourceDialog(
    initialSource: String,
    initialToken: String,
    initialAuto: Boolean,
    initialLang: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, String) -> Unit,
) {
    var source by remember { mutableStateOf(if (initialSource.isEmpty()) "assrt" else initialSource) }
    var token by remember { mutableStateOf(initialToken) }
    var auto by remember { mutableStateOf(initialAuto) }
    var lang by remember { mutableStateOf(initialLang) }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字幕源") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SubtitleSourceRadioRow(
                    label = "迅雷（免 Token）",
                    selected = source == "xunlei",
                    enabled = true,
                    onClick = { source = "xunlei" },
                )
                SubtitleSourceRadioRow(
                    label = "Assrt（免 Token，自带爬取）",
                    selected = source == "assrt",
                    enabled = true,
                    onClick = { source = "assrt" },
                )
                SubtitleSourceRadioRow(
                    label = "射手网（仅本地文件播放，在线不可用）",
                    selected = source == "shooter",
                    enabled = false,
                    onClick = { },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = source == "assrt",
                    label = { Text("Assrt Token（可选，不填也能搜）") },
                    placeholder = { Text("可空；搜索与下载不走 Token 接口") },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "开播自动匹配",
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
                Text(
                    text = "开播后按片名自动匹配一次，相关度≥2 才挂，同片 6 小时只试一次，挂不上不打断播放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = lang,
                    onValueChange = { lang = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("优先语言（可空）") },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(source, token.trim(), auto, lang.trim())
            }) { Text(stringResource(R.string.common_confirm)) }
        },
    )
}

@Composable
fun SubtitleSourceRadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
