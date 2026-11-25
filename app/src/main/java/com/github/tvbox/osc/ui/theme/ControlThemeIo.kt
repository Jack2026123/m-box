package com.github.tvbox.osc.ui.theme

import com.github.tvbox.osc.ui.components.ControlElementKind
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 3.07 百变控件 · 第二阶段：控件主题导出 / 导入（JSON）。
 *
 * 一套控件主题一个 JSON 文件，六套预设本身内置在 App 里一键套用，
 * 导出是用来分享给别人的——对方导入同一份 JSON 就能用同一套。
 *
 * 格式（version 1）：
 * {
 *   "format": "avbox_control_theme",
 *   "version": 1,
 *   "presetId": "default",
 *   "name": "默认",
 *   "sizeScale": 1.0,
 *   "fontScale": 1.0,
 *   "shape": 0,
 *   "cornerMode": 0,
 *   "customColorArgb": 0,
 *   "coveredKinds": ["button", "card", "poster", "episode", "line", "chip"]
 *   // 3.09 新增（全收归一）：cornerScale / reliefEnabled / reliefProfile /
 *   // reliefMagnitude / curvature / curvatureEnabled / curvatureOutward / lightBrightness。
 *   // 旧文件可以没有这 8 个字段：导入时按 presetId 对应预设的默认值补，不拒绝旧文件。
 *   // 3.10 曾有单元素覆盖 "overrides"，3.14 已删：导出不再写这一节，
 *   //   导入时有这一节也直接忽略（不校验、不应用、不拒绝），
 *   //   老文件照常导入，只按顶层 + groups 两节应用。
 *   // 3.11 新增：两组值 "groups"：{ "card": {…17 个字段…}, "capsule": {…} }，
 *   //   字段名与顶层一致，只写值。顶层现有字段不动（老文件兼容 + 全局兜底）；
 *   //   无 "groups" 节的老文件导入后两组全部按顶层主题值应用；有 "groups" 节时
 *   //   某组缺字段用顶层 / 预设默认补，不拒绝。
 *   // 3.16 新增：底色 "baseColorArgb"（0=跟随主题 slate；非 0=自定义底色 ARGB，
 *   //   只对胶囊组生效）。老文件没有它时按预设默认补（参考图水滴蓝紫），不拒绝。
 *   // 3.17 新增：选中颜色 "selectedContentColorArgb"（0=跟随主题主色；非 0=自定义选中字色，
 *   //   只对胶囊组生效）。老文件没有它时按预设默认补（参考图暖金），不拒绝。
 *   // 3.18 新增：胶囊风格 "capsuleStyle"（"water" 水滴 / "light" 轻透，只对胶囊组生效）。
 *   //   老文件没有它时按预设默认补（水滴），非法值归一到水滴，不拒绝。
 *   // 3.19 新增：描边颜色 "borderColorArgb"（0=跟随主题强调色；非 0=自定义描边颜色 ARGB，
 *   //   只对胶囊组生效）。老文件没有它时按预设默认补（0=跟主题），不拒绝。
 *   // 3.17 删除：配色 "colorMode" 已删——导出不再写它；老文件里有它也直接忽略（不校验、不应用）。
 * }
 *
 * 导入全量校验：format / version 对不上、缺必填字段、数值越界、
 * 未知预设、未知元素种类 → 拒绝并返回原因，不崩溃，
 * 不应用半截状态（只有全部通过才 [ControlThemeStore.setTheme]）。
 * 3.09 的 8 个新字段缺失时用预设默认值补，不算缺字段；写了但越界 → 拒绝。
 * 编解码只用 org.json（Android 自带），不引入新依赖。
 */
object ControlThemeIo {

    const val FORMAT = "avbox_control_theme"
    const val VERSION = 1

