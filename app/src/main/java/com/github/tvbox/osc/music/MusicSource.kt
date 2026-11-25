package com.github.tvbox.osc.music

/**
 * 音乐源种类。目前只做洛雪，MusicFree 留位（P4 再填）。
 */
enum class MusicSourceKind {
    LX,

    /** 预留：MusicFree 插件（P4） */
    MUSIC_FREE;

    companion object {
        fun of(name: String?): MusicSourceKind =
            entries.firstOrNull { it.name == name } ?: LX
    }
}

/**
 * 一个音乐源 = 一份脚本全文 + 元信息。
 *
 * [code] 直接存脚本原文（洛雪脚本几十 KB，MMKV 放得下），
 * 不落文件，避免"文件被系统清理导致源失效"这类问题（对照 2.68 之前的坑）。
 */
data class MusicSource(
    /** 稳定 id：kind + hash(code) 前 12 位，同内容重复导入自动合并 */
    val id: String,
    /** 脚本里的 @name */
    val name: String,
    /** 脚本里的 @version */
    val version: String,
    /** 脚本里的 @description */
    val description: String,
    /** 脚本里的 @author */
    val author: String,
    /** 脚本里的 @homepage */
    val homepage: String,
    val kind: MusicSourceKind = MusicSourceKind.LX,
    /** 是否启用（停用后不参与搜索，但保留配置） */
    val enabled: Boolean = true,
    /** 脚本全文；空串表示元信息在、脚本丢了（源管理页标红提示重新导入） */
    val code: String = "",
) {
    val codeMissing: Boolean get() = code.isBlank()

    /** 列表副标题：版本 + 作者，两样都没有就说明是没头注释的脚本 */
    val subtitle: String
        get() = listOf(version, author)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    fun withEnabled(value: Boolean): MusicSource = copy(enabled = value)
}