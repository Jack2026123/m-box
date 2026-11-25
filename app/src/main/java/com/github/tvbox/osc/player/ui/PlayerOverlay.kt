package com.github.tvbox.osc.player.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.ui.components.ControlEditState
import com.github.tvbox.osc.ui.components.ControlElementKind
import com.github.tvbox.osc.ui.components.controlButtonModifier
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun PlayerOverlay(
    state: PlayerUiState,
    actions: PlayerActions,
    gestureHandler: VideoGestureHandler,
    gestureSession: (width: Int, height: Int, screenWidth: Int, downY: Float) -> VideoGestureSession,
    onTapPending: () -> Unit,
) {
    val screenWidthPx = LocalContext.current.resources.displayMetrics.widthPixels
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .videoGestureLayer(
                handler = gestureHandler,
                sessionProvider = { size, downY ->
                    val sizeW = size.width
                    val sizeH = size.height
                    if (sizeW <= 0 || sizeH <= 0) {
                        null
                    } else {
                        gestureSession(sizeW, sizeH, screenWidthPx, downY)
                    }
                },
                onTapPending = onTapPending,
            ),
    ) {
        var bottomBarHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        PlayerTipLayer(state)
        PlayerSlideVisibility(
            visible = state.topLeftVisible || (state.topRightVisible && !state.previewMode) || (state.kernelSwitching && !state.previewMode),
            fromTop = true,
        ) {
            PlayerTopBar(state, actions)
        }
        PlayerSlideVisibility(
            visible = state.controlsVisible,
            fromTop = false,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onGloballyPositioned { bottomBarHeight = with(density) { it.size.height.toDp() } },
        ) {
            PlayerBottomBar(state, actions)
        }
        PlayerSlideHint(state)
        PlayerSeekHint(state)
        PlayerFadeVisibility(
            visible = state.loadingVisible && !state.tipVisible,
            enterMs = PLAYER_OSD_IN_MS,
            exitMs = PLAYER_OSD_OUT_MS,
        ) {
            PlayerLoadingLayer(state)
        }
        PlayerNetSpeedCenter(state)
        VesperCenterControls(state, actions)
        VesperSideColumn(state, actions, bottomBarHeight)
        PlayerFadeVisibility(
            visible = state.infoOsdVisible,
            enterMs = PLAYER_OSD_IN_MS,
            exitMs = PLAYER_OSD_OUT_MS,
        ) {
            PlayerInfoOsd(state, actions, maxWidth)
        }
        PlayerSpeedBoostHint(state)

        state.selectDialog?.let { dialogState ->
            PlayerSelectDialog(
                dialogState = dialogState,
                onDismiss = { state.selectDialog = null },
            )
        }

        state.paramsSheet?.let { sheet ->
            PlayerParamsSheet(
                sheet = sheet,
                tab = state.paramsTab,
                onTabSelected = { state.paramsTab = it },
                osdVisible = state.infoOsdVisible,
                onToggleOsd = actions::onInfoOsdClicked,
                slideFromEnd = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
                onDismiss = { state.paramsSheet = null },
                onPipClicked = actions::onPipClicked,
                onDanmuSettingClicked = actions::onDanmuSettingClicked,
            )
        }

        state.danmuSettingSheet?.let { sheet ->
            DanmuSettingSheet(sheet) { state.danmuSettingSheet = null }
        }
        state.danmuSearchSheet?.let { sheet ->
            DanmuSearchSheet(sheet) { state.danmuSearchSheet = null }
        }
        state.imageSearchSheet?.let { sheet ->
            ImageSearchSheet(sheet) { state.imageSearchSheet = null }
        }
        state.subtitleSheet?.let { sheet ->
            SubtitleSheet(sheet) { state.subtitleSheet = null }
        }
        state.subtitleSearchSheet?.let { sheet ->
            SubtitleSearchSheet(sheet) { state.subtitleSearchSheet = null }
        }
        state.castSheet?.let { sheet ->
            CastSheet(sheet) { state.castSheet = null }
        }
    }

    LaunchedEffect(state.seekHintVisible, state.seekHintText) {
        if (state.seekHintVisible) {
            delay(1000)
            actions.hideSeekHint()
        }
    }
    LaunchedEffect(state.slideHintVisible, state.slideHintText) {
        if (state.slideHintVisible) {
            delay(1000)
            actions.hideSlideHint()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            actions.refreshSystemInfo()
            delay(1000)
        }
    }
}

private const val PLAYER_DESIGN_WIDTH = 1280f

