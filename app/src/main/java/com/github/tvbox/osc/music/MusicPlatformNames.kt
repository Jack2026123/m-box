package com.github.tvbox.osc.music

object MusicPlatformNames {

    fun properName(platform: String?): String? {
        val value = platform?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when (value) {
            "kugou", "kg" -> "酷狗音乐"
            "netease", "wy", "网易音乐", "网易云音乐" -> "网易云音乐"
            "qq", "tx", "QQ音乐" -> "QQ音乐"
            "5sing" -> "5sing"
            "htqyy", "好听轻音乐" -> "好听轻音乐"
            "qingting", "蜻蜓FM" -> "蜻蜓FM"
            "ximalaya", "喜马拉雅" -> "喜马拉雅"
            "kw", "酷我音乐" -> "酷我音乐"
            "mg", "咪咕音乐" -> "咪咕音乐"
            "酷狗音乐" -> "酷狗音乐"
            "小芸音乐", "小秋音乐", "小蜗音乐", "小枸音乐", "小蜜音乐", "千千音乐" -> value
            else -> null
        }
    }

    fun isFakePlatform(value: String?): Boolean {
        return when (value?.trim()) {
            "pc", "h5", "20" -> true
            else -> false
        }
    }

    fun toLxId(mfPlatform: String?): String {
        if (mfPlatform == null) return ""
        return when {
            mfPlatform.contains("网易") || mfPlatform.contains("小芸") -> "wy"
            mfPlatform.contains("酷我") || mfPlatform.contains("小蜗") -> "kw"
            mfPlatform.contains("酷狗") || mfPlatform.contains("小枸") -> "kg"
            mfPlatform.contains("QQ") || mfPlatform.contains("qq") || mfPlatform.contains("企鹅") || mfPlatform.contains("腾讯") || mfPlatform.contains("小秋") -> "tx"
            mfPlatform.contains("咪咕") || mfPlatform.contains("小蜜") -> "mg"
            mfPlatform.contains("kugou") -> "kg"
            mfPlatform.contains("netease") -> "wy"
            else -> ""
        }
    }

    fun isKnownPlatform(platform: String?): Boolean {
        val value = platform?.trim().orEmpty()
        if (value.isEmpty() || isFakePlatform(value)) return false
        if (properName(value) != null) return true
        return value.any { it in '一'..'鿿' }
    }

    fun nameFromFilename(filename: String?): String {
        if (filename.isNullOrBlank()) return ""
        val tokens = filename.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
        for (token in tokens) {
            val name = properName(token.lowercase())
            if (name != null) return name
        }
        return ""
    }
}
