package com.github.tvbox.osc.music

/**
 * 判断一段脚本是不是 MusicFree 插件。
 *
 * MusicFree 插件的固定特征：必须有 module.exports。
 * 这是 CommonJS 导出，洛雪脚本从来不用。
 */
object MusicFreeScriptProbe {

    fun looksMusicFree(code: String): Boolean {
        if (code.isBlank()) return false
        if (code.contains("module.exports")) return true
        if (code.contains("exports.")) return true
        if (code.contains("plugin.platform")) return true
        return false
    }

    /** 粗略提取插件里声明的 platform 字段，导入时立即显示 */
    fun guessPlatform(code: String): String {
        val matches = Regex("""platform\s*:\s*['"]([a-zA-Z0-9_]+)['"]""").findAll(code)
        for (m in matches) {
            val value = m.groupValues.getOrNull(1).orEmpty()
            if (value.isNotBlank() && !MusicPlatformNames.isFakePlatform(value)) return value
        }
        return ""
    }
}