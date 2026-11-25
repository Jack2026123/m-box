package com.github.tvbox.osc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// 与 WindowSize.LARGE_SCREEN_MIN_WIDTH_DP 同值，放 util 避免 util→ui 依赖
internal const val TABLET_MIN_WIDTH_DP = 600

internal const val VALUE_LAYOUT_HORIZONTAL = "horizontal"

internal const val VALUE_LAYOUT_VERTICAL = "vertical"

internal fun resolveHomeLayoutVertical(stored: String?, smallestWidthDp: Int): Boolean = when (stored) {
    VALUE_LAYOUT_HORIZONTAL -> false
    VALUE_LAYOUT_VERTICAL -> true
    else -> smallestWidthDp >= TABLET_MIN_WIDTH_DP
}

object HomeSettings {

    enum class HomeLayout { Horizontal, Vertical }

    // 3.14：首页界面选择整套已删（HomeInterface / home_interface / setInterface
    // 连同立体 Toast 一起拿掉）。界面就是主题：首页一律经典 Grid；立体三维主题
    // 3.17 已删。已存 home_interface 的老 KV 再也没人读，不删（旧键永不删口径）。
    // home_layout（横排/竖排）不受影响。
    private const val KEY_LAYOUT = "home_layout"

    private val defaultLayout: HomeLayout =
        if (resolveHomeLayoutVertical(null, smallestWidthDp())) HomeLayout.Vertical else HomeLayout.Horizontal

    private val mutableLayout = MutableStateFlow(current())

    val layoutFlow: StateFlow<HomeLayout> = mutableLayout

    fun current(): HomeLayout = when (KV.get(KEY_LAYOUT, "")) {
        VALUE_LAYOUT_HORIZONTAL -> HomeLayout.Horizontal
        VALUE_LAYOUT_VERTICAL -> HomeLayout.Vertical
        else -> defaultLayout
    }

    fun setLayout(layout: HomeLayout) {
        KV.put(
            KEY_LAYOUT,
            if (layout == HomeLayout.Vertical) VALUE_LAYOUT_VERTICAL else VALUE_LAYOUT_HORIZONTAL,
        )
        mutableLayout.value = layout
    }

    private fun smallestWidthDp(): Int =
        AppContextHolder.context()?.resources?.configuration?.smallestScreenWidthDp ?: 0
}
