package com.github.tvbox.osc.ui.components

import com.github.tvbox.osc.ui.theme.ControlThemeStore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3.07 百变控件 · 长按编辑请求总线。
 * 接线方（各页元素）长按时调 requestEdit(elementId)；
 * 编辑弹窗方观察 editRequest，弹出对应元素的编辑器；dismiss() 关闭。
 * 纯内存状态，不进 KV；重启后无待处理编辑请求。
 */
object ControlEditState {
    private val _editRequest = MutableStateFlow<String?>(null)
    val editRequest: StateFlow<String?> = _editRequest.asStateFlow()

    fun requestEdit(elementId: String) {
        // 3.09 更正：总开关关时不弹编辑器（长按无事发生），避免关了还有半截效果。
        if (!ControlThemeStore.isControlThemeEnabled()) return
        _editRequest.value = elementId
    }

    fun dismiss() {
        _editRequest.value = null
    }
}
