package com.github.tvbox.osc.player.ui

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.player.PlayerHelper
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.ui.components.ControlEditState
import com.github.tvbox.osc.ui.components.ControlElementKind
import com.github.tvbox.osc.ui.components.controlButtonModifier
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PlayerUtils.stringForTime

private const val SEEK_MAX = 1000

private const val SEEK_STEP_MS = 10_000L

private val PreviewPlayPauseBox = 40.dp

private val SeekTrackHeight = 6.dp

private val SeekThumbRadius = 8.dp

private val SeekThumbRadiusActive = 11.dp

// 诊断用：上一次实际绘制的进度比例，仅用于避免重复打印
private var lastDrawnProgress = Float.NaN

@Composable
fun PlayerBottomBar(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val edge = playerEdgePadding()
    val configuration = LocalConfiguration.current
    val portraitActions = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val bottomPad = if (state.previewMode) {
        (16.dp + playerDim(R.dimen.vs_30) / 2 - PreviewPlayPauseBox / 2).coerceAtLeast(0.dp)
    } else {
        6.dp
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Black.copy(alpha = 0.7f),
                )
            )
            .padding(start = edge, end = edge, top = 10.dp, bottom = bottomPad)
    ) {
        if (!state.previewMode && portraitActions) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                PlayerTimePill(state = state, modifier = Modifier.align(Alignment.CenterStart))
                if (!state.netSpeedCenterVisible && state.netSpeedTopRightVisible && state.netSpeedTopRight.isNotBlank()) {
                    PlayerInfoPill(modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text(
                            text = state.netSpeedTopRight,
                            color = overlayTextColor(),
                            fontSize = playerTextSize(R.dimen.ts_20),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.previewMode) {
                PreviewPlayPauseButton(state, actions)
            }

            if (state.previewMode) {
                CurrentTimeText(state)
            }
            if (!state.previewMode && !portraitActions) {
                Text(
                    text = stringForTime(state.seekPreviewOrPosition),
                    color = overlayTextColor(),
                    fontSize = playerTextSize(R.dimen.ts_20),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
            }
            PlayerSeekRow(
                state = state,
                actions = actions,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = if (state.previewMode) 8.dp else 8.dp),
            )
            if (state.previewMode) {
                Text(
                    text = stringForTime(state.duration),
                    color = overlayTextColor(),
                    fontSize = playerTextSize(R.dimen.ts_20),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
                TransportButton(
                    iconRes = R.drawable.player_ic_fullscreen,
                    contentDescription = stringResource(R.string.player_fullscreen),
                    box = PreviewPlayPauseBox,
                    iconSize = 26.dp,
                    onClick = actions::onFullscreenClicked,
                    controlId = "player_fullscreen",
                )
            }
            if (!state.previewMode && !portraitActions) {
                Text(
                    text = stringForTime(state.duration),
                    color = overlayTextColor(),
                    fontSize = playerTextSize(R.dimen.ts_20),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
            }
        }

        if (!state.previewMode) {
            PlayerActionRow(
                state = state,
                actions = actions,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (state.showParseRow && !state.previewMode) {
            val parseList = remember(state.parseListVersion) { ApiConfig.get().parseBeanList.toList() }
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_menu_parse),
                    color = overlayTextColor(),
                    fontSize = playerTextSize(R.dimen.ts_20),
                    maxLines = 1,
                    modifier = Modifier.padding(end = playerDim(R.dimen.vs_10)),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5))) {
                    items(parseList.size) { index ->
                        val item = parseList[index]
                        PlayerMenuButton(
                            item.name.orEmpty(),
                            onClick = { actions.onParseSelected(index) },
                            textColor = if (item.isDefault) Color(0xFF02F8E1) else overlayTextColor(),
                            textSizeId = R.dimen.ts_20,
                            // 3.07 百变控件：解析行按位置编号（解析源列表顺序即显示顺序）。
                            controlId = "player_parse_" + index,
                        )
                    }
                }
            }
        }
    }
}

internal const val OVERLAY_PILL_ALPHA = 0.2f

