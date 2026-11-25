@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.github.tvbox.osc.ui.page

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.AVBoxDialog
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.wallpaperAwarePageBackground
import com.github.tvbox.osc.ui.components.CapsuleSegmentedButton
import com.github.tvbox.osc.ui.components.capsuleGroupBaseColor
import com.github.tvbox.osc.ui.components.capsuleGroupBorderColor
import com.github.tvbox.osc.ui.components.capsuleGroupSelectedContentColor
import com.github.tvbox.osc.ui.components.EditorOptionChip
import com.github.tvbox.osc.ui.components.waterDropGloss
import com.github.tvbox.osc.ui.components.lightCapsuleSkin
import com.github.tvbox.osc.ui.components.raisedCapsuleGloss
import com.github.tvbox.osc.ui.components.ControlElementKind
import com.github.tvbox.osc.ui.components.PhoneMockupPreview
import com.github.tvbox.osc.ui.components.RowLeadingIcon
import com.github.tvbox.osc.ui.components.SegmentOption
import com.github.tvbox.osc.ui.components.SegmentStyle
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsSwitch
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.ThemeColorPickerSheet
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.ui.theme.ControlGroup
import com.github.tvbox.osc.ui.theme.displayName
import com.github.tvbox.osc.ui.theme.ControlTheme
import com.github.tvbox.osc.ui.theme.ControlThemeIo
import com.github.tvbox.osc.ui.theme.ControlThemePresets
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.EffectsState
import com.github.tvbox.osc.ui.theme.LiquidGlassState
import com.github.tvbox.osc.ui.theme.PaletteStyles
import com.github.tvbox.osc.ui.theme.PresetSeeds
import com.github.tvbox.osc.ui.theme.ThemePresets
import com.github.tvbox.osc.ui.theme.ThemePreset
import com.github.tvbox.osc.ui.theme.ThemeMode
import com.github.tvbox.osc.ui.theme.ThemePack
import com.github.tvbox.osc.ui.theme.ThemePackStore
import com.github.tvbox.osc.ui.theme.ThemeSource
import com.github.tvbox.osc.ui.theme.filterChipColors
import com.github.tvbox.osc.util.FloatControl
import com.github.tvbox.osc.util.HomeSettings
import java.io.File
import com.materialkolor.PaletteStyle
import kotlin.math.roundToInt

private const val DisabledAlpha = 0.45f

private val PresetSeedCardSpacing = 12.dp

private val PresetSeedMinCardWidth = 80.dp

private const val PresetSeedNarrowColumns = 4

private const val PresetSeedWideColumns = 8

