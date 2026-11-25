package com.github.tvbox.osc.player.controller

import com.github.tvbox.osc.bean.ParseBean

import java.util.HashMap

interface VodControlListener {
    fun playNext(rmProgress: Boolean)

    fun playPre()

    fun prepared()

    fun changeParse(pb: ParseBean)

    fun updatePlayerCfg()

    fun replay(replay: Boolean)

    fun errReplay()

    fun selectSubtitle()

    fun closeSubtitles()

    fun toggleSubtitles(): Boolean

    fun selectAudioTrack()

    fun selectVideoTrack()

    fun showDanmuSetting()

    fun showEpisodes()

    fun toggleDanmu(): Boolean

    fun searchDanmuUi(longClick: Boolean)

    fun startPlayUrl(url: String, headers: HashMap<String, String>?)

    fun onM3u8ProxyUrl(proxyUrl: String, sourceUrl: String)

    fun clickCast()

    fun clickPip()

    /** Top-bar X: stop playback and close the player page entirely (host-registered). */
    fun clickClose()

    /** Fullscreen toggle (bottom-bar button / double-tap), host-registered. */
    fun clickFullscreen()

    fun setAllowSwitchPlayer(isAllow: Boolean)

    fun setAllowDecodeFallback(isAllow: Boolean)
}
