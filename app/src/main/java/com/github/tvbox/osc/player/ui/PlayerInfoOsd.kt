package com.github.tvbox.osc.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState

private const val OSD_BG_ALPHA = 0.62f

@Composable
fun BoxScope.PlayerInfoOsd(state: PlayerUiState, actions: PlayerActions, maxWidth: Dp) {
    val visible = state.infoOsdVisible
    val edge = playerEdgePadding()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tapModifier = if (visible) {
        Modifier.pointerInput(Unit) { detectTapGestures(onTap = { actions.onInfoOsdClicked() }) }
    } else {
        Modifier
    }
    BoxWithConstraints(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = edge, end = edge)
            .widthIn(max = maxWidth - edge * 2),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight * 0.85f)
                .verticalScroll(rememberScrollState())
                .background(
                    Color.Black.copy(alpha = OSD_BG_ALPHA),
                    RoundedCornerShape(
                        bottomStart = playerDim(R.dimen.vs_8),
                        bottomEnd = playerDim(R.dimen.vs_8),
                    ),
                )
                .then(tapModifier)
                .padding(
                    start = playerDim(R.dimen.vs_12),
                    end = playerDim(R.dimen.vs_12),
                    top = playerDim(R.dimen.vs_10),
                    bottom = playerDim(R.dimen.vs_10),
                ),
            verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5)),
        ) {
        if (landscape) {
            Row(horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_30))) {
                Column(verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5))) {
                    state.infoOsdLeft.forEach { OsdLine(it) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5))) {
                    state.infoOsdRight.forEach { OsdLine(it) }
                }
            }
        } else {
            state.infoOsdLeft.forEach { OsdLine(it) }
            state.infoOsdRight.forEach { OsdLine(it) }
        }
        if (state.infoOsdFooter.isNotEmpty()) {
            OsdLine(state.infoOsdFooter)
        }
        }
    }
}

@Composable
private fun OsdLine(text: String) {
    val wrapUrl = text.startsWith(stringResource(R.string.osd_play_url) + " ")
    Text(
        text = text,
        color = overlayTextColor(),
        fontSize = playerTextSize(R.dimen.ts_18),
        maxLines = if (wrapUrl) Int.MAX_VALUE else 1,
        overflow = if (wrapUrl) TextOverflow.Visible else TextOverflow.Ellipsis,
    )
}