@Composable
private fun PlayerActionRow(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val actionBox = playerDim(R.dimen.vs_70)
    val actionIcon = playerDim(R.dimen.vs_50)
    val portraitPlayer = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    if (portraitPlayer) {
        Row(
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerTransportRow(state = state, actions = actions, modifier = Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_12)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    iconRes = R.drawable.player_ic_menu_episodes,
                    contentDescription = stringResource(R.string.detail_episodes),
                    box = actionBox,
                    iconSize = actionIcon,
                    onClick = actions::onEpisodeClicked,
                    controlId = "player_episodes",
                )
                TransportButton(
                    iconRes = R.drawable.player_ic_params,
                    contentDescription = stringResource(R.string.player_menu_more),
                    box = actionBox,
                    iconSize = actionIcon,
                    onClick = actions::onParamsClicked,
                    controlId = "player_params",
                )
                TransportButton(
                    iconRes = R.drawable.player_ic_fullscreen,
                    contentDescription = stringResource(R.string.player_fullscreen),
                    box = actionBox,
                    iconSize = actionIcon,
                    onClick = actions::onFullscreenClicked,
                    controlId = "player_fullscreen",
                )
            }
        }
    } else {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            val gap = playerDim(R.dimen.vs_8)
            val leftGroup: @Composable RowScope.() -> Unit = {
                PlayerMenuButton(
                    text = stringResource(R.string.detail_episodes),
                    onClick = actions::onEpisodeClicked,
                    textSizeId = R.dimen.ts_22,
                    controlId = "player_episodes",
                )
                PlayerMenuButton(
                    text = PlayerHelper.getPlayerName(state.playerType),
                    onClick = actions::onPlayerClicked,
                    onLongClick = actions::onPlayerLongClicked,
                    textSizeId = R.dimen.ts_22,
                    // 3.07：内核钮长按已有功能（长按切内核），不劫持，只套外观。
                    controlId = "player_kernel",
                )
                PlayerMenuButton(
                    text = if (state.decodeSoft) "软解" else "硬解",
                    onClick = actions::onDecodeToggleClicked,
                    textSizeId = R.dimen.ts_22,
                    controlId = "player_decode",
                )
                PlayerMenuButton(
                    text = "刷新",
                    onClick = actions::onRefreshClicked,
                    textSizeId = R.dimen.ts_22,
                    controlId = "player_refresh",
                )
                PlayerMenuButton(
                    text = "重播",
                    onClick = actions::onReplayClicked,
                    textSizeId = R.dimen.ts_22,
                    controlId = "player_replay",
                )
                PlayerMenuButton(
                    text = "循环",
                    onClick = actions::onLoopToggleClicked,
                    textSizeId = R.dimen.ts_22,
                    controlId = "player_loop",
                )
                PlayerMenuButton(
                    text = "倍速",
                    onClick = actions::onSpeedClicked,
                    onLongClick = actions::onSpeedLongClicked,
                    textSizeId = R.dimen.ts_22,
                    // 3.07：倍速钮长按已有功能（长按调倍速），不劫持，只套外观。
                    controlId = "player_speed",
                )
                PlayerMenuButton(
                    text = PlayerHelper.getScaleName(state.scaleType),
                    onClick = actions::onOriginalClicked,
                    textSizeId = R.dimen.ts_22,
                    controlId = "player_scale",
                )
            }
            val rightGroup: @Composable RowScope.() -> Unit = {
                PlayerMenuButton(
                    text = "字幕",
                    onClick = actions::onSubtitleClicked,
                    onLongClick = actions::onSubtitleLongClicked,
                    textSizeId = R.dimen.ts_22,
                    // 3.07：字幕钮长按已有功能，不劫持，只套外观。
                    controlId = "player_subtitle",
                )
                PlayerMenuButton(
                    text = "音轨",
                    onClick = actions::onAudioTrackClicked,
                    textSizeId = R.dimen.ts_22,
                    controlId = "player_audio_track",
                )
                PlayerMenuButton(
                    text = "视轨",
                    onClick = actions::onVideoTrackClicked,
                    textSizeId = R.dimen.ts_22,
                    controlId = "player_video_track",
                )
                PlayerMenuButton(
                    text = if (state.timeStartText.isNotEmpty()) state.timeStartText else stringResource(R.string.player_time_start),
                    onClick = actions::onTimeStartClicked,
                    onLongClick = actions::onTimeStartLongClicked,
                    textColor = if (state.timeStartText.isNotEmpty()) Color(0xFF02F8E1) else overlayTextColor(),
                    textSizeId = R.dimen.ts_22,
                    fontWeight = FontWeight.Bold,
                    // 3.07：片头钮长按已有功能（长按清除），不劫持，只套外观。
                    controlId = "player_time_start",
                )
                PlayerMenuButton(
                    text = if (state.timeEndText.isNotEmpty()) state.timeEndText else stringResource(R.string.player_time_end),
                    onClick = actions::onTimeEndClicked,
                    onLongClick = actions::onTimeEndLongClicked,
                    textColor = if (state.timeEndText.isNotEmpty()) Color(0xFF02F8E1) else overlayTextColor(),
                    textSizeId = R.dimen.ts_22,
                    fontWeight = FontWeight.Bold,
                    // 3.07：片尾钮长按已有功能（长按清除），不劫持，只套外观。
                    controlId = "player_time_end",
                )
            }
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically,
                        content = leftGroup,
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically,
                        content = rightGroup,
                    )
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically,
                        content = leftGroup,
                    )
                    Spacer(Modifier.padding(horizontal = 16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically,
                        content = rightGroup,
                    )
                }
            }
        }
    }
}

