package com.github.tvbox.osc.player.ui

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.ui.components.ControlEditState
import com.github.tvbox.osc.ui.components.ControlElementKind
import com.github.tvbox.osc.ui.components.controlButtonModifier
import com.github.tvbox.osc.ui.theme.ControlThemeStore

private val TopBarLineHeight = 36.dp

private val PreviewTopPadding = 4.dp

private val FullscreenTopPadding = 12.dp

private val TopBarSysInfoHeight = 16.dp

private val TopBarBatteryIconSize = 16.dp

internal val TopBarActionSize = 42.dp

internal val TopBarActionIconSize = 28.dp

private val TopBarActionSpacing = 6.dp

@Composable
internal fun PlayerActionIcons(
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    size: Dp = TopBarActionSize,
    iconSize: Dp = TopBarActionIconSize,
    spread: Boolean = false,
) {
    Row(
        modifier,
        horizontalArrangement = if (spread) Arrangement.Start else Arrangement.spacedBy(TopBarActionSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerActionIconButton(
            iconRes = R.drawable.ic_detail_cast,
            contentDescription = stringResource(R.string.common_cast),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onCastClicked,
            controlId = "player_top_cast",
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_subtitle,
            contentDescription = stringResource(R.string.player_menu_subtitle),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onSubtitleClicked,
            onLongClick = actions::onSubtitleLongClicked,
            // 3.07：字幕钮长按已有功能，不劫持，只套外观。
            controlId = "player_top_subtitle",
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_danmu,
            contentDescription = stringResource(R.string.player_menu_danmu),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onDanmuSettingClicked,
            onLongClick = actions::onDanmuSettingLongClicked,
            // 3.07：弹幕钮长按已有功能，不劫持，只套外观。
            controlId = "player_top_danmu",
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_audio,
            contentDescription = stringResource(R.string.player_menu_audio_track),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onAudioTrackClicked,
            controlId = "player_top_audio",
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_video,
            contentDescription = stringResource(R.string.player_menu_video_track),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onVideoTrackClicked,
            controlId = "player_top_video",
        )
    }
}

@Composable
internal fun VesperTopBarActions(actions: PlayerActions) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(TopBarActionSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerActionIconButton(
            iconRes = R.drawable.ic_detail_cast,
            contentDescription = stringResource(R.string.common_cast),
            box = TopBarActionSize,
            iconSize = TopBarActionIconSize,
            expand = false,
            onClick = actions::onCastClicked,
            controlId = "player_top_cast",
        )
        // 3.07 第二阶段 C-edit：信息钮隐藏后不渲染（设置页可恢复）。
        if (!ControlThemeStore.isElementHidden("player_top_info")) {
            Box(
                // 3.07 百变控件：信息钮接控件主题，长按调编辑；点按仍走内层 Image 的 playerPressEffect。
                Modifier.size(TopBarActionSize)
                    .then(controlButtonModifier("player_top_info", kind = ControlElementKind.PLAYER)),
                contentAlignment = Alignment.Center,
            ) {
            Image(
                painter = painterResource(R.drawable.ic_settings_about),
                contentDescription = stringResource(R.string.player_info),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                modifier = Modifier
                    .size(TopBarActionIconSize)
                    .playerPressEffect(
                        onTap = actions::onInfoOsdClicked,
                        onLongClick = { ControlEditState.requestEdit("player_top_info") },
                    ),
            )
            }
        }
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_gear,
            contentDescription = stringResource(R.string.player_menu_more),
            box = TopBarActionSize,
            iconSize = TopBarActionIconSize,
            expand = false,
            onClick = actions::onParamsClicked,
            controlId = "player_top_params",
        )
    }
}

@Composable
private fun RowScope.PlayerActionIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    box: Dp,
    iconSize: Dp,
    expand: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    // 3.07 百变控件：稳定元素 id（调用处写死）；有功能长按时不劫持，只套外观。
    controlId: String? = null,
) {
    // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
    if (controlId != null && ControlThemeStore.isElementHidden(controlId)) return
    Box(
        // 3.07：只套轻量外观；点按仍走内层 Image 的 playerPressEffect（图标区，不缩点按区）。
        (if (expand) Modifier.weight(1f).height(box) else Modifier.size(box))
            .then(if (controlId != null) controlButtonModifier(controlId, kind = ControlElementKind.PLAYER) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(iconSize)
                // 3.07：playerPressEffect 自带 onLongClick 槽；无功能长按时调编辑，不吞点按。
                .playerPressEffect(
                    onTap = onClick,
                    onLongClick = onLongClick ?: controlId?.let { id ->
                        { ControlEditState.requestEdit(id) }
                    },
                ),
        )
    }
}

internal const val TOP_BAR_ROOT_TAG = "playerTopBarRoot"

internal const val TOP_BAR_BACK_TAG = "playerTopBarBack"

internal const val TOP_BAR_CLOSE_TAG = "playerTopBarClose"

