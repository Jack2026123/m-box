package com.github.tvbox.osc.ui.page

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SubscribeVisibility {
    private fun keyOf(mode: ConfigMode): String = when (mode) {
        ConfigMode.Vod -> HawkConfig.SUB_HIDE_VOD
        ConfigMode.Live -> HawkConfig.SUB_HIDE_LIVE
        ConfigMode.Reading -> HawkConfig.SUB_HIDE_READING
        ConfigMode.MiniApp -> HawkConfig.SUB_HIDE_MINIAPP
        ConfigMode.Music -> HawkConfig.SUB_HIDE_MUSIC
    }

    private fun defaultHidden(mode: ConfigMode): Boolean =
        mode == ConfigMode.MiniApp

    private fun loadHidden(): Set<ConfigMode> =
        ConfigMode.entries.filter { KV.get(keyOf(it), defaultHidden(it)) }.toSet()

    private val hiddenFlow = MutableStateFlow(loadHidden())

    val hiddenModes: StateFlow<Set<ConfigMode>> = hiddenFlow.asStateFlow()

    fun isHidden(mode: ConfigMode): Boolean = mode in hiddenFlow.value

    fun setHidden(mode: ConfigMode, hidden: Boolean) {
        KV.put(keyOf(mode), hidden)
        hiddenFlow.value = if (hidden) hiddenFlow.value + mode else hiddenFlow.value - mode
    }

    fun visibleModes(): List<ConfigMode> =
        ConfigMode.entries.filterNot { it in hiddenFlow.value }
}
