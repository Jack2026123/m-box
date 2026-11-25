package com.github.tvbox.osc.music

/**
 * 从洛雪脚本头部注释里读出来的元信息。
 *
 * 洛雪脚本头部长这样（见 洛雪音源1.js / 9.js / 15.js）：
 * 开头是 斜杠+星号+叹号，每行 `@name` / `@description` / `@version` /
 * `@author` / `@homepage`，结尾是一行星号+斜杠。
 * 形如：
 * ```
 *  @name         星海音乐源
 *  @description  GDAPI | 聚合 ...
 *  @version      v3.2.15
 *  @author       万去了了
 *  @homepage     https://zddyr.top/
 * ```
 * 注意：本注释里不能出现真正的开注释/闭注释符号，Kotlin 块注释可嵌套，
 * 一旦出现就会把后面的代码全部吞掉。
 * 有些脚本（如 lx-a、fish_music）用 `//` 行注释，也一并支持。
 */
data class MusicScriptMeta(
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
) {
    val displayName: String get() = name.ifBlank { "未命名源" }

    /** 源管理页右侧的副标题：#版本 · 作者 */
    val subtitle: String
        get() = buildList {
            if (version.isNotBlank()) add(version)
            if (author.isNotBlank()) add(author)
        }.joinToString(" · ")
}

object MusicScriptParser {
    /** 头部只扫前若干行，避免把大脚本整个正则扫一遍 */
    private const val HEADER_SCAN_CHARS = 4096

    private val KEYS = listOf("name", "version", "description", "author", "homepage")

    /**
     * 解析脚本头。三个 JS 文件里 `@name` 后面的对齐空格宽度不一致，
     * 有的用 tab，所以这里只按"@key + 任意空白"切。
     */
    fun parseMeta(code: String?): MusicScriptMeta {
        if (code.isNullOrBlank()) return MusicScriptMeta()
        val head = code.take(HEADER_SCAN_CHARS)
        val map = HashMap<String, String>(KEYS.size)

        // /*! ... */ 、// ... 都算注释，逐行找 @key
        for (line in head.lineSequence()) {
            val at = line.indexOf('@')
            if (at < 0) continue
            val rest = line.substring(at + 1)
            val key = rest.substringWhile { it.isLetter() }.lowercase()
            if (key !in KEYS) continue
            val value = rest.substring(key.length).trim()
                .removePrefix(":")
                .trim()
                .trimEnd(',', ';')
            if (value.isEmpty()) continue
            // 同名 key 只取第一次出现
            map.putIfAbsent(key, value)
        }

        return MusicScriptMeta(
            name = map["name"].orEmpty(),
            version = map["version"].orEmpty(),
            description = map["description"].orEmpty(),
            author = map["author"].orEmpty(),
            homepage = map["homepage"].orEmpty(),
        )
    }

    private fun String.substringWhile(predicate: (Char) -> Boolean): String {
        val end = indexOfFirst { !predicate(it) }.let { if (it < 0) length else it }
        return substring(0, end)
    }
}