package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.player.MyVideoView

internal class LivePageActions(
    val onRetryLoad: () -> Unit,
    val onPasswordConfirm: (String) -> Unit,
    val onPasswordDismiss: () -> Unit,
)

internal class LivePlayerActions(
    val videoView: () -> MyVideoView?,
    val onExitFullscreen: () -> Unit,
    val onEpgSheetOpen: () -> Unit,
    val onSettingsSheetOpen: () -> Unit,
    val onTimeshiftSeek: (Float) -> Unit,
    val onTimeshiftTogglePlay: () -> Unit,
    val onPipClicked: () -> Unit,
    /** Top-left back: hierarchical back, same routing as the system back key. */
    val onBackClicked: () -> Unit,
    /** Top-left X: stop playback and close the live player page entirely. */
    val onCloseClicked: () -> Unit,
)

internal class LiveEpgActions(
    val onDismiss: () -> Unit,
    val onRowClicked: (Int) -> Boolean,
)

internal class LiveChannelListActions(
    val onToggleGroup: (Int) -> Unit,
    val onSelectChannel: (Int, Int) -> Unit,
)

internal class LiveSettingsActions(
    val onDismiss: () -> Unit,
    val onItemClick: (Int, Int) -> Unit,
    val onItemLongClick: (Int) -> Unit,
)

internal class LivePlayActions(
    val page: LivePageActions,
    val player: LivePlayerActions,
    val epg: LiveEpgActions,
    val list: LiveChannelListActions,
    val settings: LiveSettingsActions,
)