private const val TRANSPORT_COUNT = 5

internal const val TRANSPORT_PLAY_PAUSE_TAG = "playerTransportPlayPause"

private const val TRANSPORT_SIDE_RATIO = 5f / 7f

@Composable
private fun PlayerTransportRow(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val gap = playerDim(R.dimen.vs_12)
    val preferred = playerDim(R.dimen.vs_70)
    BoxWithConstraints(modifier) {
        val box = minOf(preferred, (maxWidth - gap * (TRANSPORT_COUNT - 1)) / TRANSPORT_COUNT)
            .coerceAtLeast(1.dp)
        val sideIcon = box * TRANSPORT_SIDE_RATIO
        val playing = state.playbackActive
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                iconRes = R.drawable.player_ic_replay_10,
                contentDescription = stringResource(R.string.player_seek_back),
                box = box,
                iconSize = sideIcon,
                onClick = { actions.onSeekRelative(-SEEK_STEP_MS) },
                controlId = "player_seek_back",
            )
            TransportButton(
                iconRes = if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play,
                contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
                box = box,
                iconSize = box,
                testTag = TRANSPORT_PLAY_PAUSE_TAG,
                onClick = actions::onPlayPauseClicked,
                controlId = "player_play_pause",
            )
            TransportButton(
                iconRes = R.drawable.player_ic_forward_10,
                contentDescription = stringResource(R.string.player_seek_forward),
                box = box,
                iconSize = sideIcon,
                onClick = { actions.onSeekRelative(SEEK_STEP_MS) },
                controlId = "player_seek_forward",
            )
            TransportButton(
                iconRes = R.drawable.player_ic_prev,
                contentDescription = stringResource(R.string.player_prev_episode),
                box = box,
                iconSize = sideIcon,
                onClick = actions::onPreClicked,
                controlId = "player_prev",
            )
            TransportButton(
                iconRes = R.drawable.player_ic_next,
                contentDescription = stringResource(R.string.player_next_episode),
                box = box,
                iconSize = sideIcon,
                onClick = actions::onNextClicked,
                controlId = "player_next",
            )
        }
    }
}

@Composable
private fun TransportButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    box: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    testTag: String? = null,
    // 3.07 百变控件：稳定元素 id（调用处写死，如 "player_play_pause"）；为 null 时不接线。
    controlId: String? = null,
) {
    // 3.07 第二阶段 C-edit：隐藏后不渲染（3.18 起设置页“已隐藏的元素”恢复卡已删，藏了只能清应用数据回来）。
    if (controlId != null && ControlThemeStore.isElementHidden(controlId)) return
    Box(
        // 3.07：只套轻量外观（形状裁剪 + slate 淡底 + 强调色描边）；点按仍走
        // Image 上的 playerPressEffect（内层图标区，不缩点按区）。
        Modifier.size(box)
            .then(if (controlId != null) controlButtonModifier(controlId, kind = ControlElementKind.PLAYER) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(iconSize)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                // 3.07：playerPressEffect 自带 onLongClick 槽，长按调编辑，不吞点按。
                .playerPressEffect(
                    onTap = onClick,
                    onLongClick = controlId?.let { id -> { ControlEditState.requestEdit(id) } },
                ),
        )
    }
}

