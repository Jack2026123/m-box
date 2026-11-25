package com.github.tvbox.osc.player.ui

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.osc.player.controller.VideoPlayerController
import com.github.tvbox.osc.ui.theme.AVBoxTheme

/**
 * 播放器 UI 的根节点。
 *
 * 三个必须保留的原生 View（弹幕式字幕 / media3 字幕 / 歌词）通过 [AndroidView] 桥接，
 * 层级顺序与改造前 ComposeVideoController 的 addView 顺序保持一致：
 * 字幕 → media3 字幕 → 歌词 → Compose 控件层，后者盖在前者之上。
 */
@Composable
internal fun PlayerSurfaceHost(controller: VideoPlayerController) {
    AVBoxTheme(manageStatusBarIcons = false) {
        // Remote (dpad) control for the TV form: the control-bar
        // buttons are custom gesture components and cannot take
        // focus, so the host root takes focus in landscape and maps
        // keys onto the SAME actions the gestures use — seek steps
        // ride the existing key-seek path (which also wakes the
        // control bar), OK shows the bar / toggles play, Up/Down are
        // the system volume, mirroring the right-side vertical drag.
        // Portrait (touch) never consumes a key here, and Back is
        // left to the activity's back handling untouched.
        val state = controller.state
        val actions = controller.actions
        val landscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val focusRequester = remember { FocusRequester() }
        val context = LocalContext.current
        val audioManager = remember {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (!landscape || event.type != KeyEventType.KeyDown) {
                        return@onKeyEvent false
                    }
                    // Player sheets/dialogs take the keys while open.
                    if (state.selectDialog != null || state.paramsSheet != null ||
                        state.danmuSettingSheet != null || state.danmuSearchSheet != null ||
                        state.subtitleSheet != null || state.subtitleSearchSheet != null ||
                        state.castSheet != null || state.episodeSheetOpen
                    ) {
                        return@onKeyEvent false
                    }
                    when (event.key) {
                        Key.DirectionRight -> {
                            actions.onSeekStep(1)
                            true
                        }
                        Key.DirectionLeft -> {
                            actions.onSeekStep(-1)
                            true
                        }
                        Key.DirectionUp -> {
                            audioManager?.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_RAISE,
                                AudioManager.FLAG_SHOW_UI,
                            )
                            if (!state.controlsVisible) actions.toggleControls()
                            true
                        }
                        Key.DirectionDown -> {
                            audioManager?.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_LOWER,
                                AudioManager.FLAG_SHOW_UI,
                            )
                            if (!state.controlsVisible) actions.toggleControls()
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (state.controlsVisible) {
                                actions.onPlayPauseClicked()
                            } else {
                                actions.toggleControls()
                            }
                            true
                        }
                        Key.MediaPlayPause -> {
                            actions.onPlayPauseClicked()
                            true
                        }
                        else -> false
                    }
                }
                .onSizeChanged { controller.onHostSizeChanged(it.width, it.height) },
        ) {
            AndroidView(
                factory = { adopt(controller.subtitleView) },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            )
            AndroidView(
                factory = { adopt(controller.exoSubtitleView) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 24.dp),
            )
            AndroidView(
                factory = { adopt(controller.lyricView) },
                modifier = Modifier.fillMaxSize(),
            )
            PlayerOverlay(
                state = controller.state,
                actions = controller.actions,
                gestureHandler = controller.gestureHandler,
                gestureSession = { w, h, sw, y ->
                    controller.gestureActions.beginSession(w, h, sw, y)
                },
                onTapPending = { controller.onGestureTapPending() },
            )
        }
    }
}

/**
 * AndroidView 会复用同一批 View 实例，重新挂载前先脱离旧 parent，
 * 否则抛 "The specified child already has a parent"。
 */
private fun <T : View> adopt(view: T): T {
    (view.parent as? ViewGroup)?.removeView(view)
    return view
}