    /** 导入结果：Ok 只在全部校验通过后才应用；Err 保证什么都没改。 */
    sealed class ImportResult {
        data class Ok(
            val theme: ControlTheme,
            val name: String,
            // 3.11 两组值：有 "groups" 节时按节里的值（缺字段用顶层补），无节的老文件为两组全等于顶层主题。
            val groups: Map<ControlGroup, ControlTheme> = emptyMap(),
        ) : ImportResult()
        data class Err(val reason: String) : ImportResult()
    }

    /** 元素种类在 JSON 里的名字：枚举小写（button / card / poster / episode / line / chip）。 */
    fun kindJsonName(kind: ControlElementKind): String =
        kind.name.lowercase(Locale.ROOT)

    fun kindFromJsonName(name: String): ControlElementKind? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return ControlElementKind.values().firstOrNull { kindJsonName(it) == trimmed }
    }

    /** 元素种类在设置页的显示名。3.62：PLAYER 显示“播放器”。 */
    fun kindDisplayName(kind: ControlElementKind): String = when (kind) {
        ControlElementKind.BUTTON -> "胶囊"
        ControlElementKind.CARD -> "卡片"
        ControlElementKind.POSTER -> "海报"
        ControlElementKind.EPISODE -> "选集"
        ControlElementKind.LINE -> "线路"
        ControlElementKind.CHIP -> "筛选"
        ControlElementKind.PLAYER -> "播放器"
    }

    /** 全七种（默认覆盖范围）。 */
    fun allKinds(): Set<ControlElementKind> = ControlElementKind.values().toSet()

    /** 20 个值字段（顶层与 groups 里每组共用同一套字段名；presetId / name / coveredKinds 只在顶层）。 */
    private fun putThemeFields(json: JSONObject, theme: ControlTheme) {
        json.put("sizeScale", theme.sizeScale.toDouble())
        json.put("fontScale", theme.fontScale.toDouble())
        json.put("shape", theme.shape)
        json.put("cornerMode", theme.cornerMode)
        json.put("cornerScale", theme.cornerScale.toDouble())
        json.put("reliefEnabled", theme.reliefEnabled)
        json.put("reliefProfile", theme.reliefProfile)
        json.put("reliefMagnitude", theme.reliefMagnitude.toDouble())
        json.put("curvature", theme.curvature)
        json.put("curvatureEnabled", theme.curvatureEnabled)
        json.put("curvatureOutward", theme.curvatureOutward)
        json.put("lightBrightness", theme.lightBrightness)
        json.put("waterGloss", theme.waterGloss)
        json.put("fontColorArgb", theme.fontColorArgb)
        json.put("customColorArgb", theme.customColorArgb)
        json.put("baseColorArgb", theme.baseColorArgb)
        json.put("selectedContentColorArgb", theme.selectedContentColorArgb)
        json.put("borderColorArgb", theme.borderColorArgb)
        json.put("capsuleStyle", theme.capsuleStyle)
    }

    /** 导出：当前主题 + 覆盖范围写成 JSON 文本。 */
    fun exportJson(theme: ControlTheme): String {
        val json = JSONObject()
        json.put("format", FORMAT)
        json.put("version", VERSION)
        json.put("presetId", theme.presetId)
        json.put("name", ControlThemePresets.nameOf(theme.presetId))
        putThemeFields(json, theme)
        val kinds = JSONArray()
        theme.coveredKinds.sortedBy { it.ordinal }.forEach { kinds.put(kindJsonName(it)) }
        json.put("coveredKinds", kinds)
        // 3.11 两组值：每组一份，字段名与顶层一致，只写值（顶层不动，老文件兼容 + 全局兜底）。
        val groupThemes = JSONObject()
        for (group in ControlGroup.values()) {
            val groupJson = JSONObject()
            putThemeFields(groupJson, ControlThemeStore.groupThemes()[group] ?: theme)
            groupThemes.put(group.kvName(), groupJson)
        }
        json.put("groups", groupThemes)
        // 3.14：单元素覆盖已删，导出不再写 "overrides" 节。
        return json.toString(2)
    }

    /** 导出文件名：avbox_control_theme_<presetId>_<yyyyMMdd_HHmmss>.json。 */
    fun exportFileName(theme: ControlTheme): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "avbox_control_theme_" + theme.presetId + "_" + stamp + ".json"
    }

    /** 只解析 + 全量校验，不写 KV、不改状态。 */
    fun parseImportJson(text: String): ImportResult {
        val json: JSONObject
        try {
            json = JSONObject(text)
        } catch (e: Exception) {
            return ImportResult.Err("不是有效的 JSON 文件")
        }
        if (!json.has("format") || json.optString("format") != FORMAT) {
            return ImportResult.Err("不是 AVBox 控件主题文件（format 不符）")
        }
        if (!json.has("version") || json.optInt("version", -1) != VERSION) {
            return ImportResult.Err("控件主题版本不支持（仅支持 v1）")
        }
        if (!json.has("name") || json.optString("name").trim().isEmpty()) {
            return ImportResult.Err("缺少显示名（name）")
        }
        val name = json.optString("name").trim()
        if (!json.has("presetId") || json.optString("presetId").trim().isEmpty()) {
            return ImportResult.Err("缺少预设（presetId）")
        }
        val presetId = json.optString("presetId").trim()
        // 3.13：已删的五个预设不再拒绝——byId 落回 DEFAULT（见下面 val preset），
        // 文件按默认预设的默认值补字段，导入的主题 presetId 写 "default"，不把已删 id 写进 KV。
        var sizeScale: Double
        val fontScale: Double
        val shape: Int
        val cornerMode: Int
        val customColorArgb: Int
        // 3.09 新增字段：旧文件可以没有，缺失时用对应预设的默认值补（不拒绝旧文件）。
        val preset = ControlThemePresets.byId(presetId)
        val cornerScale: Double
        val reliefEnabled: Boolean
        val reliefProfile: Int
        val reliefMagnitude: Double
        val curvature: Int
        val curvatureEnabled: Boolean
        val curvatureOutward: Boolean
        val lightBrightness: Int
        val waterGloss: Int
        val fontColorArgb: Int
        val baseColorArgb: Int
        val selectedContentColorArgb: Int
        val borderColorArgb: Int
        val capsuleStyle: String
        try {
            if (!json.has("sizeScale") || !json.has("fontScale") || !json.has("shape") ||
                !json.has("cornerMode") || !json.has("customColorArgb")
            ) {
                return ImportResult.Err("缺少字段（sizeScale / fontScale / shape / cornerMode / customColorArgb）")
            }
            sizeScale = json.getDouble("sizeScale")
            fontScale = json.getDouble("fontScale")
            shape = json.getInt("shape")
            cornerMode = json.getInt("cornerMode")
            customColorArgb = json.getInt("customColorArgb")
            cornerScale = if (json.has("cornerScale")) json.getDouble("cornerScale") else preset.cornerScale.toDouble()
            reliefEnabled = if (json.has("reliefEnabled")) json.getBoolean("reliefEnabled") else preset.reliefEnabled
            reliefProfile = if (json.has("reliefProfile")) json.getInt("reliefProfile") else preset.reliefProfile
            reliefMagnitude = if (json.has("reliefMagnitude")) json.getDouble("reliefMagnitude") else preset.reliefMagnitude.toDouble()
            curvature = if (json.has("curvature")) json.getInt("curvature") else preset.curvature
            curvatureEnabled = if (json.has("curvatureEnabled")) json.getBoolean("curvatureEnabled") else preset.curvatureEnabled
            curvatureOutward = if (json.has("curvatureOutward")) json.getBoolean("curvatureOutward") else preset.curvatureOutward
            lightBrightness = if (json.has("lightBrightness")) json.getInt("lightBrightness") else preset.lightBrightness
            waterGloss = if (json.has("waterGloss")) json.getInt("waterGloss") else preset.waterGloss
            fontColorArgb = if (json.has("fontColorArgb")) json.getInt("fontColorArgb") else preset.fontColorArgb
            baseColorArgb = if (json.has("baseColorArgb")) json.getInt("baseColorArgb") else preset.baseColorArgb
            selectedContentColorArgb = if (json.has("selectedContentColorArgb")) json.getInt("selectedContentColorArgb") else preset.selectedContentColorArgb
            borderColorArgb = if (json.has("borderColorArgb")) json.getInt("borderColorArgb") else preset.borderColorArgb
            capsuleStyle = if (json.has("capsuleStyle")) ControlTheme.normalizeCapsuleStyle(json.optString("capsuleStyle")) else preset.capsuleStyle
        } catch (e: Exception) {
            return ImportResult.Err("数值字段格式不对")
        }
        sizeScale = sizeScale.coerceIn(
            ControlThemeStore.SIZE_RANGE.start.toDouble(),
            ControlThemeStore.SIZE_RANGE.endInclusive.toDouble()
        )
        if (fontScale < ControlThemeStore.FONT_SCALE_RANGE.start.toDouble() ||
            fontScale > ControlThemeStore.FONT_SCALE_RANGE.endInclusive.toDouble()
        ) {
            return ImportResult.Err("字号超出范围（0.8–1.5）： " + fontScale)
        }
        if (shape !in EffectsState.CARD_SHAPE_OPTIONS) {
            return ImportResult.Err("未知形状（shape）：" + shape)
        }
        if (cornerMode < ControlTheme.CORNER_FOLLOW_THEME || cornerMode > ControlTheme.CORNER_PILL) {
            return ImportResult.Err("未知圆角模式（cornerMode）：" + cornerMode)
        }
        // 3.09 新增字段范围校验（只在文件里写了时才判；缺失已按预设默认补过，不会误拒）。
        if (cornerScale < EffectsState.CORNER_RANGE.start.toDouble() ||
            cornerScale > EffectsState.CORNER_RANGE.endInclusive.toDouble()
        ) {
            return ImportResult.Err("圆角倍率超出范围（0–2）： " + cornerScale)
        }
        // 已下线的 profile（3/4/5/6）与 EffectsState 存值同口径：落回球面弧形，不拒绝。
        val normalizedReliefProfile = when (reliefProfile) {
            EffectsState.RELIEF_PROFILE_COCOA, EffectsState.RELIEF_PROFILE_CYBER,
            EffectsState.RELIEF_PROFILE_PORCELAIN -> EffectsState.RELIEF_PROFILE_DOME
            else -> reliefProfile.coerceIn(EffectsState.RELIEF_PROFILE_FLAT, EffectsState.RELIEF_PROFILE_FACETED)
        }
        if (reliefMagnitude < EffectsState.ELEVATION_RANGE.start.toDouble() ||
            reliefMagnitude > EffectsState.ELEVATION_RANGE.endInclusive.toDouble()
        ) {
            return ImportResult.Err("浮雕强度超出范围（0–200）： " + reliefMagnitude)
        }
        if (curvature < 0 || curvature > 200) {
            return ImportResult.Err("曲面超出范围（0–200）： " + curvature)
        }
        if (lightBrightness < 0 || lightBrightness > 200) {
            return ImportResult.Err("光感亮度超出范围（0–200）： " + lightBrightness)
        }
        if (waterGloss < 0 || waterGloss > 200) {
            return ImportResult.Err("水光超出范围（0–200）： " + waterGloss)
        }
        if (!json.has("coveredKinds")) {
            return ImportResult.Err("缺少覆盖范围（coveredKinds）")
        }
        val kindsArray = json.optJSONArray("coveredKinds")
            ?: return ImportResult.Err("覆盖范围（coveredKinds）格式不对")
        if (kindsArray.length() == 0) {
            return ImportResult.Err("覆盖范围为空，至少选一种元素")
        }
        val coveredKinds = LinkedHashSet<ControlElementKind>()
        for (i in 0 until kindsArray.length()) {
            val kindName = kindsArray.optString(i)
            val kind = kindFromJsonName(kindName)
                ?: return ImportResult.Err("未知的元素种类：" + kindName)
            coveredKinds.add(kind)
        }
        // 3.14：单元素覆盖已删，文件里有 "overrides" 节也直接忽略（不校验、不应用）。
        val theme = ControlTheme(
            presetId = preset.presetId,
            sizeScale = sizeScale.toFloat().coerceIn(ControlThemeStore.SIZE_RANGE),
            fontScale = fontScale.toFloat().coerceIn(ControlThemeStore.FONT_SCALE_RANGE),
            shape = shape,
            cornerMode = cornerMode,
            cornerScale = cornerScale.toFloat().coerceIn(EffectsState.CORNER_RANGE),
            reliefEnabled = reliefEnabled,
            reliefProfile = normalizedReliefProfile,
            reliefMagnitude = reliefMagnitude.toFloat().coerceIn(EffectsState.ELEVATION_RANGE),
            curvature = curvature.coerceIn(0, 200),
            curvatureEnabled = curvatureEnabled,
            curvatureOutward = curvatureOutward,
            lightBrightness = lightBrightness.coerceIn(0, 200),
            waterGloss = waterGloss.coerceIn(0, 200),
            fontColorArgb = fontColorArgb,
            customColorArgb = customColorArgb,
            baseColorArgb = baseColorArgb,
            selectedContentColorArgb = selectedContentColorArgb,
            borderColorArgb = borderColorArgb,
            capsuleStyle = capsuleStyle,
            coveredKinds = coveredKinds,
        )
        // 3.11 两组值：有 "groups" 节 → 每组按节里的值（缺字段用顶层主题补）；
        // 无节的老文件 → 两组全部按顶层主题值。格式 / 越界 → 拒绝整份文件。
        val groupThemes: Map<ControlGroup, ControlTheme>
        try {
            groupThemes = parseGroups(json, theme)
        } catch (e: IllegalArgumentException) {
            return ImportResult.Err(e.message ?: "两组值（groups）格式不对")
        }
        return ImportResult.Ok(theme, name, groupThemes)
    }

    /** 组名（card / capsule）→ [ControlGroup]；认不出返回 null。 */
    private fun controlGroupFromJsonName(name: String): ControlGroup? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return ControlGroup.values().firstOrNull { it.kvName() == trimmed }
    }

    /**
     * 解析 "groups"：缺失 = 两组全等于顶层主题（老文件）；present 但不是对象、
     * 某组不是对象、未知组名、某字段类型不对或数值越界 → 抛 IllegalArgumentException，
     * 调用方转 Err，整份文件拒绝，什么都不改。
     * 每组字段全可选：缺失 = 顶层主题对应字段（顶层本身已按预设默认补过）；节里没写的组 = 全等于顶层主题。
     */
    private fun parseGroups(json: JSONObject, topTheme: ControlTheme): Map<ControlGroup, ControlTheme> {
        val out = LinkedHashMap<ControlGroup, ControlTheme>()
        for (group in ControlGroup.values()) {
            // 3.18：无 "groups" 节的老文件也一样——胶囊组边框形状恒为跟主题，
            // 顶层带 pill 值也不写回来。
            out[group] = if (group == ControlGroup.CAPSULE) {
                topTheme.copy(cornerMode = ControlTheme.CORNER_FOLLOW_THEME)
            } else {
                topTheme
            }
        }
        if (!json.has("groups")) return out
        val raw = json.optJSONObject("groups")
            ?: throw IllegalArgumentException("两组值（groups）格式不对")
        val keys = raw.keys()
        while (keys.hasNext()) {
            val groupName = keys.next()
            val group = controlGroupFromJsonName(groupName)
                ?: throw IllegalArgumentException("未知的元素组（groups）：" + groupName)
            val obj = raw.optJSONObject(groupName)
                ?: throw IllegalArgumentException("两组值（" + groupName + "）格式不对")
            try {
                val sizeScale = if (obj.has("sizeScale")) obj.getDouble("sizeScale") else topTheme.sizeScale.toDouble()
                val fontScale = if (obj.has("fontScale")) obj.getDouble("fontScale") else topTheme.fontScale.toDouble()
                val shape = if (obj.has("shape")) obj.getInt("shape") else topTheme.shape
                // 3.18：胶囊组边框形状恒为跟主题——节里缺字段跟顶层，有字段是直角/大圆角也归一到跟主题
                //（旧导出文件带 pill 值时不得再把 pill 写回来）；卡片组按节里的值走。
                val cornerMode = if (group == ControlGroup.CAPSULE) {
                    ControlTheme.CORNER_FOLLOW_THEME
                } else if (obj.has("cornerMode")) {
                    obj.getInt("cornerMode")
                } else {
                    topTheme.cornerMode
                }
                val cornerScale = if (obj.has("cornerScale")) obj.getDouble("cornerScale") else topTheme.cornerScale.toDouble()
                val reliefEnabled = if (obj.has("reliefEnabled")) obj.getBoolean("reliefEnabled") else topTheme.reliefEnabled
                val reliefProfile = if (obj.has("reliefProfile")) obj.getInt("reliefProfile") else topTheme.reliefProfile
                val reliefMagnitude = if (obj.has("reliefMagnitude")) obj.getDouble("reliefMagnitude") else topTheme.reliefMagnitude.toDouble()
                val curvature = if (obj.has("curvature")) obj.getInt("curvature") else topTheme.curvature
                val curvatureEnabled = if (obj.has("curvatureEnabled")) obj.getBoolean("curvatureEnabled") else topTheme.curvatureEnabled
                val curvatureOutward = if (obj.has("curvatureOutward")) obj.getBoolean("curvatureOutward") else topTheme.curvatureOutward
                val lightBrightness = if (obj.has("lightBrightness")) obj.getInt("lightBrightness") else topTheme.lightBrightness
                val waterGloss = if (obj.has("waterGloss")) obj.getInt("waterGloss") else topTheme.waterGloss
                val fontColorArgb = if (obj.has("fontColorArgb")) obj.getInt("fontColorArgb") else topTheme.fontColorArgb
                val customColorArgb = if (obj.has("customColorArgb")) obj.getInt("customColorArgb") else topTheme.customColorArgb
                val baseColorArgb = if (obj.has("baseColorArgb")) obj.getInt("baseColorArgb") else topTheme.baseColorArgb
                val selectedContentColorArgb = if (obj.has("selectedContentColorArgb")) obj.getInt("selectedContentColorArgb") else topTheme.selectedContentColorArgb
                val borderColorArgb = if (obj.has("borderColorArgb")) obj.getInt("borderColorArgb") else topTheme.borderColorArgb
                val capsuleStyle = if (obj.has("capsuleStyle")) ControlTheme.normalizeCapsuleStyle(obj.optString("capsuleStyle")) else topTheme.capsuleStyle
                if (fontScale < ControlThemeStore.FONT_SCALE_RANGE.start.toDouble() ||
                    fontScale > ControlThemeStore.FONT_SCALE_RANGE.endInclusive.toDouble()
                ) {
                    throw IllegalArgumentException("两组值（" + groupName + "）字号超出范围（0.8–1.5）")
                }
                if (shape !in EffectsState.CARD_SHAPE_OPTIONS) {
                    throw IllegalArgumentException("两组值（" + groupName + "）未知形状（shape）：" + shape)
                }
                if (cornerMode < ControlTheme.CORNER_FOLLOW_THEME || cornerMode > ControlTheme.CORNER_PILL) {
                    throw IllegalArgumentException("两组值（" + groupName + "）未知圆角模式（cornerMode）：" + cornerMode)
                }
                if (cornerScale < EffectsState.CORNER_RANGE.start.toDouble() ||
                    cornerScale > EffectsState.CORNER_RANGE.endInclusive.toDouble()
                ) {
                    throw IllegalArgumentException("两组值（" + groupName + "）圆角倍率超出范围（0–2）")
                }
                if (reliefMagnitude < EffectsState.ELEVATION_RANGE.start.toDouble() ||
                    reliefMagnitude > EffectsState.ELEVATION_RANGE.endInclusive.toDouble()
                ) {
                    throw IllegalArgumentException("两组值（" + groupName + "）浮雕强度超出范围（0–200）")
                }
                if (curvature < 0 || curvature > 200) {
                    throw IllegalArgumentException("两组值（" + groupName + "）曲面超出范围（0–200）")
                }
                if (lightBrightness < 0 || lightBrightness > 200) {
                    throw IllegalArgumentException("两组值（" + groupName + "）光感亮度超出范围（0–200）")
                }
                if (waterGloss < 0 || waterGloss > 200) {
                    throw IllegalArgumentException("两组值（" + groupName + "）水光超出范围（0–200）")
                }
                // 已下线的 profile（3/4/5/6）与顶层导入同口径：落回球面弧形，不拒绝。
                val normalizedReliefProfile = when (reliefProfile) {
                    EffectsState.RELIEF_PROFILE_COCOA, EffectsState.RELIEF_PROFILE_CYBER,
                    EffectsState.RELIEF_PROFILE_PORCELAIN -> EffectsState.RELIEF_PROFILE_DOME
                    else -> reliefProfile.coerceIn(EffectsState.RELIEF_PROFILE_FLAT, EffectsState.RELIEF_PROFILE_FACETED)
                }
                out[group] = topTheme.copy(
                    sizeScale = sizeScale.toFloat().coerceIn(ControlThemeStore.SIZE_RANGE),
                    fontScale = fontScale.toFloat().coerceIn(ControlThemeStore.FONT_SCALE_RANGE),
                    shape = shape,
                    cornerMode = cornerMode,
                    cornerScale = cornerScale.toFloat().coerceIn(EffectsState.CORNER_RANGE),
                    reliefEnabled = reliefEnabled,
                    reliefProfile = normalizedReliefProfile,
                    reliefMagnitude = reliefMagnitude.toFloat().coerceIn(EffectsState.ELEVATION_RANGE),
                    curvature = curvature.coerceIn(0, 200),
                    curvatureEnabled = curvatureEnabled,
                    curvatureOutward = curvatureOutward,
                    lightBrightness = lightBrightness.coerceIn(0, 200),
                    waterGloss = waterGloss.coerceIn(0, 200),
                    fontColorArgb = fontColorArgb,
                    customColorArgb = customColorArgb,
                    baseColorArgb = baseColorArgb,
                    selectedContentColorArgb = selectedContentColorArgb,
                    borderColorArgb = borderColorArgb,
                    capsuleStyle = capsuleStyle,
                )
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("两组值（" + groupName + "）数值字段格式不对")
            }
        }
        return out
    }

    /** 解析 + 校验 + 应用：只有全部通过才写 KV + 更新 state，否则什么都不改。 */
    fun importAndApply(text: String): ImportResult {
        val result = parseImportJson(text)
        if (result is ImportResult.Ok) {
            ControlThemeStore.setTheme(result.theme)
            // 3.11 两组值与全局同语义：整份替换（parse 已保证 groups 总是两组齐全；
            // 无节的老文件 groups 全等于顶层主题，即两组全部按顶层值应用）。
            for ((group, groupTheme) in result.groups) {
                ControlThemeStore.setGroupTheme(group, groupTheme)
            }
            // 3.14：单元素覆盖已删，文件里的 "overrides" 节一律忽略，不应用。
        }
        return result
    }
}