@Composable
private fun playerMmScale(): Float {
    val container = LocalWindowInfo.current.containerSize
    val longEdgePx = if (container.width > 0 && container.height > 0) {
        maxOf(container.width, container.height).toFloat()
    } else {
        val conf = LocalConfiguration.current
        maxOf(conf.screenWidthDp, conf.screenHeightDp) * LocalDensity.current.density
    }
    return if (longEdgePx > 0f) longEdgePx / PLAYER_DESIGN_WIDTH else 1f
}

private fun rawMm(resources: Resources, @DimenRes id: Int): Float? {
    val tv = TypedValue()
    try {
        resources.getValue(id, tv, true)
    } catch (e: Resources.NotFoundException) {
        return null
    }
    if (tv.type != TypedValue.TYPE_DIMENSION) return null
    if ((tv.data and TypedValue.COMPLEX_UNIT_MASK) != TypedValue.COMPLEX_UNIT_MM) return null
    return TypedValue.complexToFloat(tv.data)
}

@Composable
internal fun playerDim(@DimenRes id: Int): Dp {
    val res = LocalContext.current.resources
    val raw = rawMm(res, id)
    val px = if (raw != null) raw * playerMmScale() else res.getDimension(id)
    val pxInt = if (px == 0f) 0 else px.roundToInt().coerceAtLeast(1)
    return with(LocalDensity.current) { pxInt.toFloat().toDp() }
}

@Composable
internal fun playerTextSize(@DimenRes id: Int): TextUnit {
    val res = LocalContext.current.resources
    val raw = rawMm(res, id)
    val px = if (raw != null) raw * playerMmScale() else res.getDimension(id)
    val safePx = if (px.isFinite()) px.coerceAtLeast(0f) else 0f
    return with(LocalDensity.current) { safePx.toSp() }
}

@Composable
internal fun playerEdgePadding(): Dp =
    if (LocalConfiguration.current.screenWidthDp >= 600) 48.dp else 16.dp

@Composable
internal fun PlayerMenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    textColor: Color = overlayTextColor(),
    @DimenRes textSizeId: Int = R.dimen.ts_20,
    fontWeight: FontWeight = FontWeight.Medium,
    boxHeight: Dp? = null,
    // 3.07 百变控件：稳定元素 id（调用处写死，如 "player_speed"）；
    // 有功能长按（onLongClick 非空）时长按仍走原功能，不劫持；
    // 无功能长按时，长按调编辑。
    controlId: String? = null,
) {
    // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
    if (controlId != null && ControlThemeStore.isElementHidden(controlId)) return
    var pressed by remember { mutableStateOf(false) }
    var buttonModifier = modifier
    if (boxHeight != null) buttonModifier = buttonModifier.height(boxHeight)
    // 3.07：无功能长按时由控件编辑接管（有功能长按的钮不劫持）。
    val editLongPress: (() -> Unit)? = onLongClick ?: controlId?.let { id ->
        { ControlEditState.requestEdit(id) }
    }
    buttonModifier = buttonModifier
        .pointerInput(onClick, onLongClick, controlId) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { onClick() },
                onLongPress = editLongPress?.let { cb -> { cb() } },
            )
        }
        .background(
            if (pressed) Color.White.copy(alpha = 0.16f) else Color.Transparent,
            RoundedCornerShape(playerDim(R.dimen.vs_5)),
        )
        .padding(horizontal = playerDim(R.dimen.vs_10), vertical = playerDim(R.dimen.vs_5))
        // 3.07 百变控件：只套轻量外观（形状裁剪 + slate 淡底 + 强调色描边），
        // 放在点按处理内侧，不缩点按区；字号/原有按压高亮不动。
        .then(if (controlId != null) controlButtonModifier(controlId, kind = ControlElementKind.PLAYER) else Modifier)
    if (boxHeight != null) buttonModifier = buttonModifier.wrapContentHeight(Alignment.CenterVertically)
    Text(
        text = text,
        color = textColor,
        fontSize = playerTextSize(textSizeId),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (pressed) FontWeight.Bold else fontWeight,
        modifier = buttonModifier,
    )
}

internal const val ICON_TO_BOX_RATIO = 0.55f

private const val PILL_MAX_ICONS = 8

@Composable
internal fun playerIconBox(availableWidth: Dp): Dp {
    val gap = playerDim(R.dimen.vs_8)
    return minOf(
        playerDim(R.dimen.vs_70),
        (availableWidth - gap * (PILL_MAX_ICONS + 1)) / PILL_MAX_ICONS,
    ).coerceAtLeast(1.dp)
}