@Composable
fun PlayerTopBar(state: PlayerUiState, actions: PlayerActions) {
    val configuration = LocalConfiguration.current
    val showActionsInTopBar = configuration.orientation != Configuration.ORIENTATION_PORTRAIT
    val rightVisible = state.topRightVisible && !state.previewMode
    val edge = playerEdgePadding()
    val topPad = if (state.previewMode) PreviewTopPadding else FullscreenTopPadding
    val density = LocalDensity.current
    val sysLineHeight = with(density) { TopBarSysInfoHeight.toSp() }
    val topInset = if (LocalConfiguration.current.screenWidthDp >= 600) {
        WindowInsets.displayCutout
    } else {
        WindowInsets.safeDrawing
    }
    val topInsetPx = topInset.getTop(density)
    var barTopPx by remember { mutableStateOf(Float.NaN) }
    val extraTop = if (barTopPx.isNaN()) {
        0.dp
    } else {
        with(density) { (topInsetPx - barTopPx).coerceIn(0f, topInsetPx.toFloat()).toDp() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .testTag(TOP_BAR_ROOT_TAG)
            .onGloballyPositioned {
                barTopPx = it.positionInWindow().y
            }
    ) {
        Box {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(playerDim(R.dimen.vs_140))
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.65f),
                                0.45f to Color.Black.copy(alpha = 0.28f),
                                1f to Color.Transparent,
                            )
                        )
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = edge,
                            end = edge,
                            top = topPad + extraTop,
                            bottom = playerDim(R.dimen.vs_5),
                        ),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (state.topLeftVisible) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        // Back: hierarchical back (exit fullscreen first,
                        // else leave to the previous screen).
                        // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                        if (!ControlThemeStore.isElementHidden("player_back")) {
                            Box(
                                // 3.07 百变控件：返回钮接控件主题，长按调编辑；
                                // pointerInput 放最外层（点按区含表面内边距），不缩点按区。
                                Modifier
                                    .size(TopBarLineHeight)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = { actions.onBackClicked() },
                                            onLongPress = { ControlEditState.requestEdit("player_back") },
                                        )
                                    }
                                    .then(controlButtonModifier("player_back", kind = ControlElementKind.PLAYER)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_arrow_left),
                                    contentDescription = stringResource(R.string.common_back),
                                    modifier = Modifier
                                        .size(30.dp)
                                        .testTag(TOP_BAR_BACK_TAG),
                                )
                            }
                        }
                        // Close (X): stop playback and close the player
                        // page entirely — a distinct action from back.
                        // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                        if (!ControlThemeStore.isElementHidden("player_close")) {
                            Box(
                                // 3.07 百变控件：关闭钮接控件主题，长按调编辑；
                                // pointerInput 放最外层（点按区含表面内边距），不缩点按区。
                                Modifier
                                    .size(TopBarLineHeight)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = { actions.onCloseClicked() },
                                            onLongPress = { ControlEditState.requestEdit("player_close") },
                                        )
                                    }
                                    .then(controlButtonModifier("player_close", kind = ControlElementKind.PLAYER)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.player_ic_close),
                                    contentDescription = stringResource(R.string.common_close),
                                    modifier = Modifier
                                        .size(30.dp)
                                        .testTag(TOP_BAR_CLOSE_TAG),
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.padding(start = playerDim(R.dimen.vs_10)),
                        ) {
                            Text(
                                text = state.title,
                                color = overlayTextColor(),
                                fontSize = playerTextSize(R.dimen.ts_26),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sizeLine = if (state.videoQuality.isBlank()) {
                                state.videoSize
                            } else {
                                state.videoSize + " · " + state.videoQuality
                            }
                            if (sizeLine.isNotBlank()) {
                                Text(
                                    text = sizeLine,
                                    color = overlayTextColor().copy(alpha = 0.72f),
                                    fontSize = playerTextSize(R.dimen.ts_18),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (rightVisible) {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(TopBarSysInfoHeight),
                        ) {
                            if (state.batteryPercent in 0..100) {
                                Text(
                                    text = "${state.batteryPercent}%",
                                    color = overlayTextColor(),
                                    fontSize = playerTextSize(R.dimen.ts_18),
                                    lineHeight = sysLineHeight,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Image(
                                    painter = painterResource(batteryIcon(state)),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(start = 4.dp, end = playerDim(R.dimen.vs_10))
                                        .size(TopBarBatteryIconSize),
                                )
                            }
                            Text(
                                text = state.sysTime,
                                color = overlayTextColor(),
                                fontSize = playerTextSize(R.dimen.ts_18),
                                lineHeight = sysLineHeight,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                        if (showActionsInTopBar) {
                            VesperTopBarActions(actions = actions)
                        }
                    }
                }
            }
        }
    }
}

private fun batteryIcon(state: PlayerUiState): Int = when {
    state.batteryCharging -> R.drawable.ic_battery_charging
    state.batteryPercent <= 16 -> R.drawable.ic_battery_1
    state.batteryPercent <= 33 -> R.drawable.ic_battery_2
    state.batteryPercent <= 50 -> R.drawable.ic_battery_3
    state.batteryPercent <= 66 -> R.drawable.ic_battery_4
    state.batteryPercent <= 83 -> R.drawable.ic_battery_5
    else -> R.drawable.ic_battery_6
}
