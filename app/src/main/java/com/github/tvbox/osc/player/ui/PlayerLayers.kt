@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.player.ui

import com.github.tvbox.osc.player.PlayerHelper
import com.github.tvbox.osc.ui.theme.AppThemeState
import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState

private val PillShape = RoundedCornerShape(50)

@Composable
private fun HintPill(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .shadow(4.dp, PillShape)
            .background(Color.Black.copy(alpha = OVERLAY_PILL_ALPHA), PillShape)
            .padding(horizontal = playerDim(R.dimen.vs_20), vertical = playerDim(R.dimen.vs_5)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun HintPillLayer(content: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        HintPill(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = playerDim(R.dimen.vs_60)),
            content = content,
        )
    }
}

@Composable
fun PlayerTipLayer(state: PlayerUiState) {
    if (!state.tipVisible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.tipLoading) {
                ContainedLoadingIndicator(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    indicatorColor = Color.White,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            if (state.tipMsg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.tipMsg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = overlayTextColor(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
fun PlayerSlideHint(state: PlayerUiState) {
    PlayerHintVisibility(visible = state.slideHintVisible) {
        HintPillLayer {
            Image(
                painter = painterResource(
                    if (state.slideHintBrightness) R.drawable.player_ic_brightness
                    else R.drawable.player_ic_volume
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(playerDim(R.dimen.vs_50)),
            )
            Spacer(Modifier.width(playerDim(R.dimen.vs_20)))
            Text(
                text = state.slideHintText,
                color = overlayTextColor(),
                fontSize = playerTextSize(R.dimen.ts_30),
            )
        }
    }
}

@Composable
fun PlayerSeekHint(state: PlayerUiState) {
    PlayerHintVisibility(visible = state.seekHintVisible) {
        HintPillLayer {
            Image(
                painter = painterResource(
                    if (state.seekHintForward) R.drawable.player_ic_params_time_end
                    else R.drawable.player_ic_params_time_start
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(playerDim(R.dimen.vs_50)),
            )
            Spacer(Modifier.width(playerDim(R.dimen.vs_20)))
            Text(
                text = state.seekHintText,
                color = overlayTextColor(),
                fontSize = playerTextSize(R.dimen.ts_30),
            )
        }
    }
}

@Composable
fun PlayerLoadingLayer(state: PlayerUiState) {
    if (!state.loadingVisible) return
    Box(Modifier.fillMaxSize()) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .size(playerDim(R.dimen.vs_50)),
            color = overlayTextColor(),
        )
        if (state.kernelSwitching) {
            Text(
                text = PlayerHelper.getPlayerName(state.playerType),
                color = overlayTextColor(),
                fontSize = playerTextSize(R.dimen.ts_20),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = playerDim(R.dimen.vs_50) / 2 + playerDim(R.dimen.vs_10)),
            )
        }
    }
}

@Composable
fun PlayerNetSpeedCenter(state: PlayerUiState) {
    if (!state.netSpeedCenterVisible || state.tipVisible) return
    Box(Modifier.fillMaxSize()) {
        Text(
            text = state.netSpeedCenter,
            color = overlayTextColor(),
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = playerDim(R.dimen.vs_40)),
        )
    }
}

@Composable
fun VesperSideColumn(state: PlayerUiState, actions: PlayerActions, bottomBarHeight: Dp) {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    // 3.63：两道守卫只拦横屏。竖屏只要一个识图 🔍、不走锁那套，但详情页是
    // previewMode 且锁状态是 GONE，两道守卫把整个侧栏拦掉，3.60 的竖屏 🔍 在
    // 详情页从来没生效过（用户 2026-11-19 18:39 报“竖屏没有搜索识图”）。竖屏分支放行。
    if (state.previewMode && !isPortrait) return
    // 用户 2026-11-19 14:15 定：竖屏也放按钮（右列右中）；竖屏播放区
    // 只有屏幕 26% 高，三个钮竖排会贴到上下栏，用户已知仍要放。
    // 用户 2026-11-19 15:25/15:26 定：竖屏只要一个识图 🔍，不要旋转、
    // 不要锁、不要全屏；进全屏走底栏的全屏钮。
    // 用户 2026-11-19 15:53 定：全屏锁放左侧中间，右列只剩识图 / 旋转 / 全屏。
    // 识图图标不用 ic_action_search（它带 android:tint="?attr/colorControlNormal"，
    // 主主题没定义这个属性，Compose 里解析成透明，3.58–3.61 的 🔍 全程隐形；
    // 锁/旋转/全屏都是写死白所以一直正常），改用去 tint 的 ic_search_white；
    // ic_action_search 本身不动，音乐页搜索框还在用它。
    if (state.lockState == LockVisibility.GONE && !isPortrait) return
    val shown = state.lockState == LockVisibility.SHOWN
    val sideAlpha = playerSideAlpha(shown)
    val sideScale = playerSideScale(shown)
    // 用户 14:11 图定：上锁／旋转／全屏三钮同一列、间距均匀，不再是
    // 上锁＋旋转一对＋全屏单独贴底排。触摸框 48dp，字形 28dp 居中。
    // 图标回 1.64 那套（1.65 的 side_ic_* 重画被用户否了，太难看）。
    Box(Modifier.fillMaxSize()) {
        if (isPortrait) {
            // 竖屏只要一个识图 🔍（用户 2026-11-19 15:25/15:26 定）：放右边中间
            // （红圈位置），不要旋转、不要锁、不要全屏；进全屏走底栏的全屏钮。
            if (state.controlsVisible && !state.locked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_search_white),
                        contentDescription = "识图",
                        modifier = Modifier
                            .size(28.dp)
                            .playerPressEffect(onTap = actions::onImageSearchClicked),
                    )
                }
            }
        } else {
            // 横屏：锁放左侧中间（用户 2026-11-19 15:53 定）；右列只剩识图 / 旋转 /
            // 全屏，控制栏显示且未锁定时才出现，锁定时只剩左边的锁。
            // 用户 2026-11-19 14:57 定：全屏也要有识图 🔍，点它先退全屏，再开竖屏那种搜索面板。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .size(48.dp)
                    .playerSideEffect(alpha = sideAlpha, scale = sideScale),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(if (state.locked) R.drawable.icon_lock else R.drawable.icon_unlock),
                    contentDescription = stringResource(R.string.player_lock),
                    modifier = Modifier
                        .size(28.dp)
                        .then(
                            if (shown) Modifier.playerPressEffect(onTap = actions::onLockClicked)
                            else Modifier
                        ),
                )
            }
            if (state.controlsVisible && !state.locked) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_search_white),
                            contentDescription = "识图",
                            modifier = Modifier
                                .size(28.dp)
                                .playerPressEffect(onTap = actions::onImageSearchClicked),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_rotate_user),
                            contentDescription = "旋转",
                            modifier = Modifier
                                .size(28.dp)
                                .playerPressEffect(onTap = actions::onRotateClicked),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.player_ic_fullscreen_exit),
                            contentDescription = stringResource(R.string.player_exit_fullscreen),
                            modifier = Modifier
                                .size(28.dp)
                                .playerPressEffect(onTap = actions::onFullscreenClicked),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerSideButtons(state: PlayerUiState, actions: PlayerActions, iconBox: Dp) {
    if (state.lockState == LockVisibility.GONE) return
    val shown = state.lockState == LockVisibility.SHOWN
    val edge = playerEdgePadding()
    val iconSize = iconBox * ICON_TO_BOX_RATIO
    Box(Modifier.fillMaxSize()) {
        SideButton(
            iconRes = if (state.locked) R.drawable.icon_lock else R.drawable.icon_unlock,
            contentDescription = stringResource(R.string.player_lock),
            startSide = false,
            edge = edge,
            iconSize = iconSize,
            visible = shown,
            onClick = actions::onLockClicked,
        )
    }
}