@Composable
fun ThemeSettingsScreen(onNavigateBack: () -> Unit) {
    val config = AppThemeState.config
    val isCustom = config.source == ThemeSource.CUSTOM
    var seedPickerOpen by remember { mutableStateOf(false) }
    var textColorPickerOpen by remember { mutableStateOf(false) }
    val glassConfig = LiquidGlassState.config
    var blurValue by remember(glassConfig.blurDp) { mutableStateOf(glassConfig.blurDp) }
    var distortionValue by remember(glassConfig.distortionDp) { mutableStateOf(glassConfig.distortionDp) }
    var translucencyValue by remember(glassConfig.translucency) { mutableStateOf(glassConfig.translucency) }
    val effects = EffectsState.config
    var ambientValue by remember(effects.ambientLevel) { mutableStateOf(effects.ambientLevel.toFloat()) }
    var frostedValue by remember(effects.frostedLevel) { mutableStateOf(effects.frostedLevel.toFloat()) }
    var wallpaperValue by remember(effects.wallpaperLevel) { mutableStateOf(effects.wallpaperLevel.toFloat()) }
    var highValue by remember(effects.highTransparencyLevel) { mutableStateOf(effects.highTransparencyLevel.toFloat()) }
    var posterValue by remember(effects.posterTransparencyLevel) { mutableStateOf(effects.posterTransparencyLevel.toFloat()) }
    var veilValue by remember(effects.detailVeilLevel) { mutableStateOf(effects.detailVeilLevel.toFloat()) }
    var wallpaperDialogOpen by remember { mutableStateOf(false) }
    var wallpaperBusy by remember { mutableStateOf(false) }
    var wallpaperFailed by remember { mutableStateOf(false) }
    var videoWallpaperBusy by remember { mutableStateOf(false) }
    var videoWallpaperFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val localContext = LocalContext.current
    val wallpaperLocalPick = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        wallpaperBusy = true
        wallpaperFailed = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val stream = localContext.contentResolver.openInputStream(uri)
                        ?: return@withContext false
                    com.github.tvbox.osc.ui.theme.WallpaperStore.applyLocal(stream)
                } catch (e: Exception) {
                    false
                }
            }
            wallpaperBusy = false
            wallpaperFailed = !ok
        }
    }
    // r482：视频壁纸选本地视频。OpenDocument（不是 GetContent）：能进文件管理器
    // 全盘选（没被媒体库收录的文件也能选），且可同时接受 video/* 与 image/gif。
    // 流程：GIF → 直接当图片壁纸（Coil 会动）；H.264 mp4 → 直接拷；其他编码
    // （H.265/AV1/VP9/10bit 等本机播不了的）→ VideoTranscoder 转成 H.264 MP4
    // 再设为壁纸。m3u8 是在线流列表、不是视频文件，不接。
    var videoProgress by remember { mutableStateOf<Float?>(null) }
    var videoNote by remember { mutableStateOf<String?>(null) }
    val videoWallpaperPick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        videoWallpaperBusy = true
        videoWallpaperFailed = false
        videoNote = null
        videoProgress = null
        scope.launch {
            var wasTranscoded = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val mime = localContext.contentResolver.getType(uri) ?: ""
                    val tail = uri.lastPathSegment ?: ""
                    if (mime == "image/gif" || tail.endsWith(".gif", ignoreCase = true)) {
                        val stream = localContext.contentResolver.openInputStream(uri)
                            ?: return@runCatching VideoWallpaperResult.FAILED
                        if (com.github.tvbox.osc.ui.theme.WallpaperStore.applyLocal(stream)) {
                            VideoWallpaperResult.GIF_OK
                        } else {
                            VideoWallpaperResult.FAILED
                        }
                    } else {
                        val probe = com.github.tvbox.osc.ui.theme.VideoTranscoder
                            .probe(localContext, uri)
                            ?: return@runCatching VideoWallpaperResult.NO_VIDEO
                        if (com.github.tvbox.osc.ui.theme.VideoTranscoder
                                .isDirectlyPlayable(probe)
                        ) {
                            val stream = localContext.contentResolver.openInputStream(uri)
                                ?: return@runCatching VideoWallpaperResult.FAILED
                            if (com.github.tvbox.osc.ui.theme.WallpaperStore
                                    .applyLocalVideo(stream)
                            ) {
                                VideoWallpaperResult.OK
                            } else {
                                VideoWallpaperResult.FAILED
                            }
                        } else {
                            val tmp = com.github.tvbox.osc.ui.theme.WallpaperStore
                                .transcodeTempFile()
                                ?: return@runCatching VideoWallpaperResult.FAILED
                            val ok = com.github.tvbox.osc.ui.theme.VideoTranscoder
                                .transcode(localContext, uri, tmp) { p ->
                                    scope.launch { videoProgress = p }
                                }
                            if (!ok) return@runCatching VideoWallpaperResult.FAILED
                            wasTranscoded = true
                            if (com.github.tvbox.osc.ui.theme.WallpaperStore
                                    .applyLocalVideoFile(tmp)
                            ) {
                                VideoWallpaperResult.OK
                            } else {
                                VideoWallpaperResult.FAILED
                            }
                        }
                    }
                }.getOrElse { VideoWallpaperResult.FAILED }
            }
            videoWallpaperBusy = false
            videoProgress = null
            when (result) {
                VideoWallpaperResult.OK -> {
                    videoWallpaperFailed = false
                    // r483：设成功了要吭一声，之前不显示提示。转过码的也说明白。
                    videoNote = if (wasTranscoded) "已转码为 H.264 并设为动态壁纸"
                    else "动态壁纸已设置"
                }
                VideoWallpaperResult.GIF_OK -> {
                    videoNote = "GIF 已设为图片壁纸（会动）"
                }
                VideoWallpaperResult.NO_VIDEO -> {
                    videoNote = "这个文件里没有视频，换一个试试"
                    videoWallpaperFailed = true
                }
                VideoWallpaperResult.FAILED -> {
                    videoWallpaperFailed = true
                }
            }
        }
    }


    val listState = rememberScrollState()

    AppTopBarScaffold(
        containerColor = wallpaperAwarePageBackground(MaterialTheme.colorScheme.surfaceContainer),
        titleContent = {
            Text(
                text = stringResource(R.string.settings_theme),
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
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Spacer(Modifier.height(topPad - 20.dp))

            SettingsCard(SettingsCardPosition.SINGLE) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoneMockupPreview()
                }
            }

                ThemeCard(SettingsCardPosition.SINGLE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "主题",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        // 3.09：浮雕 / 曲面开关已连滑杆一起搬进「百变控件」组，卡头只剩标题。
                    }
                    Text(
                        text = "点选整套套用全部参数；手动拖动下方滑杆后变为自定义",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    ThemePresetGrid(
                        selectedId = if (isCustom) ThemePresets.CUSTOM_ID else "",
                        onSelectCustom = {
                            ThemePresets.markCustom()
                        },
                        // 3.17：36 套已删，默认主题常显（系统默认，不属于任何套）。
                        onSelectDefault = {
                            ThemePackStore.clearCurrent()
                            AppThemeState.setSource(ThemeSource.SYSTEM)
                        },
                        defaultSelected = config.source != ThemeSource.CUSTOM,
                    )
                    if (ThemePresets.hasSnapshot()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceBright,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
                            onClick = {
                                ThemePresets.restoreSnapshot()?.let { snapshot ->
                                    // 3.09：浮雕 / 曲面 / 光感 / 圆角已归百变控件，快照不再还原它们。
                                    highValue = snapshot.highTransparency.toFloat()
                                    posterValue = snapshot.posterTransparency.toFloat()
                                    ambientValue = snapshot.ambientLevel.toFloat()
                                    frostedValue = snapshot.frostedLevel.toFloat()
                                    wallpaperValue = snapshot.wallpaperLevel.toFloat()
                                    veilValue = snapshot.detailVeilLevel.toFloat()
                                    blurValue = snapshot.glassBlurDp
                                    distortionValue = snapshot.glassDistortionDp
                                    translucencyValue = snapshot.glassTranslucency
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = "返回上一套",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // 2.99 主题包一键导入：总开关 + 选择文件夹 + 已导入包网格（点包即应用主题+壁纸）+ 每包删除钮；
                // 包是额外选项。applyPack 里已走 ThemePresets.apply，这里只把滑杆值跟包走。
                ThemePackGroup(onApplyPack = { preset ->
                    // 3.09：主题包不再写浮雕 / 曲面 / 光感 / 圆角（已归百变控件），只同步不搬的项。
                    highValue = preset.highTransparency.toFloat()
                    posterValue = preset.posterTransparency.toFloat()
                    ambientValue = preset.ambientLevel.toFloat()
                    frostedValue = preset.frostedLevel.toFloat()
                    wallpaperValue = preset.wallpaperLevel.toFloat()
                    veilValue = preset.detailVeilLevel.toFloat()
                    blurValue = preset.glassBlurDp
                    distortionValue = preset.glassDistortionDp
                    translucencyValue = preset.glassTranslucency
                })

                        SettingsGroup(title = stringResource(R.string.theme_color)) {
                ThemeCard(SettingsCardPosition.FIRST) {
                    CustomThemeSwitchRow(
                        checked = isCustom,
                        onCheckedChange = { checked ->
                            AppThemeState.setSource(
                                if (checked) ThemeSource.CUSTOM else ThemeSource.SYSTEM,
                            )
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.theme_pure_black),
                        checked = config.pureBlack,
                        onCheckedChange = { AppThemeState.setPureBlack(it) },
                    )
                }
                ThemeCard(SettingsCardPosition.MIDDLE) {
                    ThemeModeRow(
                        currentMode = config.mode,
                        onModeSelected = { AppThemeState.setMode(it) },
                    )
                }
                ThemeCard(SettingsCardPosition.MIDDLE, enabled = isCustom) {
                    PresetSeedsRow(
                        currentSeed = config.seedArgb,
                        style = config.style,
                        enabled = isCustom,
                        onSeedSelected = { AppThemeState.setSeed(it) },
                    )
                }
                ThemeCard(SettingsCardPosition.MIDDLE, enabled = isCustom) {
                    CustomSeedRow(
                        seedArgb = config.seedArgb,
                        enabled = isCustom,
                        onClick = { seedPickerOpen = true },
                    )
                }
                ThemeCard(SettingsCardPosition.MIDDLE) {
                    TextColorRow(
                        textColorArgb = config.textColorArgb,
                        onClick = { textColorPickerOpen = true },
                        onReset = { AppThemeState.setTextColor(0) },
                    )
                }
                ThemeCard(SettingsCardPosition.LAST, enabled = isCustom) {
                    VariantSelectorRow(
                        currentStyle = config.style,
                        enabled = isCustom,
                        onStyleSelected = { AppThemeState.setStyle(it) },
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.theme_app_effects)) {
                // 3.09：浮雕 / 曲面 / 卡片形状三张卡已搬进下面「百变控件」组（原位置删除）。
                // 3.07 百变控件（第一阶段地基 + 第二阶段导出/导入 + C-edit
                // 长按编辑弹窗（3.14 改组值） / 隐藏 / 重排 / 三档位置）：
                // 嵌在应用效果组内、紧跟卡片形状卡之后，不拆原有
                // FIRST/MIDDLE/LAST 位置链；设置页一律扁平，不受浮雕影响，
                // 颜色跟当前主题走，不写死固定色。
                // 3.14：首页界面整组已拿掉（只剩经典一个选项的设置行没有意义；立体不做
                // 单独的首页界面，3.15 做成主题里一个可选项）。首页一律经典 Grid。
                // 横排/竖排排布切换还在 SearchSettingsSheet 里（首页 ⋮ 按钮进），这里不留。
                SettingsGroup(title = "随心所欲") {
                    ControlThemeSettingsContent(includeFloatBallSwitch = true)
                }
                // 3.09：圆角卡已搬进「百变控件」组（原位置删除）。
                FxEffectCard(
                    position = SettingsCardPosition.FIRST,
                    title = stringResource(R.string.fx_anim_speed),
                    onReset = { EffectsState.resetAnimSpeed() },
                ) {
                    CapsuleSegmentedButton(
                        options = listOf(
                            SegmentOption(stringResource(R.string.fx_anim_off), EffectsState.ANIM_OFF),
                            SegmentOption(stringResource(R.string.fx_anim_slow), EffectsState.ANIM_SLOW),
                            SegmentOption(stringResource(R.string.fx_anim_standard), EffectsState.ANIM_STANDARD),
                            SegmentOption(stringResource(R.string.fx_anim_fast), EffectsState.ANIM_FAST),
                        ),
                        selectedValue = effects.animSpeed,
                        onOptionSelected = { EffectsState.setAnimSpeed(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 3.09：光感亮度卡已搬进「百变控件」组（原位置删除）。
                FxEffectCard(
                    position = SettingsCardPosition.MIDDLE,
                    title = stringResource(R.string.fx_high_transparency),
                    valueText = highValue.roundToInt().toString(),
                    onReset = { EffectsState.resetHighTransparency() },
                ) {
                    Slider(
                        value = highValue,
                        onValueChange = { highValue = it; EffectsState.setHighTransparencyLevelPreview(it) },
                        onValueChangeFinished = { EffectsState.setHighTransparencyLevel(highValue.roundToInt()) },
                        valueRange = EffectsState.LEVEL_RANGE,
                        colors = fxSliderColors(),
                    )
                }
                FxEffectCard(
                    position = SettingsCardPosition.MIDDLE,
                    title = stringResource(R.string.fx_poster_transparency),
                    valueText = posterValue.roundToInt().toString(),
                    onReset = { EffectsState.resetPosterTransparency() },
                ) {
                    Slider(
                        value = posterValue,
                        onValueChange = { posterValue = it; EffectsState.setPosterTransparencyLevelPreview(it) },
                        onValueChangeFinished = { EffectsState.setPosterTransparencyLevel(posterValue.roundToInt()) },
                        valueRange = EffectsState.LEVEL_RANGE,
                        colors = fxSliderColors(),
                    )
                }
                FxEffectCard(
                    position = SettingsCardPosition.MIDDLE,
                    title = stringResource(R.string.fx_wallpaper),
                    valueText = wallpaperValue.roundToInt().toString(),
                    onReset = { EffectsState.resetWallpaperLevel() },
                    titleActions = {
                        val globalOn = effects.globalTransparent
                        TextButton(
                            onClick = { EffectsState.setGlobalTransparent(!globalOn) },
                        ) {
                            Text(
                                text = stringResource(R.string.fx_global_transparent),
                                color = if (globalOn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    },
                ) {
                    val wallpaperUrl = com.github.tvbox.osc.ui.theme.WallpaperStore.url
                    val wallpaperLocal = com.github.tvbox.osc.ui.theme.WallpaperStore.local
                    val videoWallpaperSet = com.github.tvbox.osc.ui.theme.WallpaperStore.video
                    val hasWallpaperSource = wallpaperUrl.isNotBlank() || wallpaperLocal || videoWallpaperSet
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !wallpaperBusy) { wallpaperDialogOpen = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.fx_wallpaper_link),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (wallpaperLocal) {
                                stringResource(R.string.fx_wallpaper_local)
                            } else {
                                wallpaperUrl.ifBlank {
                                    stringResource(R.string.fx_wallpaper_unset)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = !wallpaperBusy,
                            onClick = { wallpaperLocalPick.launch("image/*") },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = stringResource(R.string.fx_wallpaper_local),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    // r481：视频壁纸行（应用内背景播视频）。与图片壁纸二选一：选视频会替换图片，
                    // 选图片/链接会替换视频；r486：底下的清除/重置只动图片，不动视频——视频只用本行删除键清。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.fx_video_wallpaper),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (videoWallpaperSet) {
                                stringResource(R.string.fx_video_wallpaper_local)
                            } else {
                                stringResource(R.string.fx_wallpaper_unset)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = !videoWallpaperBusy,
                            onClick = { videoWallpaperPick.launch(arrayOf("video/*", "image/gif")) },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = stringResource(R.string.fx_video_wallpaper_local),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // r482：删除视频壁纸（更换 = 再选一个视频，会覆盖）。
                        if (videoWallpaperSet) {
                            IconButton(
                                enabled = !videoWallpaperBusy,
                                onClick = {
                                    com.github.tvbox.osc.ui.theme.WallpaperStore.clearVideo()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.fx_wallpaper_clear),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    val progress = videoProgress
                    val note = videoNote
                    if (videoWallpaperBusy && progress != null) {
                        Text(
                            text = stringResource(
                                R.string.fx_video_wallpaper_converting,
                                (progress * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (videoWallpaperBusy) {
                        Text(
                            text = stringResource(R.string.fx_wallpaper_downloading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (videoWallpaperFailed) {
                        Text(
                            text = note
                                ?: stringResource(R.string.fx_video_wallpaper_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (note != null) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = hasWallpaperSource && !wallpaperBusy,
                            onClick = {
                                wallpaperBusy = true
                                wallpaperFailed = false
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        com.github.tvbox.osc.ui.theme.WallpaperStore.refresh()
                                    }
                                    wallpaperBusy = false
                                    wallpaperFailed = !ok
                                }
                            },
                        ) {
                            Text(stringResource(R.string.fx_wallpaper_refresh))
                        }
                        TextButton(
                            enabled = hasWallpaperSource && !wallpaperBusy,
                            onClick = { com.github.tvbox.osc.ui.theme.WallpaperStore.clear() },
                        ) {
                            Text(stringResource(R.string.fx_wallpaper_clear))
                        }
                        TextButton(
                            enabled = !wallpaperBusy,
                            onClick = { com.github.tvbox.osc.ui.theme.WallpaperStore.reset() },
                        ) {
                            Text(stringResource(R.string.theme_reset))
                        }
                        if (wallpaperBusy) {
                            Text(
                                text = stringResource(R.string.fx_wallpaper_downloading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (wallpaperFailed) {
                            Text(
                                text = stringResource(R.string.fx_wallpaper_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Slider(
                        value = wallpaperValue,
                        onValueChange = { wallpaperValue = it; EffectsState.setWallpaperLevelPreview(it) },
                        onValueChangeFinished = {
                            EffectsState.setWallpaperLevel(wallpaperValue.roundToInt())
                        },
                        valueRange = EffectsState.LEVEL_RANGE,
                        enabled = hasWallpaperSource,
                        colors = fxSliderColors(),
                    )
                    if (!hasWallpaperSource) {
                        Text(
                            text = stringResource(R.string.fx_wallpaper_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FxEffectCard(
                    position = SettingsCardPosition.MIDDLE,
                    title = stringResource(R.string.fx_detail_veil),
                    valueText = veilValue.roundToInt().toString(),
                    onReset = { EffectsState.resetDetailVeilLevel() },
                ) {
                    Slider(
                        value = veilValue,
                        onValueChange = { veilValue = it; EffectsState.setDetailVeilLevelPreview(it) },
                        onValueChangeFinished = {
                            EffectsState.setDetailVeilLevel(veilValue.roundToInt())
                        },
                        valueRange = EffectsState.LEVEL_RANGE,
                        colors = fxSliderColors(),
                    )
                }
                FxEffectCard(
                    position = SettingsCardPosition.MIDDLE,
                    title = stringResource(R.string.fx_ambient_bg),
                    valueText = ambientValue.roundToInt().toString(),
                    onReset = { EffectsState.resetAmbientLevel() },
                ) {
                    Slider(
                        value = ambientValue,
                        onValueChange = { ambientValue = it; EffectsState.setAmbientLevelPreview(it) },
                        onValueChangeFinished = { EffectsState.setAmbientLevel(ambientValue.roundToInt()) },
                        valueRange = EffectsState.LEVEL_RANGE,
                        colors = fxSliderColors(),
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RowLeadingIcon(R.drawable.ic_theme_liquid_glass, enabled = true)
                            Text(
                                text = stringResource(R.string.theme_liquid_glass),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { LiquidGlassState.restoreDefaults() }) {
                                Text(stringResource(R.string.theme_reset))
                            }
                        }
                        Text(
                            text = stringResource(R.string.theme_liquid_glass_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.theme_nav_bar),
                        checked = glassConfig.navbarEnabled,
                        onCheckedChange = { LiquidGlassState.setNavbarEnabled(it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.theme_app_controls),
                        checked = glassConfig.controlsEnabled,
                        onCheckedChange = { LiquidGlassState.setControlsEnabled(it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    GlassSliderRow(
                        title = stringResource(R.string.theme_blur),
                        value = blurValue,
                        valueRange = LiquidGlassState.BLUR_RANGE,
                        onValueChange = { blurValue = it },
                        onValueChangeFinished = { LiquidGlassState.setBlurDp(blurValue) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    GlassSliderRow(
                        title = stringResource(R.string.theme_distortion),
                        value = distortionValue,
                        valueRange = LiquidGlassState.DISTORTION_RANGE,
                        onValueChange = { distortionValue = it },
                        onValueChangeFinished = { LiquidGlassState.setDistortionDp(distortionValue) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    GlassSliderRow(
                        title = stringResource(R.string.theme_translucency),
                        value = translucencyValue,
                        valueRange = LiquidGlassState.TRANSLUCENCY_RANGE,
                        onValueChange = { translucencyValue = it },
                        onValueChangeFinished = { LiquidGlassState.setTranslucency(translucencyValue) },
                        valueText = "${(translucencyValue * 100f).roundToInt()}%",
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.theme_dispersion),
                        checked = glassConfig.dispersion,
                        onCheckedChange = { LiquidGlassState.setDispersion(it) },
                    )
                }
                FxEffectCard(
                    position = SettingsCardPosition.MIDDLE,
                    title = stringResource(R.string.fx_frosted_bars),
                    valueText = frostedValue.roundToInt().toString(),
                    onReset = { EffectsState.resetFrostedLevel() },
                ) {
                    Slider(
                        value = frostedValue,
                        onValueChange = { frostedValue = it; EffectsState.setFrostedLevelPreview(it) },
                        onValueChangeFinished = { EffectsState.setFrostedLevel(frostedValue.roundToInt()) },
                        valueRange = EffectsState.LEVEL_RANGE,
                        colors = fxSliderColors(),
                    )
                }
                FxEffectCard(
                    position = SettingsCardPosition.LAST,
                    title = stringResource(R.string.fx_font_size),
                    onReset = { EffectsState.resetFontStep() },
                ) {
                    CapsuleSegmentedButton(
                        options = listOf(
                            SegmentOption(stringResource(R.string.fx_font_small), EffectsState.FONT_SMALL),
                            SegmentOption(stringResource(R.string.fx_font_standard), EffectsState.FONT_STANDARD),
                            SegmentOption(stringResource(R.string.fx_font_large), EffectsState.FONT_LARGE),
                        ),
                        selectedValue = effects.fontStep,
                        onOptionSelected = { EffectsState.setFontStep(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(36.dp))
        }
    }

    if (seedPickerOpen) {
        ThemeColorPickerSheet(
            title = stringResource(R.string.theme_custom_color),
            initialColor = config.seedArgb,
            onConfirm = { argb ->
                AppThemeState.setSeed(argb)
            },
            onDismiss = { seedPickerOpen = false },
        )
    }

    if (textColorPickerOpen) {
        ThemeColorPickerSheet(
            title = stringResource(R.string.theme_text_color),
            initialColor = if (config.textColorArgb != 0) config.textColorArgb else config.seedArgb,
            onConfirm = { argb ->
                AppThemeState.setTextColor(argb)
            },
            onDismiss = { textColorPickerOpen = false },
        )
    }


    if (wallpaperDialogOpen) {
        TextEditDialog(
            title = stringResource(R.string.fx_wallpaper_link),
            initialText = com.github.tvbox.osc.ui.theme.WallpaperStore.url,
            trailingAction = {
                IconButton(
                    enabled = !wallpaperBusy,
                    onClick = {
                        wallpaperDialogOpen = false
                        wallpaperLocalPick.launch("image/*")
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = stringResource(R.string.fx_wallpaper_local),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            onDismiss = { wallpaperDialogOpen = false },
            onConfirm = { text ->
                wallpaperDialogOpen = false
                if (text.isBlank()) {
                    com.github.tvbox.osc.ui.theme.WallpaperStore.clear()
                } else {
                    wallpaperBusy = true
                    wallpaperFailed = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            com.github.tvbox.osc.ui.theme.WallpaperStore.download(text)
                        }
                        wallpaperBusy = false
                        wallpaperFailed = !ok
                    }
                }
            },
        )
    }

}

/**
 * 3.09 悬浮球：随心所欲组内容抽成的公共 composable。
 *
 * 设置页 ThemeSettingsScreen 与悬浮球浮窗面板（FloatControlPanelView 内 ComposeView）
 * 共用同一份内容，改一处两处都变；组内原有逻辑（预设 / 滑杆 / 浮雕 / 曲面 / 光感 /
 * 配色 / 覆盖范围 / 导出导入 / 隐藏 / 重排 / 对齐 / 恢复默认）一字未动，只从
 * ThemeSettingsScreen 搬到这里，连同它依赖的局部 state、导入 launcher、导出函数、
 * 自定义强调色选色器 dialog 一起搬。
 *
 * 3.11 按元素分两组：全局项（悬浮球开关 / 总开关 / 预设 / 覆盖范围 / 导出导入 /
 * 隐藏元素 / 重排 / 对齐 / 恢复默认）在上，两组小节（卡片 / 胶囊，
 * 各见 GroupSection）在下；全局项内容与行为一字不动，
 * 只挪顺序与 position；每组滑杆 state 与 glide Job 在小节内部 remember，按组独立。
 *
 * @param includeFloatBallSwitch 1.14：随心所欲悬浮球已去掉，该参数不再起作用（保留签名免改调用方）。
 */
@Composable
fun ControlThemeSettingsContent(includeFloatBallSwitch: Boolean = false, isFloatingPanel: Boolean = false) {
    // 3.07 百变控件：读 ControlThemeStore.current（mutableStateOf），
    // 切预设 / 改字段即重组，设置页与全 App 按钮即时变。
    // 3.11 按元素分五组：滑杆本地值与 glide Job 不再放这里，改放每组小节
    // GroupSection 内部 remember（按组独立）；这里只留全局项用的 state。
    val controlTheme = ControlThemeStore.config
    // 3.11：自定义强调色选色器按组打开——记录正在改色的组，null = 没打开；
    // 胶囊组「自定义强调色」卡点开时设 group，确认后调 setGroupCustomColorArgb(group, argb)。
    var colorPickerGroup by remember { mutableStateOf<ControlGroup?>(null) }
    // 3.11：自定义字体颜色选色器按组打开——胶囊组「胶囊字体颜色」卡点“自定义”时设 group，
    // 确认后调 setGroupFontColorArgb(group, argb)。
    var fontColorPickerGroup by remember { mutableStateOf<ControlGroup?>(null) }
    // 3.17：自定义底色选色器按组打开——胶囊组「胶囊底色」卡点“自定义”时设 group，
    // 确认后调 setGroupBaseColorArgb(group, argb)。
    var baseColorPickerGroup by remember { mutableStateOf<ControlGroup?>(null) }
    // 3.17：自定义选中颜色选色器按组打开——胶囊组「胶囊选中颜色」卡点“自定义”时设 group，
    // 确认后调 setGroupSelectedContentColorArgb(group, argb)。
    var selectedColorPickerGroup by remember { mutableStateOf<ControlGroup?>(null) }
    // 3.19：自定义描边颜色选色器按组打开——胶囊组「胶囊描边颜色」卡点“自定义”时设 group，
    // 确认后调 setGroupBorderColorArgb(group, argb)。
    var borderColorPickerGroup by remember { mutableStateOf<ControlGroup?>(null) }
    val scope = rememberCoroutineScope()
    val localContext = LocalContext.current
    // 3.07 第二阶段：控件主题导入——系统文件选择器（GetContent 复用壁纸
    // 本地选取的写法）；选到 JSON 后读文本、ControlThemeIo 全量校验并应用，
    // 成功 / 失败都 Toast 提示，坏文件不崩溃、不应用半截状态。
    val controlThemeImportPick = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val stream = localContext.contentResolver.openInputStream(uri)
                        ?: return@withContext ControlThemeIo.ImportResult.Err("打不开这个文件")
                    val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    ControlThemeIo.importAndApply(text)
                } catch (e: Exception) {
                    ControlThemeIo.ImportResult.Err("读取文件失败")
                }
            }
            when (result) {
                is ControlThemeIo.ImportResult.Ok -> Toast.makeText(
                    localContext,
                    "已导入控件主题「" + result.name + "」",
                    Toast.LENGTH_LONG,
                ).show()
                is ControlThemeIo.ImportResult.Err -> Toast.makeText(
                    localContext,
                    "导入失败：" + result.reason,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // 3.07 第二阶段：控件主题导出——写 JSON 文件后系统分享（ACTION_SEND 复用
    // MiniAppConfigPanel / SettingsPage 悬浮日志导出的 FileProvider 写法，
    // 文件落 getExternalFilesDir/control_themes/）。
    fun exportControlTheme() {
        scope.launch {
            val theme = ControlThemeStore.config
            val file = withContext(Dispatchers.IO) {
                try {
                    val base = localContext.getExternalFilesDir(null) ?: return@withContext null
                    val dir = File(base, "control_themes")
                    if (!dir.exists()) dir.mkdirs()
                    val out = File(dir, ControlThemeIo.exportFileName(theme))
                    out.writeText(ControlThemeIo.exportJson(theme), Charsets.UTF_8)
                    out
                } catch (_: Throwable) {
                    null
                }
            }
            if (file == null) {
                Toast.makeText(localContext, "导出失败，请重试", Toast.LENGTH_LONG).show()
            } else {
                val uri = FileProvider.getUriForFile(
                    localContext,
                    localContext.packageName + ".fileprovider",
                    file,
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                localContext.startActivity(Intent.createChooser(share, "分享控件主题"))
            }
        }
    }

    // 3.11 新顺序：全局项在上，五组在下。以下全局项内容与行为一字不动，
    // 只挪顺序与 position；position 重排：整组内容第一张卡 FIRST、
    // 最后一张卡（播放组最后一张）LAST，其余 MIDDLE。
    // 1.14：随心所欲悬浮球已去掉（开关已删；FloatControl 永不挂球）。
    // 3.09 更正：随心所欲总开关（只留这一个；原主题开关已删）。
    // 开 = 随心所欲生效；关 = 随心所欲完全不生效，恢复最初的 AVBox
    // （预设、滑杆、隐藏、重排、悬浮球都不生效，值照存，重开即恢复）。
    FxEffectCard(
        position = SettingsCardPosition.FIRST,
        title = "总开关",
        onReset = {
            ControlThemeStore.setControlThemeEnabled(true)
            FloatControl.refreshVisibility()
        },
    ) {
        SettingsSwitchRow(
            title = "随心所欲总开关",
            subtitle = "开：形状、浮雕、曲面、光感、水光、大小、配色、字体颜色全按随心所欲的来；关：随心所欲完全不生效，恢复最初的 AVBox",
            checked = ControlThemeStore.isControlThemeEnabled(),
            onCheckedChange = {
                ControlThemeStore.setControlThemeEnabled(it)
                FloatControl.refreshVisibility()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // 3.07 第二阶段：覆盖范围——七种元素各一个开关，改即
    // setCoveredKinds；没勾选的种类回落默认控件主题。
    // 3.62：播放器是第七种，没勾选时直接不画胶囊（不是回落默认主题）。
    // 重置 = 恢复全七种都勾选。
    FxEffectCard(
        position = SettingsCardPosition.MIDDLE,
        title = "覆盖范围",
        onReset = { ControlThemeStore.setCoveredKinds(ControlThemeIo.allKinds()) },
    ) {
        Text(
            text = "只对选中的元素用当前控件主题；没选中的元素用默认控件主题（播放器没选中时直接不画胶囊）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // 1.14：七种分两排（4 + 3 个选项片）；开关一行放不下 4 个，改选项片。
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlElementKind.values().toList().chunked(4).forEach { rowKinds ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowKinds.forEach { kind ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            EditorOptionChip(
                                label = ControlThemeIo.kindDisplayName(kind),
                                selected = kind in controlTheme.coveredKinds,
                                onClick = {
                                    val next = controlTheme.coveredKinds.toMutableSet()
                                    if (kind in next) next.remove(kind) else next.add(kind)
                                    ControlThemeStore.setCoveredKinds(next)
                                },
                            )
                        }
                    }
                    repeat(4 - rowKinds.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    // 1.14：导出 / 导入控件主题已去掉。
    // 1.14：恢复默认已去掉（就是覆盖范围重置）。
    // 3.11 两组小节：全局项在上，两组在下；每组一个标题行 + 该组管用的卡。
    GroupSection(
        group = ControlGroup.CARD,
        onOpenColorPicker = { colorPickerGroup = it },
        onOpenFontColorPicker = { fontColorPickerGroup = it },
        onOpenBaseColorPicker = { baseColorPickerGroup = it },
        onOpenSelectedColorPicker = { selectedColorPickerGroup = it },
        onOpenBorderColorPicker = { borderColorPickerGroup = it },
    )
    GroupSection(
        group = ControlGroup.CAPSULE,
        onOpenColorPicker = { colorPickerGroup = it },
        onOpenFontColorPicker = { fontColorPickerGroup = it },
        onOpenBaseColorPicker = { baseColorPickerGroup = it },
        onOpenSelectedColorPicker = { selectedColorPickerGroup = it },
        onOpenBorderColorPicker = { borderColorPickerGroup = it },
    )
    // 3.17：自定义强调色选色器按组打开（胶囊组「自定义强调色」卡点开时设 group；
    // 胶囊配色已删，选色盘一直能开）。确认后只改该组的颜色，不动别的组；取消 / 关闭时清 null。
    val pickerGroup = colorPickerGroup
    if (pickerGroup != null) {
        val pickerTheme = ControlThemeStore.groupConfig(pickerGroup)
        ThemeColorPickerSheet(
            title = "自定义强调色",
            initialColor = if (pickerTheme.customColorArgb != 0) {
                pickerTheme.customColorArgb
            } else {
                MaterialTheme.colorScheme.primary.toArgb()
            },
            onConfirm = { argb ->
                ControlThemeStore.setGroupCustomColorArgb(pickerGroup, argb)
                colorPickerGroup = null
            },
            onDismiss = { colorPickerGroup = null },
        )
    }
    // 3.11：自定义字体颜色选色器按组打开（胶囊组字体颜色卡点“自定义”时打开）。
    // 确认后只改该组的字体颜色；取消 / 关闭时清 null。
    val fontPickerGroup = fontColorPickerGroup
    if (fontPickerGroup != null) {
        val pickerTheme = ControlThemeStore.groupConfig(fontPickerGroup)
        ThemeColorPickerSheet(
            title = "自定义字体颜色",
            initialColor = if (pickerTheme.fontColorArgb != 0) {
                pickerTheme.fontColorArgb
            } else {
                Color.White.toArgb()
            },
            onConfirm = { argb ->
                ControlThemeStore.setGroupFontColorArgb(fontPickerGroup, argb)
                fontColorPickerGroup = null
            },
            onDismiss = { fontColorPickerGroup = null },
        )
    }
    // 3.17：自定义底色选色器按组打开（胶囊组底色卡点“自定义”时打开）。
    // 确认后只改该组的底色；取消 / 关闭时清 null。
    val basePickerGroup = baseColorPickerGroup
    if (basePickerGroup != null) {
        val pickerTheme = ControlThemeStore.groupConfig(basePickerGroup)
        ThemeColorPickerSheet(
            title = "自定义底色",
            initialColor = if (pickerTheme.baseColorArgb != 0) {
                pickerTheme.baseColorArgb
            } else {
                ControlThemePresets.DEFAULT.baseColorArgb
            },
            onConfirm = { argb ->
                ControlThemeStore.setGroupBaseColorArgb(basePickerGroup, argb)
                baseColorPickerGroup = null
            },
            onDismiss = { baseColorPickerGroup = null },
        )
    }
    // 3.17：自定义选中颜色选色器按组打开（胶囊组选中颜色卡点“自定义”时打开）。
    // 确认后只改该组的选中颜色；取消 / 关闭时清 null。
    val selectedPickerGroup = selectedColorPickerGroup
    if (selectedPickerGroup != null) {
        val pickerTheme = ControlThemeStore.groupConfig(selectedPickerGroup)
        ThemeColorPickerSheet(
            title = "自定义选中颜色",
            initialColor = if (pickerTheme.selectedContentColorArgb != 0) {
                pickerTheme.selectedContentColorArgb
            } else {
                ControlThemePresets.DEFAULT.selectedContentColorArgb
            },
            onConfirm = { argb ->
                ControlThemeStore.setGroupSelectedContentColorArgb(selectedPickerGroup, argb)
                selectedColorPickerGroup = null
            },
            onDismiss = { selectedColorPickerGroup = null },
        )
    }
    // 3.19：自定义描边颜色选色器按组打开（胶囊组描边颜色卡点“自定义”时打开）。
    // 确认后只改该组的描边颜色；取消 / 关闭时清 null。初值 = 当前生效的描边颜色。
    val borderPickerGroup = borderColorPickerGroup
    if (borderPickerGroup != null) {
        ThemeColorPickerSheet(
            title = "自定义描边颜色",
            initialColor = capsuleGroupBorderColor().toArgb(),
            onConfirm = { argb ->
                ControlThemeStore.setGroupBorderColorArgb(borderPickerGroup, argb)
                borderColorPickerGroup = null
            },
            onDismiss = { borderColorPickerGroup = null },
        )
    }
}


/** 3.19 描边颜色预设：金边 = 暖金 #FFD34D（与选中金字同色）；银边 = 浅银灰 #C0C0C0。 */
private val BORDER_COLOR_GOLD: Int = 0xFFFFD34D.toInt()
private val BORDER_COLOR_SILVER: Int = 0xFFC0C0C0.toInt()

/**
 * 3.11 按元素分两组：每组一个小节（标题行 + 该组管用的卡）。
 *
 * 滑杆本地值与重置平滑 Job（elevationGlide / curvatureGlide）放在小节内部
 * remember：两个调用点是两个独立的 composition，state 按组独立，改卡片组
 * 滑杆不会串到胶囊组。remember 键用该组 groupConfig 的对应字段值，store
 * 一变（切预设 / 点重置 / 拖动预览）本地值即跟新值同步。
 * 每张卡的 onReset 只调本组的 setter 回默认值，不调全局 setter
 * （全局 setter 现在只剩预设 / 恢复默认在用）。
 * 每组包含的项：卡片组 6 项（大小 / 形状 / 浮雕 / 曲面 / 光感 / 水光）；
 * 胶囊组 9 项（胶囊风格 / 胶囊装饰 / 大小 / 圆角倍率 / 字体颜色 / 自定义强调色 / 底色 / 选中颜色 / 水光）。
 * 字号不在两组里（用户没点名要；字段仍在数据模型里，预设仍会写，
 * 渲染仍读组值）。圆角倍率只在胶囊组有滑杆（卡片组形状走 shape，不用它）。3.17：胶囊配色已删，自定义强调色卡一直显示；底色与选中颜色
 * 两张卡与长按编辑器写同一组值，改一边两边同步。
 * 每组最后一张卡是水光卡，拿 LAST 圆角。
 */
@Composable
private fun GroupSection(
    group: ControlGroup,
    onOpenColorPicker: (ControlGroup) -> Unit,
    onOpenFontColorPicker: (ControlGroup) -> Unit,
    onOpenBaseColorPicker: (ControlGroup) -> Unit,
    onOpenSelectedColorPicker: (ControlGroup) -> Unit,
    onOpenBorderColorPicker: (ControlGroup) -> Unit,
) {
    val isCardGroup = group == ControlGroup.CARD
    // groupConfig：该组当前主题（拖动预览中读预览值）。
    val groupTheme = ControlThemeStore.groupConfig(group)
    var cornerScaleValue by remember(groupTheme.cornerScale) { mutableStateOf(groupTheme.cornerScale) }
    var elevationValue by remember(groupTheme.reliefMagnitude) { mutableStateOf(groupTheme.reliefMagnitude) }
    // logtest42d: the reset glide job — cancelled the moment the
    // user grabs the slider again, so a drag never fights the glide.
    var elevationGlide by remember { mutableStateOf<Job?>(null) }
    // 2.12 空间曲率：滑杆值与重置平滑，同悬浮立体一对。
    var curvatureValue by remember(groupTheme.curvature) { mutableStateOf(groupTheme.curvature.toFloat()) }
    var curvatureGlide by remember { mutableStateOf<Job?>(null) }
    var lightValue by remember(groupTheme.lightBrightness) { mutableStateOf(groupTheme.lightBrightness.toFloat()) }
    var waterGlossValue by remember(groupTheme.waterGloss) { mutableStateOf(groupTheme.waterGloss.toFloat()) }
    val scope = rememberCoroutineScope()
    val defaultWaterGloss = if (isCardGroup) {
        ControlThemeStore.DEFAULT_CARD_WATER_GLOSS
    } else {
        ControlThemeStore.DEFAULT_CAPSULE_WATER_GLOSS
    }

    Text(
        text = group.displayName() + "组 — " + group.scopeDescription(),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
    // 1.14：胶囊风格整块不显示（选项 + 预览已删）；水光 0-400 一根滑杆走四段（capsuleGlossByWater）。
    // 1.15：大小卡不显示（胶囊/卡片统一 0.5 默认，见 ControlTheme 读入）。
    // 3.26 胶囊装饰（1.14 去掉星星 / 云朵 / 闪电 / 金鱼 / 可爱熊）：默认 / 雪花 / 枫叶 / 烟花 / 气球 五选，只在胶囊组有；
    // 只水滴画（轻透/纽扣不画）；同一张卡里再加「动态」开关，开关只管动不动，不管选哪种装饰，
    // 选「默认」时开关不起作用。切即写 KV，画法按它分支。
    if (!isCardGroup) {
        val decoration = ControlThemeStore.capsuleDecoration()
        val decorationAnimated = ControlThemeStore.isCapsuleDecorationAnimated()
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = "胶囊装饰",
            onReset = { ControlThemeStore.setCapsuleDecoration(ControlTheme.DECORATION_NONE) },
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((label, value) in listOf(
                    "默认" to ControlTheme.DECORATION_NONE,
                    "雪花" to ControlTheme.DECORATION_SNOW,
                    "枫叶" to ControlTheme.DECORATION_LEAF,
                    "烟花" to ControlTheme.DECORATION_FIREWORK,
                    "气球" to ControlTheme.DECORATION_BALLOON,
                )) {
                    EditorOptionChip(
                        label = label,
                        selected = decoration == value,
                        onClick = { ControlThemeStore.setCapsuleDecoration(value) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "动态",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SettingsSwitch(
                    checked = decorationAnimated,
                    onCheckedChange = { ControlThemeStore.setCapsuleDecorationAnimated(it) },
                )
            }
        }
    }
    // 3.18 圆角倍率：只在胶囊组有（卡片组形状走 shape，不用它）。
    // 胶囊边框形状恒为跟主题（cornerMode = 0），倍率直接乘圆角半径，滑了就看得出。
    if (!isCardGroup) {
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = "圆角倍率",
            valueText = "×" + String.format("%.2f", cornerScaleValue),
            onReset = { ControlThemeStore.setGroupCornerScale(group, 1.0f) },
        ) {
            Slider(
                value = cornerScaleValue,
                onValueChange = { cornerScaleValue = it },
                onValueChangeFinished = { ControlThemeStore.setGroupCornerScale(group, cornerScaleValue) },
                valueRange = EffectsState.CORNER_RANGE,
                colors = fxSliderColors(),
            )
        }
    }
    // 1.14：卡片形状整行不显示（五边形 / 六边形 / 八边形 / 圆形已删，只剩默认矩形）。
    // 浮雕：开关 + 强度滑杆 + 档位。开关关时效果归零（渲染门控）、
    // 滑杆值与档位保留，开时恢复原值（原来就是这么做的，搬家不改语义）。
    // 只有卡片组有浮雕。
    if (isCardGroup) {
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = stringResource(R.string.fx_card_elevation),
            valueText = (if (groupTheme.reliefEnabled) elevationValue else 0f).roundToInt().toString(),
            onReset = {
                elevationGlide?.cancel()
                if (elevationValue <= 0f) {
                    ControlThemeStore.setGroupReliefProfile(group, EffectsState.DEFAULT_RELIEF_PROFILE)
                    ControlThemeStore.setGroupReliefMagnitude(group, EffectsState.DEFAULT_CARD_ELEVATION_DP)
                } else {
                    elevationGlide = scope.launch {
                        val start = elevationValue
                        Animatable(start).animateTo(
                            targetValue = 0f,
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = FastOutSlowInEasing,
                            ),
                        ) {
                            elevationValue = value
                            ControlThemeStore.setGroupReliefMagnitudePreview(group, value)
                        }
                        ControlThemeStore.setGroupReliefProfile(group, EffectsState.DEFAULT_RELIEF_PROFILE)
                        ControlThemeStore.setGroupReliefMagnitude(group, EffectsState.DEFAULT_CARD_ELEVATION_DP)
                    }
                }
            },
        ) {
            SettingsSwitchRow(
                title = "启用浮雕",
                subtitle = "关时效果归零、滑杆值与档位保留，开时恢复原值",
                checked = groupTheme.reliefEnabled,
                onCheckedChange = { ControlThemeStore.setGroupReliefEnabled(group, it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Slider(
                value = if (groupTheme.reliefEnabled) elevationValue else 0f,
                enabled = groupTheme.reliefEnabled,
                onValueChange = {
                    elevationGlide?.cancel()
                    elevationValue = it
                    ControlThemeStore.setGroupReliefMagnitudePreview(group, it)
                },
                onValueChangeFinished = { ControlThemeStore.setGroupReliefMagnitude(group, elevationValue) },
                valueRange = EffectsState.ELEVATION_RANGE,
                colors = fxSliderColors(),
            )
            ReliefProfileGrid(
                selectedValue = groupTheme.reliefProfile,
                onOptionSelected = { ControlThemeStore.setGroupReliefProfile(group, it) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        // 曲面：开关 + 曲率滑杆 + 方向开关。开关关时效果归零、值保留，开时恢复原值。
        // 只有卡片组有曲面。
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = stringResource(R.string.fx_spatial_curvature),
            valueText = (if (groupTheme.curvatureEnabled) curvatureValue else 0f).roundToInt().toString() + "%",
            onReset = {
                curvatureGlide?.cancel()
                if (curvatureValue == EffectsState.DEFAULT_CURVATURE.toFloat()) {
                    ControlThemeStore.setGroupCurvature(group, EffectsState.DEFAULT_CURVATURE)
                } else {
                    curvatureGlide = scope.launch {
                        val start = curvatureValue
                        Animatable(start).animateTo(
                            targetValue = EffectsState.DEFAULT_CURVATURE.toFloat(),
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = FastOutSlowInEasing,
                            ),
                        ) {
                            curvatureValue = value
                            ControlThemeStore.setGroupCurvaturePreview(group, value)
                        }
                        ControlThemeStore.setGroupCurvature(group, EffectsState.DEFAULT_CURVATURE)
                    }
                }
            },
        ) {
            SettingsSwitchRow(
                title = "启用曲面",
                subtitle = "关时效果归零、滑杆值与方向保留，开时恢复原值",
                checked = groupTheme.curvatureEnabled,
                onCheckedChange = { ControlThemeStore.setGroupCurvatureEnabled(group, it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Slider(
                value = if (groupTheme.curvatureEnabled) curvatureValue else 0f,
                enabled = groupTheme.curvatureEnabled,
                onValueChange = {
                    curvatureGlide?.cancel()
                    curvatureValue = it
                    ControlThemeStore.setGroupCurvaturePreview(group, it)
                },
                onValueChangeFinished = { ControlThemeStore.setGroupCurvature(group, curvatureValue.toInt()) },
                valueRange = EffectsState.CURVATURE_RANGE,
                colors = fxSliderColors(),
            )
            SettingsSwitchRow(
                title = "曲率往外",
                subtitle = "开 = 往外折，关 = 往里折",
                checked = groupTheme.curvatureOutward,
                onCheckedChange = { ControlThemeStore.setGroupCurvatureOutward(group, it) },
                enabled = groupTheme.curvatureEnabled,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        // 光感亮度：0..200，默认 140（渲染乘子 = 值 / 80）。只有卡片组有光感。
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = stringResource(R.string.fx_light_brightness),
            valueText = lightValue.roundToInt().toString(),
            onReset = { ControlThemeStore.setGroupLightBrightness(group, EffectsState.DEFAULT_LIGHT_BRIGHTNESS) },
        ) {
            Slider(
                value = lightValue,
                onValueChange = { lightValue = it; ControlThemeStore.setGroupLightBrightnessPreview(group, it) },
                onValueChangeFinished = { ControlThemeStore.setGroupLightBrightness(group, lightValue.roundToInt()) },
                valueRange = EffectsState.LIGHT_BRIGHTNESS_RANGE,
                colors = fxSliderColors(),
            )
        }
    } else {
        // 字体颜色：只有胶囊组有。跟主题 = 白字；自定义出选色盘（一步）。
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = "胶囊字体颜色",
            onReset = { ControlThemeStore.setGroupFontColorArgb(group, 0) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenFontColorPicker(group) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (groupTheme.fontColorArgb != 0) {
                                Color(groupTheme.fontColorArgb)
                            } else {
                                Color.White
                            },
                        )
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            RoundedCornerShape(12.dp),
                        ),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = if (groupTheme.fontColorArgb != 0) "已设置，点此换色" else "跟主题（白字），点此选色",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption("跟主题", 0),
                    SegmentOption("自定义", 1),
                ),
                selectedValue = if (groupTheme.fontColorArgb != 0) 1 else 0,
                // 点“自定义”直接出选色盘，不用再点上面那张卡。
                onOptionSelected = {
                    if (it == 1) {
                        onOpenFontColorPicker(group)
                    } else {
                        ControlThemeStore.setGroupFontColorArgb(group, 0)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = "自定义强调色",
            onReset = { ControlThemeStore.setGroupCustomColorArgb(group, 0) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenColorPicker(group) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (groupTheme.customColorArgb != 0) {
                                Color(groupTheme.customColorArgb)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            RoundedCornerShape(12.dp),
                        ),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = if (groupTheme.customColorArgb != 0) "已设置，点此换色" else "未设置，点此选色",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 底色：只有胶囊组有。跟主题 = 深 slate；自定义出选色盘（一步）。
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = "胶囊底色",
            onReset = { ControlThemeStore.setGroupBaseColorArgb(group, 0) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenBaseColorPicker(group) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (groupTheme.baseColorArgb != 0) {
                                Color(groupTheme.baseColorArgb)
                            } else {
                                capsuleGroupBaseColor()
                            },
                        )
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            RoundedCornerShape(12.dp),
                        ),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = if (groupTheme.baseColorArgb != 0) "已设置，点此换色" else "跟主题，点此选色",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption("跟主题", 0),
                    SegmentOption("自定义", 1),
                ),
                selectedValue = if (groupTheme.baseColorArgb != 0) 1 else 0,
                // 点“自定义”直接出选色盘，不用再点上面那张卡。
                onOptionSelected = {
                    if (it == 1) {
                        onOpenBaseColorPicker(group)
                    } else {
                        ControlThemeStore.setGroupBaseColorArgb(group, 0)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 选中颜色：只有胶囊组有。跟主题 = 金色；自定义出选色盘（一步）。
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = "胶囊选中颜色",
            onReset = { ControlThemeStore.setGroupSelectedContentColorArgb(group, 0) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenSelectedColorPicker(group) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (groupTheme.selectedContentColorArgb != 0) {
                                Color(groupTheme.selectedContentColorArgb)
                            } else {
                                capsuleGroupSelectedContentColor()
                            },
                        )
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            RoundedCornerShape(12.dp),
                        ),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = if (groupTheme.selectedContentColorArgb != 0) "已设置，点此换色" else "跟主题（金色），点此选色",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption("跟主题", 0),
                    SegmentOption("自定义", 1),
                ),
                selectedValue = if (groupTheme.selectedContentColorArgb != 0) 1 else 0,
                // 点“自定义”直接出选色盘，不用再点上面那张卡。
                onOptionSelected = {
                    if (it == 1) {
                        onOpenSelectedColorPicker(group)
                    } else {
                        ControlThemeStore.setGroupSelectedContentColorArgb(group, 0)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 描边颜色：只有胶囊组有。跟主题 = 强调色；金边 / 银边一点就换；自定义出选色盘（一步）。
        FxEffectCard(
            position = SettingsCardPosition.MIDDLE,
            title = "胶囊描边颜色",
            onReset = { ControlThemeStore.setGroupBorderColorArgb(group, 0) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenBorderColorPicker(group) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(capsuleGroupBorderColor())
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            RoundedCornerShape(12.dp),
                        ),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = if (groupTheme.borderColorArgb != 0) "已设置，点此换色" else "跟主题，点此选色",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption("跟主题", 0),
                    SegmentOption("金边", 1),
                    SegmentOption("银边", 2),
                    SegmentOption("自定义", 3),
                ),
                selectedValue = when (groupTheme.borderColorArgb) {
                    0 -> 0
                    BORDER_COLOR_GOLD -> 1
                    BORDER_COLOR_SILVER -> 2
                    else -> 3
                },
                // 点“自定义”直接出选色盘，不用再点上面那张卡；金边 / 银边一点就换。
                onOptionSelected = {
                    when (it) {
                        0 -> ControlThemeStore.setGroupBorderColorArgb(group, 0)
                        1 -> ControlThemeStore.setGroupBorderColorArgb(group, BORDER_COLOR_GOLD)
                        2 -> ControlThemeStore.setGroupBorderColorArgb(group, BORDER_COLOR_SILVER)
                        else -> onOpenBorderColorPicker(group)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    // 水光：两组都有，强度滑杆 0..400（0 = 无水光，纯平）。卡片组默认 40
    // （海报照片不盖白），胶囊组默认 400（现水胶囊强度）。每组最后一张卡。
    FxEffectCard(
        position = SettingsCardPosition.LAST,
        title = if (isCardGroup) "卡片水光" else "胶囊水光",
        valueText = waterGlossValue.roundToInt().toString(),
        onReset = { ControlThemeStore.setGroupWaterGloss(group, defaultWaterGloss) },
    ) {
        Slider(
            value = waterGlossValue,
            onValueChange = { waterGlossValue = it; ControlThemeStore.setGroupWaterGlossPreview(group, it) },
            onValueChangeFinished = { ControlThemeStore.setGroupWaterGloss(group, waterGlossValue.roundToInt()) },
            valueRange = EffectsState.WATER_GLOSS_RANGE,
            colors = fxSliderColors(),
        )
        Text(
            // 1.14：水光 0-400 一根滑杆走四段——0-50 纽扣、50-100 轻透、100-150 立体、150-400 水滴。
            // 卡片组只改顶部亮面（海报底是图片衬底，不变色）。
            text = if (isCardGroup) "0 = 无水光（纯平），越大顶部亮面越亮" else "0-50 纽扣 · 50-100 轻透 · 100-150 立体 · 150-400 水滴",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 3.11：两组小节标题里的说明——该组管哪些元素。 */
private fun ControlGroup.scopeDescription(): String = when (this) {
    ControlGroup.CARD -> "海报卡"
    ControlGroup.CAPSULE -> "线路卡、选集卡、源胶囊、分类/筛选 chip、播放页胶囊"
}


/**
 * Relief profile picker as a 3-column grid (3+3 over two rows):
 * the one-row segmented control truncated every four-character
 * name to "平面.../球面.../几何.../青花...". Grid chips size to
 * a third of the row, so all names show in full: first row
 * 平面切面/球面弧形/几何刻面, second row 青花瓷釉/可可键盘/
 * 赛博朋克. (黑金铠甲 cancelled, never an option; 青花瓷球
 * cancelled 2026-10-26, removed in 72.)
 */
@Composable
private fun ReliefProfileGrid(
    selectedValue: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        SegmentOption(stringResource(R.string.fx_relief_flat), EffectsState.RELIEF_PROFILE_FLAT),
        SegmentOption(stringResource(R.string.fx_relief_dome), EffectsState.RELIEF_PROFILE_DOME),
        SegmentOption(stringResource(R.string.fx_relief_facet), EffectsState.RELIEF_PROFILE_FACETED),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(3).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { option ->
                    ReliefProfileChip(
                        option = option,
                        selected = selectedValue == option.value,
                        onOptionSelected = onOptionSelected,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReliefProfileChip(
    option: SegmentOption<Int>,
    selected: Boolean,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
        ),
        onClick = { onOptionSelected(option.value) },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ThemePresetGrid(
    selectedId: String,
    onSelectCustom: () -> Unit,
    onSelectDefault: (() -> Unit)? = null,
    defaultSelected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 3.17：36 套内置主题已删，主题列表只剩默认主题 + 自定义（主题包在下面「主题包」组里照常显示）。
    val items: List<ThemeGridItem> = buildList {
        if (onSelectDefault != null) add(ThemeGridItem.Default)
        add(ThemeGridItem.Custom)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { item ->
                    val label = when (item) {
                        ThemeGridItem.Custom -> "自定义"
                        ThemeGridItem.Default -> "默认主题"
                    }
                    val selected = when (item) {
                        ThemeGridItem.Custom -> selectedId == ThemePresets.CUSTOM_ID
                        ThemeGridItem.Default -> defaultSelected
                    }
                    ThemePresetChip(
                        label = label,
                        dotArgb = null,
                        selected = selected,
                        onClick = {
                            when (item) {
                                ThemeGridItem.Custom -> onSelectCustom()
                                ThemeGridItem.Default -> onSelectDefault?.invoke()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}


/** 主题列表里的两种格子：自定义 / 默认主题（系统默认）。 */
private sealed interface ThemeGridItem {
    data object Custom : ThemeGridItem
    data object Default : ThemeGridItem
}

@Composable
private fun ThemePresetChip(
    label: String,
    dotArgb: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
        ),
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (dotArgb != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(dotArgb)),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// 2.99 主题包一键导入：总开关 + 选择文件夹 + 已导入包网格（点包即应用主题+壁纸）+ 每包删除钮。
// 包是额外选项；开关关时只留开关行（已导入的包仍存着，不删）。
@Composable
private fun ThemePackGroup(onApplyPack: (ThemePreset) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(ThemePackStore.isEnabled()) }
    var packs by remember { mutableStateOf(ThemePackStore.list(context)) }
    var folderDialogOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var applyingId by remember { mutableStateOf<String?>(null) }

    SettingsGroup(title = "主题包") {
        SettingsCard(SettingsCardPosition.FIRST) {
            SettingsSwitchRow(
                title = "主题包",
                subtitle = "从文件夹导入主题包（子文件夹或 zip），主题 + 壁纸一起用",
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    ThemePackStore.setEnabled(checked)
                },
            )
        }
        if (enabled) {
            SettingsCard(SettingsCardPosition.MIDDLE) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { folderDialogOpen = true }) {
                        Text(text = "选择文件夹")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = ThemePackStore.lastFolder().ifEmpty { "未选过文件夹" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (busy) {
                    Text(
                        text = "正在导入…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            SettingsCard(SettingsCardPosition.LAST) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (packs.isEmpty()) {
                        Text(
                            text = "还没有导入的主题包，点「选择文件夹」导入",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        packs.chunked(2).forEach { rowPacks ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowPacks.forEach { pack ->
                                    ThemePackChip(
                                        pack = pack,
                                        selected = ThemePackStore.currentId() == pack.id,
                                        enabled = applyingId == null,
                                        onClick = {
                                            if (applyingId != null) return@ThemePackChip
                                            applyingId = pack.id
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    ThemePackStore.applyPack(pack)
                                                }
                                                applyingId = null
                                                onApplyPack(pack.preset)
                                            }
                                        },
                                        onDelete = {
                                            ThemePackStore.removePack(context, pack.id)
                                            packs = ThemePackStore.list(context)
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(2 - rowPacks.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (folderDialogOpen) {
        ThemePackFolderDialog(
            onDismiss = { folderDialogOpen = false },
            onFolderSelected = { folder ->
                folderDialogOpen = false
                busy = true
                notice = null
                scope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        ThemePackStore.scanFolder(File(folder), context)
                    }
                    busy = false
                    packs = ThemePackStore.list(context)
                    notice = if (imported.isEmpty()) "这个文件夹里没有找到主题包" else "已导入 ${imported.size} 套主题包"
                }
            },
        )
    }
}

@Composable
private fun ThemePackChip(
    pack: ThemePack,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
        ),
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(pack.preset.seedArgb)),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = pack.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// 2.99 主题包文件夹浏览器（Compose 版，对标 AiAssistPanel.pickFolder 的 View 版做法）：
// 从上次文件夹/首个扫描目录/Download 起步，进子文件夹、「选此」、上级目录、新建文件夹，列表固定高 320dp。
@Composable
private fun ThemePackFolderDialog(
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit,
) {
    var currentDir by remember { mutableStateOf(ThemePackStore.defaultStartDir()) }
    var newFolderName by remember { mutableStateOf("") }
    val subs = remember(currentDir) {
        try {
            File(currentDir).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }
    AVBoxDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = "选择文件夹",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = currentDir,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val parent = try {
                File(currentDir).parentFile
            } catch (e: Exception) {
                null
            }
            if (parent != null && parent.exists()) {
                TextButton(onClick = { currentDir = parent.absolutePath }) {
                    Text(text = "↩ 上级目录")
                }
            }
            // 2.51 教训：列表固定高 320dp，在固定区里滚，别让权重分不到空间
            Column(
                modifier = Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState()),
            ) {
                if (subs.isEmpty()) {
                    Text(
                        text = "（空文件夹）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    subs.forEach { sub ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { currentDir = sub.absolutePath },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = "📁 " + sub.name,
                                    maxLines = 1,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            TextButton(onClick = { onFolderSelected(sub.absolutePath) }) {
                                Text(text = "选此")
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { onFolderSelected(currentDir) }) {
                Text(text = "选此文件夹")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text(text = "新文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    val name = newFolderName.trim()
                    if (name.isNotEmpty()) {
                        val dir = File(currentDir, name)
                        if (dir.mkdirs() || dir.exists()) {
                            currentDir = dir.absolutePath
                            newFolderName = ""
                        }
                    }
                }) {
                    Text(text = "新建")
                }
            }
        }
    }
}


@Composable
private fun ThemeCard(
    position: SettingsCardPosition,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    SettingsCard(
        position = position,
        modifier = Modifier.then(if (enabled) Modifier else Modifier.alpha(DisabledAlpha)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun CustomThemeSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLeadingIcon(R.drawable.ic_theme_custom, enabled = true)
        Text(
            text = stringResource(R.string.theme_custom_theme),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        SettingsSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GlassSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueText: String = value.roundToInt().toString(),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.outline,
                disabledActiveTrackColor = MaterialTheme.colorScheme.outline,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

@Composable
private fun fxSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledThumbColor = MaterialTheme.colorScheme.outline,
    disabledActiveTrackColor = MaterialTheme.colorScheme.outline,
    disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
)

/** One app-effect card: title + optional value chip + its own 重置 button.
 * logtest50: optional title-actions slot rendered left of 重置 (which
 * stays rightmost), for the wallpaper card's 全局透明 toggle. */
@Composable
private fun FxEffectCard(
    position: SettingsCardPosition,
    title: String,
    valueText: String? = null,
    onReset: () -> Unit,
    titleActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    SettingsCard(position) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (valueText != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            text = valueText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                titleActions()
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.theme_reset))
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ThemeModeRow(currentMode: Int, onModeSelected: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.theme_mode),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        CapsuleSegmentedButton(
            options = listOf(
                SegmentOption(
                    label = stringResource(R.string.theme_mode_auto),
                    value = ThemeMode.FOLLOW_SYSTEM,
                    iconPainter = painterResource(R.drawable.ic_brightness_auto),
                ),
                SegmentOption(
                    label = stringResource(R.string.theme_mode_light),
                    value = ThemeMode.LIGHT,
                    iconPainter = painterResource(R.drawable.ic_light_mode),
                ),
                SegmentOption(
                    label = stringResource(R.string.theme_mode_dark),
                    value = ThemeMode.DARK,
                    iconPainter = painterResource(R.drawable.ic_dark_mode),
                ),
            ),
            selectedValue = currentMode,
            onOptionSelected = onModeSelected,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CustomSeedRow(seedArgb: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.theme_custom_color),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        val seedShape = MaterialTheme.shapes.small
        Surface(
            modifier = Modifier
                .size(40.dp)
                .clip(seedShape)
                .clickable(enabled = enabled, onClick = onClick),
            shape = seedShape,
            color = Color(seedArgb),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {}
    }
}

@Composable
private fun TextColorRow(textColorArgb: Int, onClick: () -> Unit, onReset: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.theme_text_color),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (textColorArgb != 0) {
            Spacer(Modifier.width(16.dp))
            val dotShape = MaterialTheme.shapes.small
            Surface(
                modifier = Modifier.size(40.dp),
                shape = dotShape,
                color = Color(textColorArgb),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {}
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.theme_reset),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onReset),
            )
        } else {
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.theme_text_color_follow),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VariantSelectorRow(
    currentStyle: PaletteStyle,
    enabled: Boolean,
    onStyleSelected: (PaletteStyle) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.theme_palette_style),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaletteStyles.forEach { (style, labelRes) ->
                FilterChip(
                    selected = currentStyle == style,
                    onClick = { onStyleSelected(style) },
                    enabled = enabled,
                    label = { Text(stringResource(labelRes)) },
                    colors = MaterialTheme.colorScheme.filterChipColors(),
                )
            }
        }
    }
}

@Composable
private fun PresetSeedsRow(
    currentSeed: Int,
    style: PaletteStyle,
    enabled: Boolean,
    onSeedSelected: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wideColumnsMinWidth = PresetSeedMinCardWidth * PresetSeedWideColumns +
            PresetSeedCardSpacing * (PresetSeedWideColumns - 1)
        val columns =
            if (maxWidth >= wideColumnsMinWidth) PresetSeedWideColumns
            else PresetSeedNarrowColumns
        Column {
            PresetSeeds.chunked(columns).forEachIndexed { rowIndex, rowItems ->
                if (rowIndex > 0) Spacer(Modifier.height(PresetSeedCardSpacing))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PresetSeedCardSpacing),
                ) {
                    rowItems.forEach { (nameRes, argb) ->
                        key(argb, style) {
                            PresetSeedCard(
                                nameRes = nameRes,
                                seedArgb = argb,
                                selected = currentSeed == argb,
                                style = style,
                                enabled = enabled,
                                onClick = { onSeedSelected(argb) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetSeedCard(
    nameRes: Int,
    seedArgb: Int,
    selected: Boolean,
    style: PaletteStyle,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewScheme by produceState(
        initialValue = MaterialTheme.colorScheme,
        key1 = seedArgb,
        key2 = style,
    ) {
        value = AppThemeState.previewScheme(seedArgb, style)
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val cardShape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(cardShape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = cardShape,
        color = previewScheme.surfaceContainer,
        border = BorderStroke(2.dp, borderColor),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val unit = maxWidth
            val barHeight = unit * 0.14f
            val gap = unit * 0.05f
            val blockCorner = unit * 0.04f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(unit * 0.08f),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(0.6f)
                        .height(barHeight)
                        .background(previewScheme.primary, RoundedCornerShape(barHeight / 2)),
                )
                if (selected) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(unit * 0.25f),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(unit * 0.04f),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(barHeight)
                                .background(previewScheme.secondary, RoundedCornerShape(blockCorner)),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(barHeight)
                                .background(previewScheme.tertiary, RoundedCornerShape(blockCorner)),
                        )
                    }
                    Spacer(Modifier.height(gap))
                    Text(
                        text = stringResource(nameRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = previewScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}



/** r482：视频壁纸选文件的处理结果。 */
private enum class VideoWallpaperResult {
    /** 已设为视频壁纸（直接拷或转码后）。 */
    OK,
    /** 选的是 GIF，已设为图片壁纸（Coil 会动）。 */
    GIF_OK,
    /** 失败（读不到、转码失败、存不下）。 */
    FAILED,
    /** 文件里没有视频轨。 */
    NO_VIDEO,
}
