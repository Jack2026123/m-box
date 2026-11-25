package com.github.tvbox.osc.ui.activity

internal enum class DetailPlaybackEntry {
    PlayCapsule,
    Episode,
    Quality,
    Line,
    Cast,
    ExitFullscreen,
    SwitchRollback,
    MusicReturn,
}

internal data class DetailPlaybackPlan(
    val startPlayback: Boolean,
    val enterFullScreen: Boolean,
    val stopPlayback: Boolean,
)

internal object DetailPlaybackPolicy {

    fun plan(entry: DetailPlaybackEntry, resumable: Boolean = false): DetailPlaybackPlan = when (entry) {
        DetailPlaybackEntry.PlayCapsule,
        DetailPlaybackEntry.Episode,
        DetailPlaybackEntry.Quality -> DetailPlaybackPlan(startPlayback = true, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.SwitchRollback,
        DetailPlaybackEntry.MusicReturn -> DetailPlaybackPlan(startPlayback = resumable, enterFullScreen = false, stopPlayback = false)

        // 对标昏黄 selectFlag→seamless→selectEpisode：换线路也要开播
        DetailPlaybackEntry.Line -> DetailPlaybackPlan(startPlayback = true, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.Cast -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.ExitFullscreen -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = true)
    }
}