@Composable
private fun BoxScope.SideButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    startSide: Boolean,
    edge: Dp,
    iconSize: Dp,
    visible: Boolean,
    onClick: () -> Unit,
) {
    val sideAlpha = playerSideAlpha(visible)
    val sideScale = playerSideScale(visible)
    Box(
        modifier = Modifier
            .align(if (startSide) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(start = if (startSide) edge else 0.dp, end = if (startSide) 0.dp else edge)
            .size(iconSize)
            .playerSideEffect(alpha = sideAlpha, scale = sideScale),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (visible) {
                        Modifier.playerPressEffect(onTap = onClick)
                    } else {
                        Modifier
                    }
                ),
        )
    }
}

@Composable
fun VesperCenterControls(state: PlayerUiState, actions: PlayerActions) {
    if (state.previewMode) return
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) return
    if (!state.centerControlsVisible) return
    val playing = state.playbackActive
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(48.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.player_ic_prev),
                    contentDescription = stringResource(R.string.player_prev_episode),
                    modifier = Modifier
                        .size(36.dp)
                        .playerPressEffect(onTap = actions::onPreClicked),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(
                            if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play
                        ),
                        contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
                        modifier = Modifier
                            .size(56.dp)
                            .playerPressEffect(onTap = actions::onPlayPauseClicked),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .background(Color(0xFFFFA726), RoundedCornerShape(50)),
                    )
                }
                Image(
                    painter = painterResource(R.drawable.player_ic_next),
                    contentDescription = stringResource(R.string.player_next_episode),
                    modifier = Modifier
                        .size(36.dp)
                        .playerPressEffect(onTap = actions::onNextClicked),
                )
            }
            if (!state.netSpeedCenterVisible && state.netSpeedTopRight.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.netSpeedTopRight,
                    color = overlayTextColor(),
                    fontSize = playerTextSize(R.dimen.ts_20),
                )
            }
        }
    }
}

@Composable
fun PlayerSpeedBoostHint(state: PlayerUiState) {
    PlayerHintVisibility(visible = state.speedBoostVisible && !state.tipVisible) {
        HintPillLayer {
            Text(
                text = "%.1f X".format(state.speedBoostValue),
                color = overlayTextColor(),
                fontSize = playerTextSize(R.dimen.ts_26),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun overlayTextColor(): Color {
    val argb = AppThemeState.config.textColorArgb
    return if (argb != 0) Color(argb) else Color.White
}