@Composable
private fun PlayerInfoPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = OVERLAY_PILL_ALPHA), RoundedCornerShape(50))
            .padding(
                horizontal = playerDim(R.dimen.vs_10),
                vertical = playerDim(R.dimen.vs_5),
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PlayerTimePill(state: PlayerUiState, modifier: Modifier = Modifier) {
    PlayerInfoPill(modifier) { TimeRangeText(state) }
}

@Composable
private fun TimeRangeText(state: PlayerUiState) {
    Text(
        text = stringForTime(state.seekPreviewOrPosition) + " / " + stringForTime(state.duration),
        color = overlayTextColor(),
        fontSize = playerTextSize(R.dimen.ts_20),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

@Composable
private fun CurrentTimeText(state: PlayerUiState, modifier: Modifier = Modifier) {
    Text(
        text = stringForTime(state.seekPreviewOrPosition),
        color = overlayTextColor(),
        fontSize = playerTextSize(R.dimen.ts_20),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = modifier.widthIn(min = 48.dp),
    )
}

@Composable
private fun PreviewPlayPauseButton(state: PlayerUiState, actions: PlayerActions) {
    val playing = state.playbackActive
    // 3.07 第二阶段 C-edit：隐藏后不渲染（与竖屏 transport 共用 "player_play_pause"，同进同出）。
    if (ControlThemeStore.isElementHidden("player_play_pause")) return
    Box(
        // 3.07 百变控件：预览态播放/暂停钮接控件主题（与竖屏 transport 共用 "player_play_pause"）。
        modifier = Modifier.size(PreviewPlayPauseBox)
            .then(controlButtonModifier("player_play_pause", kind = ControlElementKind.PLAYER)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(
                if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play
            ),
            contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.9f)),
            modifier = Modifier
                .size(22.dp)
                // 3.07：playerPressEffect 自带 onLongClick 槽，长按调编辑，不吞点按。
                .playerPressEffect(
                    onTap = actions::onPlayPauseClicked,
                    onLongClick = { ControlEditState.requestEdit("player_play_pause") },
                ),
        )
    }
}

@Composable
private fun PlayerSeekRow(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier,
) {
    var draggingLocal by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }

    val thumbActive = draggingLocal || state.dragging
    val density = LocalDensity.current
    val thumbRadiusDp = animateFloatAsState(
        targetValue = if (thumbActive) SeekThumbRadiusActive.value else SeekThumbRadius.value,
        animationSpec = tween(
            durationMillis = if (thumbActive) PLAYER_IN_MS else PLAYER_OUT_MS,
            easing = if (thumbActive) PLAYER_ENTER_EASING else PLAYER_EXIT_EASING,
        ),
        label = "seekThumbRadius",
    ).value
    val thumbRadiusPx = with(density) { thumbRadiusDp.dp.toPx() }

    var seekModifier = modifier
        .height(playerDim(R.dimen.vs_30))
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll && state.duration > 0) {
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                        val dir = when {
                            delta.y != 0f -> if (delta.y > 0) 1 else -1
                            delta.x != 0f -> if (delta.x > 0) 1 else -1
                            else -> continue
                        }
                        actions.onSeekStep(dir)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures { offset ->
                if (state.duration <= 0) return@detectTapGestures
                val target = (offset.x / size.width * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
                actions.onSeekStarted()
                actions.onSeekPreview(target)
                actions.onSeekFinished(target)
            }
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    if (state.duration > 0) {
                        actions.onSeekStarted()
                        draggingLocal = true
                        dragProgress = (offset.x / size.width * SEEK_MAX).coerceIn(0f, SEEK_MAX.toFloat())
                        actions.onSeekPreview(dragProgress.toInt())
                    }
                },
                onDragEnd = {
                    if (draggingLocal) {
                        actions.onSeekFinished(dragProgress.toInt())
                    }
                    draggingLocal = false
                },
                onDragCancel = {
                    if (draggingLocal) {
                        actions.onSeekCancelled()
                    }
                    draggingLocal = false
                },
            ) { change, dragAmount ->
                if (draggingLocal) {
                    dragProgress = (dragProgress + dragAmount / size.width * SEEK_MAX)
                        .coerceIn(0f, SEEK_MAX.toFloat())
                    actions.onSeekPreview(dragProgress.toInt())
                    change.consume()
                }
            }
        }

    Canvas(seekModifier) {
        val progress: Float = when {
            state.dragging && state.duration > 0 ->
                state.seekPreviewPositionMs.toFloat() / state.duration * SEEK_MAX
            state.duration > 0 -> state.position.toFloat() / state.duration * SEEK_MAX
            else -> 0f
        }
        if (!state.dragging && lastDrawnProgress != progress) {
            lastDrawnProgress = progress
            LOG.i(
                "echo-bar-draw: progress=$progress uiPosition=${state.position}"
                    + " uiDuration=${state.duration} dragging=${state.dragging}",
            )
        }
        val buffered: Float =
            if (state.duration > 0) state.bufferedPercent / 100f * SEEK_MAX else 0f
        val trackHeight = SeekTrackHeight.toPx()
        val centerY = size.height / 2
        val corner = CornerRadius(trackHeight / 2)
        drawRoundRect(
            color = Color(0x4DFFFFFF),
            topLeft = Offset(0f, centerY - trackHeight / 2),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            cornerRadius = corner,
        )
        if (buffered > 0f) {
            drawRoundRect(
                color = Color(0x66FFFFFF),
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = androidx.compose.ui.geometry.Size(size.width * (buffered / SEEK_MAX), trackHeight),
                cornerRadius = corner,
            )
        }
        if (progress > 0f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = androidx.compose.ui.geometry.Size(size.width * (progress / SEEK_MAX), trackHeight),
                cornerRadius = corner,
            )
        }
        val thumbRadius = thumbRadiusPx
        val thumbCenter = Offset(size.width * (progress / SEEK_MAX), centerY)
        drawCircle(Color.White, radius = thumbRadius, center = thumbCenter)
        drawCircle(
            Color.White.copy(alpha = 0.55f),
            radius = thumbRadius,
            center = thumbCenter,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
