package com.github.tvbox.osc.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.ui.components.ControlElementKind
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

/**
 * 3.07 百变控件 · 第一阶段地基：控件主题模型。
 *
 * 全 App 的按钮（下一阶段接线）都读 [ControlThemeStore.current]，
 * 切预设 / 改任一字段即写 KV + 更新 state，读 state 的按钮即时变，
 * 重启后 load() 从 KV 恢复，同一预设还在。
 *
 * 字段一律有默认值；shape 复用 [EffectsState] 卡片形状值
 * （0 矩形 / 5 五边形 / 6 六边形 / 8 八边形 / 99 心形 / 100 圆形），
 * 心形只作为单按钮可选项，不做任何预设的默认（会裁掉文字）。
 *
 * 覆盖范围 [coveredKinds]（3.07 第二阶段）：当前主题只对这些种类的
 * 元素生效；不在范围里的元素回落到 [ControlThemePresets.DEFAULT]
 * 的大小 / 形状 / 配色，不套当前主题（见 ControlButton.resolveButtonStyle）。
 * 默认全六种（胶囊 / 卡片 / 海报 / 选集 / 线路 / 筛选）。
 * 3.18 新增：胶囊风格 capsuleStyle（"water" 水滴 / "light" 轻透，只对胶囊组生效）。
 * 3.41 新增第三种："raised" 立体（raisedCapsuleGloss 实色底 + 柔和外阴影 + 顶部亮面）。
 */
data class ControlTheme(
    val presetId: String = ControlThemePresets.DEFAULT_ID,
    val sizeScale: Float = 0.5f,
    val fontScale: Float = 1.0f,
    val shape: Int = EffectsState.CARD_SHAPE_RECT,
    val cornerMode: Int = ControlTheme.CORNER_FOLLOW_THEME,
    // 3.09 全收归一：原主题 5 项元素设置搬进来（形状复用上面的 shape）。
    // cornerScale 是浮点倍率（0..2），与 cornerMode（模式：跟主题/直角/大圆角）并存，互不覆盖。
    // reliefMagnitude 是浮雕滑杆值（0..200，沿用 EffectsState.ELEVATION_RANGE；开关关时渲染归零、值保留，
    // 见 EffectsConfig.reliefMagnitude）；curvature 是曲面滑杆值（0..200%，同理开关门控）。
    val cornerScale: Float = 1.0f,
    val reliefEnabled: Boolean = false,
    val reliefProfile: Int = EffectsState.DEFAULT_RELIEF_PROFILE,
    val reliefMagnitude: Float = EffectsState.DEFAULT_CARD_ELEVATION_DP,
    val curvature: Int = EffectsState.DEFAULT_CURVATURE,
    val curvatureEnabled: Boolean = false,
    val curvatureOutward: Boolean = false,
    val lightBrightness: Int = EffectsState.DEFAULT_LIGHT_BRIGHTNESS,
    // 3.11 水光强度（0..200，默认 120 = 现水胶囊 level 60 换算；卡片组默认 40，
    // 切预设 / 恢复默认时卡片组另写 40，不跟这个值）。
    val waterGloss: Int = EffectsState.DEFAULT_WATER_GLOSS,
    // 3.11 字体颜色（0 = 跟主题，胶囊上默认白字；非 0 = 自定义字色 ARGB）。
    val fontColorArgb: Int = 0,
    val customColorArgb: Int = 0,
    // 3.16 底色（0 = 跟随主题 slate；非 0 = 自定义底色 ARGB，只对胶囊组生效；
    // 卡片组不读它，海报底只是图片未加载时的衬底）。
    val baseColorArgb: Int = 0,
    // 3.17 选中颜色（0 = 跟随主题主色；非 0 = 自定义选中字色 ARGB，只对胶囊组生效；
    // 卡片组不读它，卡片组选中字仍走平时字色）。
    val selectedContentColorArgb: Int = 0,
    // 3.19 描边颜色（0 = 跟随主题强调色；非 0 = 自定义描边颜色 ARGB，只对胶囊组生效；
    // 卡片组不读它，卡片描边仍走强调色）。
    val borderColorArgb: Int = 0,
    // 3.18 胶囊风格（"water" = 水滴 / "light" = 轻透 / "raised" = 立体，只对胶囊组生效；
    // 卡片组不读它，海报仍走平板底 + 顶部亮面）。
    val capsuleStyle: String = CAPSULE_STYLE_WATER,
    val coveredKinds: Set<ControlElementKind> = ControlElementKind.values().toSet(),
) {
    companion object {
        /** 圆角模式：0 = 跟主题圆角，1 = 直角，2 = 大圆角 / pill。 */
        const val CORNER_FOLLOW_THEME = 0
        const val CORNER_SQUARE = 1
        const val CORNER_PILL = 2

        /** 胶囊风格：水滴（waterDropGloss 实色透亮底）/ 轻透（0.15 淡底 + 描边 + 顶部亮面）/ 立体（raisedCapsuleGloss 实色底 + 柔和外阴影 + 顶部亮面）。 */
        const val CAPSULE_STYLE_WATER = "water"
        const val CAPSULE_STYLE_LIGHT = "light"
        const val CAPSULE_STYLE_RAISED = "raised"
        /** 深凹纽扣（3.44，5 样式之一）：槽更深，见 grooveDepth。 */
        const val CAPSULE_STYLE_DEEP_BUTTON = "deep_button"
        /** 微立体纽扣（3.44，5 样式之一）：只留浅边缘，见 grooveDepth。 */
        const val CAPSULE_STYLE_FLAT_BUTTON = "flat_button"

        /** 1.14：胶囊风格已删；非法风格值一律归一（深凹存过的回到纽扣）。 */
        fun normalizeCapsuleStyle(value: String): String = when (value) {
            CAPSULE_STYLE_LIGHT -> CAPSULE_STYLE_LIGHT
            CAPSULE_STYLE_RAISED -> CAPSULE_STYLE_RAISED
            CAPSULE_STYLE_DEEP_BUTTON -> CAPSULE_STYLE_RAISED
            CAPSULE_STYLE_FLAT_BUTTON -> CAPSULE_STYLE_FLAT_BUTTON
            else -> CAPSULE_STYLE_WATER
        }

        /** 凹槽深度（3.44，5 样式）：纽扣 1.0、深凹 1.4、微立体 0.33。 */
        fun grooveDepth(style: String): Float = when (normalizeCapsuleStyle(style)) {
            CAPSULE_STYLE_DEEP_BUTTON -> 1.65f
            CAPSULE_STYLE_FLAT_BUTTON -> 0.22f
            else -> 1f
        }

        /** 是否走凹槽画法（纽扣/深凹/微立体三档共用 raisedCapsuleGloss，只深度不同）。 */
        fun isGrooveStyle(style: String): Boolean = normalizeCapsuleStyle(style) in setOf(CAPSULE_STYLE_RAISED, CAPSULE_STYLE_DEEP_BUTTON, CAPSULE_STYLE_FLAT_BUTTON)

        /** 胶囊装饰（3.26）：0=默认（不画）/ 1=雪花 / 2=星星 / 3=枫叶 / 5=云朵 / 6=闪电 / 7=烟花 / 8=气球 / 9=金鱼 / 10=可爱熊；只水滴画。4=雨水已删（3.30 存过雨水的迁到可爱熊）。 */
        const val DECORATION_NONE = 0
        const val DECORATION_SNOW = 1
        const val DECORATION_STAR = 2
        const val DECORATION_LEAF = 3
        const val DECORATION_CLOUD = 5
        const val DECORATION_LIGHTNING = 6
        const val DECORATION_FIREWORK = 7
        const val DECORATION_BALLOON = 8
        const val DECORATION_GOLDFISH = 9
        const val DECORATION_JOLLY = 10

        /** 1.14：星星 / 云朵 / 闪电 / 金鱼 / 可爱熊已删，只留默认 / 雪花 / 枫叶 / 烟花 / 气球；存过删掉的回到默认（不画）。 */
        fun normalizeDecoration(value: Int): Int = when (value) {
            DECORATION_SNOW, DECORATION_LEAF, DECORATION_FIREWORK, DECORATION_BALLOON -> value
            else -> DECORATION_NONE
        }

        /**
         * res 玻璃 chip 实测值（res_30_4wue.zip 二进制 XML + jackshell_push 源码 XML，
         * 3.25 水滴/清透按 res 重做）：
         * 未选中底 #383C73（res 原值 #78383C73，47% alpha；此处存不透明，
         * 透亮感由水滴渐变 stop 自带的 alpha 给）；未选中描边 #9B9FD0 24%；
         * 选中描边 #73DDF7 80%（源码版选中色，res 编译版 layer-list 的 #6B6F9F/#E6E6EA 不用）；
         * 清透描边 40% 白（面板那支 #66FFFFFF）。
         * 旧默认底 #6F74F2（3.16 长春花蓝紫）只作迁移比对：已存的旧默认一次迁到 res 底。
         */
        const val RES_CHIP_BASE_ARGB: Int = 0xFF383C73.toInt()
        const val OLD_DEFAULT_BASE_ARGB: Int = 0xFF6F74F2.toInt()
        // 3.30：旧默认选中暖金 #FFD34D，只作迁移比对：已存的旧默认一次迁到 0（跟主题主色）。
        const val OLD_DEFAULT_SELECTED_ARGB: Int = 0xFFFFD34D.toInt()
        const val RES_CHIP_STROKE_ARGB: Int = 0x3D9B9FD0.toInt()
        const val RES_CHIP_SELECTED_STROKE_ARGB: Int = 0xCC73DDF7.toInt()
        const val RES_LIGHT_STROKE_ARGB: Int = 0x66FFFFFF

    }
}

/**
 * 3.11 两大组（用户 2026-11-15 定，五组改两组；按钮就是胶囊，不另起叫法）：
 * 卡片 = 海报卡；胶囊 = 线路卡、选集卡、源胶囊、分类/筛选 chip、播放页胶囊。
 * 每组一份 [ControlTheme]，见 [ControlThemeStore.groupConfig]。
 * 3.11 第一版的五组 ControlKind（POSTER/CAPSULE/FILTER/LINE/PLAYBACK）已删，
 * control_kind_ 键从没交付过，不迁移。
 */
enum class ControlGroup {
    CARD,     // 卡片
    CAPSULE,  // 胶囊
}

/** 组的 KV 名（小写）：card / capsule。 */
fun ControlGroup.kvName(): String = when (this) {
    ControlGroup.CARD -> "card"
    ControlGroup.CAPSULE -> "capsule"
}

fun ControlGroup.displayName(): String = when (this) {
    ControlGroup.CARD -> "卡片"
    ControlGroup.CAPSULE -> "胶囊"
}

/** 组标题里的说明——该组管哪些元素。 */
fun ControlGroup.scopeDescription(): String = when (this) {
    ControlGroup.CARD -> "海报卡"
    ControlGroup.CAPSULE -> "线路卡、选集卡、源胶囊、分类/筛选 chip、播放页胶囊"
}

/**
 * 代码种类 → 用户组（3.11 两大组）：
 * POSTER / CARD → 卡片组；LINE / EPISODE / BUTTON / CHIP → 胶囊组
 * （线路卡、选集卡用户感觉也是胶囊，不再按卡片调；CHIP 不用再看
 * elementId 前缀分胶囊/筛选，全进胶囊组）。
 * elementId 参数保留（调用方签名不动），判定里用不上。
 */
fun resolveUserKind(kind: ControlElementKind, elementId: String): ControlGroup = when (kind) {
    ControlElementKind.POSTER, ControlElementKind.CARD -> ControlGroup.CARD
    else -> ControlGroup.CAPSULE
}

/**
 * 仅按 elementId 前缀判定代码种类（3.11 小编辑框入口用）：
 * poster_/poster_pos_ → POSTER；episode_ → EPISODE；line_ → LINE；
 * chip_/filterchip_ → CHIP；source_ → CHIP；card_ → CARD；
 * 无前缀的写死 buttonId（如 home_search、player_play_pause、
 * music_lyric_search、settings_preview）一律 BUTTON。
 */
fun resolveCodeKind(elementId: String): ControlElementKind = when {
    elementId.startsWith("poster_") || elementId.startsWith("poster_pos_") -> ControlElementKind.POSTER
    elementId.startsWith("episode_") -> ControlElementKind.EPISODE
    elementId.startsWith("line_") -> ControlElementKind.LINE
    elementId.startsWith("chip_") || elementId.startsWith("filterchip_") -> ControlElementKind.CHIP
    elementId.startsWith("source_") -> ControlElementKind.CHIP
    elementId.startsWith("card_") -> ControlElementKind.CARD
    else -> ControlElementKind.BUTTON
}

/**
 * 控件预设（3.13：只剩“默认”一个——大字版 / 简洁版 / 彩色版 / 胶囊版 /
 * 方正版五个已删，设置页的预设 UI 也一并删了；“恢复默认”与按钮兜底
 * 仍用 [DEFAULT]，所以它不能删。已删的 id 经 [byId] 一律落回 DEFAULT，
 * 旧导出文件与旧 KV 里的已删 id 不再拒绝、不再写回）。
 */
object ControlThemePresets {

    const val DEFAULT_ID = "default"

    val DEFAULT: ControlTheme = ControlTheme(
        presetId = "default",
        sizeScale = 0.5f,
        fontScale = 1.0f,
        shape = EffectsState.CARD_SHAPE_RECT,
        cornerMode = ControlTheme.CORNER_FOLLOW_THEME,
        cornerScale = 1.0f,
        reliefEnabled = false,
        reliefProfile = EffectsState.DEFAULT_RELIEF_PROFILE,
        reliefMagnitude = EffectsState.DEFAULT_CARD_ELEVATION_DP,
        curvature = EffectsState.DEFAULT_CURVATURE,
        curvatureEnabled = false,
        curvatureOutward = false,
        lightBrightness = EffectsState.DEFAULT_LIGHT_BRIGHTNESS,
        customColorArgb = 0,
        // 3.25：胶囊组默认底色 = res 未选中 chip 底 #383C73（3.16 的长春花蓝紫 #6F74F2 已废，
        // 见 OLD_DEFAULT_BASE_ARGB；老用户已存的 #6F74F2 由 loadGroups 一次迁到这里）。
        // 存不透明 ARGB，透亮感由水滴渐变 stop 自带的 alpha 给；0 = 跟随主题 slate 的旧义仍保留，
        // 但 resolveButtonStyle 会把胶囊组的 0 也落到 res 底（跟主题 slate 是灰紫，永远出不了参考图蓝紫）。
        baseColorArgb = ControlTheme.RES_CHIP_BASE_ARGB,
        // 3.30：胶囊组默认选中色 = 跟主题主色（暖金 #FFD34D 已废，见 OLD_DEFAULT_SELECTED_ARGB）。
        selectedContentColorArgb = 0,
    )

    val ALL: List<ControlTheme> = listOf(DEFAULT)

    /** 预设展示名（导出文件名用，与 id 一一对应）。 */
    val NAMES: Map<String, String> = mapOf(
        "default" to "默认",
    )

    /** 预设一句话说明（3.16：胶囊组默认底是水滴蓝紫；3.18：边框恒为跟主题，不再是大圆角）。 */
    val SUBTITLES: Map<String, String> = mapOf(
        "default" to "中号 · 胶囊水滴蓝紫底 · 主色强调",
    )

    /** 未知 / 已删的 id 一律落回 DEFAULT，永不抛错、不留 stale id。 */
    fun byId(id: String): ControlTheme = ALL.firstOrNull { it.presetId == id } ?: DEFAULT

    fun nameOf(id: String): String = NAMES[id] ?: "默认"

    fun subtitleOf(id: String): String = SUBTITLES[id] ?: ""
}

object ControlThemeStore {

    val SIZE_RANGE: ClosedFloatingPointRange<Float> = 0f..0.5f
    val FONT_SCALE_RANGE: ClosedFloatingPointRange<Float> = 0.8f..1.5f

    private var current by mutableStateOf(load())

    /**
     * 3.11 两组当前值：每组一份 ControlTheme（初始化时 [loadGroups] 从组 KV 读，
     * 没存过就按全局值迁一次并写进组 KV，见 loadGroups）。
     * 卡片组水光默认 40（低，别盖白海报照片）；胶囊组水光默认 120（= 现水胶囊强度）。
     */
    private var groupCurrent by mutableStateOf(loadGroups(current))

    /** 3.11 两组拖动预览（只改内存，不写 KV；提交时 [saveGroup] 按组清掉）。 */
    private var groupPreview by mutableStateOf<Map<ControlGroup, ControlTheme>>(emptyMap())

    /** 卡片组水光默认（0..200）：低，海报照片不盖白。 */
    const val DEFAULT_CARD_WATER_GLOSS = 40

    /** 胶囊组水光默认（0..400）：3.25 由 120 提到 400。 */
    const val DEFAULT_CAPSULE_WATER_GLOSS = 400

    /** 胶囊组边框形状默认（3.18：恒为跟主题；大圆角是 percent=50，倍率乘不上去，圆角倍率滑杆只对跟主题分支有意义）。 */
    const val DEFAULT_CAPSULE_CORNER_MODE: Int = ControlTheme.CORNER_FOLLOW_THEME

    /**
     * 拖动中的预览值（只改内存，不写 KV；松手提交时清掉，走已存值）。
     * 3.09 更正：随心所欲侧的滑杆拖动预览放这里（EffectsState 的预览已随搬家删掉）。
     * [save] 是全部提交的唯一漏斗，提交时清预览。
     */
    private var preview by mutableStateOf<ControlTheme?>(null)

    /**
     * 3.07 第二阶段 C-edit：控件主题之外的 KV 改动（隐藏 /
     * 顺序 / 对齐）也要即时生效。KV 本身不可观察，所以改动后 bump；
     * 下面所有新 getter 都先读它（组合期间订阅），写入方调 [bump]。
     * [resolveButtonStyle] 经 [isKindCovered] 订阅它，组值改完按钮即变。
     */
    private var uiRevision by mutableStateOf(0)

    private fun bump() {
        uiRevision++
    }

    /** 与 EffectsState.config 同款读法：ControlThemeStore.current（拖动中有预览值时读预览）。 */
    val config: ControlTheme get() = preview ?: current

    /** 3.11 某一组的实际主题：拖动中有该组预览读预览，否则读该组当前值。 */
    fun groupConfig(group: ControlGroup): ControlTheme =
        groupPreview[group] ?: groupCurrent[group] ?: current

    /** 3.11 两组当前值快照（导出 groups 节用；不含拖动预览）。 */
    fun groupThemes(): Map<ControlGroup, ControlTheme> = groupCurrent

    // —— 3.09 更正：随心所欲总开关（只留这一个；原主题开关已删） ——

    /** 总开关（默认开）。开 = 随心所欲生效；关 = 随心所欲完全不生效，恢复最初的 AVBox。 */
    fun isControlThemeEnabled(): Boolean {
        uiRevision
        return KV.get(HawkConfig.CONTROL_THEME_ENABLED, true)
    }

    fun setControlThemeEnabled(enabled: Boolean) {
        KV.put(HawkConfig.CONTROL_THEME_ENABLED, enabled)
        bump()
    }

    /** 原主题元素样式是否画——总开关关时画（原主题值兜底），开时不画（只留随心所欲那一层）。 */
    fun shouldDrawOriginalStyle(): Boolean {
        uiRevision
        return !isControlThemeEnabled()
    }

    private fun load(): ControlTheme {
        // 3.14 一次性迁移：单元素覆盖已废止——按索引删掉全部已存覆盖键 + 索引本身，
        // 否则个别胶囊/海报会悄悄不跟组变。只用 KV 读写，不碰 uiRevision
        // （load() 在对象初始化时跑，uiRevision 字段还没初始化）。
        // KV 不能枚举键，索引是唯一枚举路；索引之外的残留键再也没人读写，无害。
        val staleOverrideIds = parseIdList(KV.get(HawkConfig.CONTROL_BTN_OVERRIDE_INDEX, ""))
        if (staleOverrideIds.isNotEmpty()) {
            for (id in staleOverrideIds) {
                for (suffix in listOf(
                    "size", "shape", "color", "reliefEnabled", "reliefProfile",
                    "reliefMagnitude", "curvature", "curvatureEnabled", "curvatureOutward",
                    "lightBrightness", "waterGloss", "fontColorArgb", "cornerMode",
                )) {
                    KV.delete(HawkConfig.CONTROL_BTN_OVERRIDE_PREFIX + id + "_" + suffix)
                }
            }
            KV.delete(HawkConfig.CONTROL_BTN_OVERRIDE_INDEX)
        }
        val presetId = KV.get(HawkConfig.CONTROL_THEME_PRESET, ControlThemePresets.DEFAULT_ID)
        val base = ControlThemePresets.byId(presetId)
        // 3.13：KV 里还存着已删预设的 id 时，byId 已落回 DEFAULT；顺手把 KV 写回
        // "default"，不留 stale id（下次 save 之前也不会再读到已删 id）。
        if (presetId != base.presetId) KV.put(HawkConfig.CONTROL_THEME_PRESET, base.presetId)
        // 覆盖范围：没存过（老版本升级）跟预设走（全七种）；存过则按存的值走，
        // 存了空串 = 用户主动全不勾 = 空集（全部元素回落默认主题）。
        // 3.62：PLAYER 是新加的种类，老版本存的值里没有它。只迁一次：没迁过且存的
        // 值非空时把 PLAYER 补进去（老用户播放器胶囊不变）；存了空串不补。
        val coveredKinds = if (KV.contains(HawkConfig.CONTROL_THEME_COVERED_KINDS)) {
            val parsed = parseCoveredKinds(KV.get(HawkConfig.CONTROL_THEME_COVERED_KINDS, ""))
            if (!KV.contains(HawkConfig.CONTROL_THEME_PLAYER_SCOPE_MIGRATED)) {
                KV.put(HawkConfig.CONTROL_THEME_PLAYER_SCOPE_MIGRATED, true)
                if (parsed.isNotEmpty()) {
                    (parsed + ControlElementKind.PLAYER).also {
                        KV.put(HawkConfig.CONTROL_THEME_COVERED_KINDS, coveredKindsToString(it))
                    }
                } else parsed
            } else parsed
        } else {
            base.coveredKinds
        }
        return ControlTheme(
            presetId = base.presetId,
            // 1.15：大小滑杆已删，统一 0.5 默认；存过的值不再读。
            sizeScale = 0.5f,
            fontScale = KV.get(HawkConfig.CONTROL_THEME_FONT_SCALE, base.fontScale)
                .coerceIn(FONT_SCALE_RANGE),
            shape = KV.get(HawkConfig.CONTROL_THEME_SHAPE, base.shape)
                .let { if (it in EffectsState.CARD_SHAPE_OPTIONS) it else EffectsState.CARD_SHAPE_RECT },
            cornerMode = KV.get(HawkConfig.CONTROL_THEME_CORNER, base.cornerMode)
                .coerceIn(ControlTheme.CORNER_FOLLOW_THEME, ControlTheme.CORNER_PILL),
            // 3.09 全收归一：新键优先；新键没存过且旧键有值 → 读旧值兜底一次并写进新键（只迁一次，
            // 旧键保留不删）；两边都没存过 → 跟预设默认值（= 原主题默认）。开关值同样迁移。
            cornerScale = migrateFloat(
                HawkConfig.CONTROL_CORNER_SCALE, HawkConfig.FX_CORNER_SCALE, base.cornerScale,
            ).coerceIn(EffectsState.CORNER_RANGE),
            reliefEnabled = migrateBoolean(
                HawkConfig.CONTROL_RELIEF_ENABLED, HawkConfig.FX_RELIEF_ENABLED, base.reliefEnabled,
            ),
            reliefProfile = migrateReliefProfile(
                HawkConfig.CONTROL_RELIEF_PROFILE, HawkConfig.FX_RELIEF_PROFILE, base.reliefProfile,
            ),
            reliefMagnitude = migrateFloat(
                HawkConfig.CONTROL_RELIEF_MAGNITUDE, HawkConfig.FX_CARD_ELEVATION, base.reliefMagnitude,
            ).coerceIn(EffectsState.ELEVATION_RANGE),
            curvature = migrateInt(
                HawkConfig.CONTROL_CURVATURE, HawkConfig.FX_SPATIAL_CURVATURE, base.curvature,
            ).coerceIn(0, 200),
            curvatureEnabled = migrateBoolean(
                HawkConfig.CONTROL_CURVATURE_ENABLED, HawkConfig.FX_CURVATURE_ENABLED, base.curvatureEnabled,
            ),
            curvatureOutward = migrateBoolean(
                HawkConfig.CONTROL_CURVATURE_OUTWARD, HawkConfig.FX_CURVATURE_OUTWARD, base.curvatureOutward,
            ),
            lightBrightness = migrateInt(
                HawkConfig.CONTROL_LIGHT_BRIGHTNESS, HawkConfig.FX_LIGHT_BRIGHTNESS, base.lightBrightness,
            ).coerceIn(0, 200),
            // 3.11 新字段：无旧键可迁，直接读新键、没有跟预设默认（水光 400、字色 0=跟主题）。
            waterGloss = KV.get(HawkConfig.CONTROL_WATER_GLOSS, base.waterGloss).coerceIn(0, 400),
            fontColorArgb = KV.get(HawkConfig.CONTROL_FONT_COLOR, base.fontColorArgb),
            customColorArgb = KV.get(HawkConfig.CONTROL_THEME_CUSTOM_COLOR, base.customColorArgb),
            // 3.16 新字段：无旧键可迁，直接读新键、没有跟预设默认（参考图水滴蓝紫）。
            baseColorArgb = KV.get(HawkConfig.CONTROL_BASE_COLOR, base.baseColorArgb),
            // 3.30：选中色默认跟主题主色；已存的旧默认暖金 #FFD34D 一次迁到 0（跟主题），真正自定义过的选中色不动。
            selectedContentColorArgb = KV.get(HawkConfig.CONTROL_SELECTED_COLOR, base.selectedContentColorArgb).let {
                if (it == ControlTheme.OLD_DEFAULT_SELECTED_ARGB) {
                    KV.put(HawkConfig.CONTROL_SELECTED_COLOR, 0)
                    0
                } else it
            },
            // 3.19 新字段：无旧键可迁，直接读新键、没有跟预设默认（0 = 跟随主题强调色）。
            borderColorArgb = KV.get(HawkConfig.CONTROL_BORDER_COLOR, base.borderColorArgb),
            // 3.18 新字段：无旧键可迁，直接读新键、没有跟预设默认（水滴）；非法值归一到水滴。
            capsuleStyle = ControlTheme.normalizeCapsuleStyle(KV.get(HawkConfig.CONTROL_CAPSULE_STYLE, base.capsuleStyle)),
            coveredKinds = coveredKinds,
        )
    }

    // 3.11 两组值的组 KV 键：control_group_<组名>_<字段后缀>，字段后缀与全局键一致
    // （size / font_scale / shape / corner / corner_scale / relief_enabled /
    // relief_profile / relief_magnitude / curvature / curvature_enabled /
    // curvature_outward / light_brightness / water_gloss / font_color /
    // custom_color / base_color / selected_color / border_color / capsule_style）。
    // 不把 2×19 个键全写成常量，仿 CONTROL_BTN_OVERRIDE_PREFIX 的拼法。
    private fun groupKey(group: ControlGroup, suffix: String): String =
        HawkConfig.CONTROL_GROUP_PREFIX + group.kvName() + "_" + suffix

    /**
     * 3.11 两组值加载：全局主题的读法（[load]，含 3.09 迁移）一字不动；
     * 全局读完后，对每组、每个字段：组 KV 键存在 → 按全局同款钳制 / 校验读；
     * 不存在 → 取全局主题对应字段的值，写进组 KV 键一次（只迁一次）。
     * 例外：卡片组水光不取全局值，取 [DEFAULT_CARD_WATER_GLOSS]（40，低），
     * 否则卡片组一上来就是 120，会把海报照片盖白。旧键（control_theme_* 与 fx_*）永不删除。
     * 升级后两组值与原来全局值一模一样（除卡片组水光），视觉不变。
     */
    private fun loadGroups(global: ControlTheme): Map<ControlGroup, ControlTheme> {
        val out = LinkedHashMap<ControlGroup, ControlTheme>()
        for (group in ControlGroup.values()) {
            var theme = global
            theme = theme.copy(
                // 1.15：大小滑杆已删，统一 0.5 默认；存过的值不再读。
                sizeScale = 0.5f,
                fontScale = migrateGroupFloat(group, "font_scale", global.fontScale)
                    .coerceIn(FONT_SCALE_RANGE),
                shape = migrateGroupInt(group, "shape", global.shape)
                    .let { if (it in EffectsState.CARD_SHAPE_OPTIONS) it else EffectsState.CARD_SHAPE_RECT },
                cornerMode = (
                    // 3.18：胶囊组边框形状恒为跟主题（圆角倍率滑杆只对跟主题分支有意义；
                    // 大圆角是 percent=50，倍率乘不上去）——每次加载都强制写回组 KV，
                    // 已存的直角/大圆角一律迁回 0，以后也不得再变回；卡片组跟全局。
                    if (group == ControlGroup.CAPSULE) {
                        KV.put(groupKey(group, "corner"), ControlTheme.CORNER_FOLLOW_THEME)
                        ControlTheme.CORNER_FOLLOW_THEME
                    } else {
                        migrateGroupInt(group, "corner", global.cornerMode)
                    }
                )
                    .coerceIn(ControlTheme.CORNER_FOLLOW_THEME, ControlTheme.CORNER_PILL),
                cornerScale = migrateGroupFloat(group, "corner_scale", global.cornerScale)
                    .coerceIn(EffectsState.CORNER_RANGE),
                reliefEnabled = migrateGroupBoolean(group, "relief_enabled", global.reliefEnabled),
                reliefProfile = migrateGroupReliefProfile(group, "relief_profile", global.reliefProfile),
                reliefMagnitude = migrateGroupFloat(group, "relief_magnitude", global.reliefMagnitude)
                    .coerceIn(EffectsState.ELEVATION_RANGE),
                curvature = migrateGroupInt(group, "curvature", global.curvature)
                    .coerceIn(0, 200),
                curvatureEnabled = migrateGroupBoolean(group, "curvature_enabled", global.curvatureEnabled),
                curvatureOutward = migrateGroupBoolean(group, "curvature_outward", global.curvatureOutward),
                lightBrightness = migrateGroupInt(group, "light_brightness", global.lightBrightness)
                    .coerceIn(0, 200),
                waterGloss = migrateGroupInt(
                    group, "water_gloss",
                    if (group == ControlGroup.CARD) DEFAULT_CARD_WATER_GLOSS else global.waterGloss,
                ).coerceIn(0, 400).let {
                    // 3.25：胶囊组旧默认 120 一次迁到 400（只迁 120 这一档，手拖过别的数不动；卡片组 40 不动）。
                    if (group == ControlGroup.CAPSULE && it == 120) {
                        KV.put(groupKey(group, "water_gloss"), 400)
                        400
                    } else it
                },
                fontColorArgb = migrateGroupInt(group, "font_color", global.fontColorArgb),
                customColorArgb = migrateGroupInt(group, "custom_color", global.customColorArgb),
                // 3.16 底色：卡片组不读它， fallback 写 0（跟主题 slate）；胶囊组跟全局
                // （默认 = res 未选中底 #383C73），新键没存过时一次迁入，老用户胶囊底自动变蓝紫。
                baseColorArgb = migrateGroupInt(
                    group, "base_color",
                    if (group == ControlGroup.CARD) 0 else global.baseColorArgb,
                ).let {
                    // 3.25：胶囊组旧默认底 #6F74F2（3.16 长春花蓝紫）一次迁到 res 底 #383C73；
                    // 只迁旧默认这一档，真正自定义过的底色不动；卡片组 0（跟主题）不动。
                    if (group == ControlGroup.CAPSULE && it == ControlTheme.OLD_DEFAULT_BASE_ARGB) {
                        KV.put(groupKey(group, "base_color"), ControlTheme.RES_CHIP_BASE_ARGB)
                        ControlTheme.RES_CHIP_BASE_ARGB
                    } else it
                },
                // 3.30 选中颜色：卡片组不读它，fallback 写 0（跟主题）；胶囊组跟全局
                // （默认 0 = 跟主题主色），已存的旧默认暖金 #FFD34D 一次迁到 0，真正自定义过的选中色不动。
                selectedContentColorArgb = migrateGroupInt(
                    group, "selected_color",
                    if (group == ControlGroup.CARD) 0 else global.selectedContentColorArgb,
                ).let {
                    if (group == ControlGroup.CAPSULE && it == ControlTheme.OLD_DEFAULT_SELECTED_ARGB) {
                        KV.put(groupKey(group, "selected_color"), 0)
                        0
                    } else it
                },
                // 3.19 描边颜色：卡片组不读它，fallback 写 0（跟主题强调色）；胶囊组跟全局
                // （默认 0 = 跟随主题强调色），新键没存过时一次迁入。
                borderColorArgb = migrateGroupInt(
                    group, "border_color",
                    if (group == ControlGroup.CARD) 0 else global.borderColorArgb,
                ),
                // 3.18 胶囊风格：卡片组不读它，fallback 写水滴；胶囊组跟全局
                // （默认水滴），新键没存过时一次迁入。
                // 读取并保留用户选择。旧版每次启动把 water 强制写成 raised，
                // 导致“水滴”切回去又变纽扣；这里不再做任何风格强制迁移。
                capsuleStyle = ControlTheme.normalizeCapsuleStyle(
                    migrateGroupString(
                        group, "capsule_style",
                        if (group == ControlGroup.CARD) ControlTheme.CAPSULE_STYLE_WATER else global.capsuleStyle,
                    ),
                ),
            )
            // 3.16 的 pill 一次性迁移已删（3.18：胶囊组边框形状恒为跟主题，见上面 cornerMode 强制分支）。
            out[group] = theme
        }
        return out
    }

    // 3.11 组 KV 迁移：组键存在 → 读；不存在 → 取全局对应字段值写进组键一次并返回
    // （只迁一次；组键没有旧 fx_ 键兜底，旧键保留不删）。
    private fun migrateGroupFloat(group: ControlGroup, suffix: String, fallback: Float): Float {
        val key = groupKey(group, suffix)
        if (KV.contains(key)) return KV.get(key, fallback)
        KV.put(key, fallback)
        return fallback
    }

    private fun migrateGroupInt(group: ControlGroup, suffix: String, fallback: Int): Int {
        val key = groupKey(group, suffix)
        if (KV.contains(key)) return KV.get(key, fallback)
        KV.put(key, fallback)
        return fallback
    }

    private fun migrateGroupBoolean(group: ControlGroup, suffix: String, fallback: Boolean): Boolean {
        val key = groupKey(group, suffix)
        if (KV.contains(key)) return KV.get(key, fallback)
        KV.put(key, fallback)
        return fallback
    }

    private fun migrateGroupString(group: ControlGroup, suffix: String, fallback: String): String {
        val key = groupKey(group, suffix)
        if (KV.contains(key)) return KV.get(key, fallback)
        KV.put(key, fallback)
        return fallback
    }

    /** 组 KV 的浮雕 profile 迁移：与全局 [migrateReliefProfile] 同口径（已下线的 3/4/5/6 落回球面弧形），再钳到 0..2。 */
    private fun migrateGroupReliefProfile(group: ControlGroup, suffix: String, fallback: Int): Int {
        fun normalize(stored: Int): Int = when (stored) {
            6, EffectsState.RELIEF_PROFILE_COCOA, EffectsState.RELIEF_PROFILE_CYBER,
            EffectsState.RELIEF_PROFILE_PORCELAIN -> EffectsState.RELIEF_PROFILE_DOME
            else -> stored.coerceIn(EffectsState.RELIEF_PROFILE_FLAT, EffectsState.RELIEF_PROFILE_FACETED)
        }
        val key = groupKey(group, suffix)
        if (KV.contains(key)) return normalize(KV.get(key, fallback))
        val migrated = normalize(fallback)
        KV.put(key, migrated)
        return migrated
    }

    // 3.09 旧值迁移（新键优先 → 旧键兜底一次并写进新键 → 预设默认；旧键保留不删）。
    // 注意：浮雕滑杆旧值正常已是 0..200 量级（logtest42d 起的一次性旧量纲迁移早已跑过）；
    // 若某台极老机器从未跑过那次迁移，这里读到的仍是旧量纲值并按原样钳到 0..200。
    private fun migrateFloat(newKey: String, oldKey: String, fallback: Float): Float {
        if (KV.contains(newKey)) return KV.get(newKey, fallback)
        if (KV.contains(oldKey)) {
            val migrated = KV.get(oldKey, fallback)
            KV.put(newKey, migrated)
            return migrated
        }
        return fallback
    }

    private fun migrateInt(newKey: String, oldKey: String, fallback: Int): Int {
        if (KV.contains(newKey)) return KV.get(newKey, fallback)
        if (KV.contains(oldKey)) {
            val migrated = KV.get(oldKey, fallback)
            KV.put(newKey, migrated)
            return migrated
        }
        return fallback
    }

    private fun migrateBoolean(newKey: String, oldKey: String, fallback: Boolean): Boolean {
        if (KV.contains(newKey)) return KV.get(newKey, fallback)
        if (KV.contains(oldKey)) {
            val migrated = KV.get(oldKey, fallback)
            KV.put(newKey, migrated)
            return migrated
        }
        return fallback
    }

    /** 浮雕 profile 迁移：与 EffectsState 同口径（已下线的 3/4/5/6 落回球面弧形），再钳到 0..2。 */
    private fun migrateReliefProfile(newKey: String, oldKey: String, fallback: Int): Int {
        fun normalize(stored: Int): Int = when (stored) {
            6, EffectsState.RELIEF_PROFILE_COCOA, EffectsState.RELIEF_PROFILE_CYBER,
            EffectsState.RELIEF_PROFILE_PORCELAIN -> EffectsState.RELIEF_PROFILE_DOME
            else -> stored.coerceIn(EffectsState.RELIEF_PROFILE_FLAT, EffectsState.RELIEF_PROFILE_FACETED)
        }
        if (KV.contains(newKey)) return normalize(KV.get(newKey, fallback))
        if (KV.contains(oldKey)) {
            val migrated = normalize(KV.get(oldKey, fallback))
            KV.put(newKey, migrated)
            return migrated
        }
        return fallback
    }

    /** 逗号分隔的 id 串 → 列表；空白项跳过（隐藏集 / 重排 / 3.14 覆盖迁移共用）。 */
    private fun parseIdList(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** 逗号分隔的种类名（button,card,…）→ 集合；认不出的名字跳过。 */
    private fun parseCoveredKinds(raw: String): Set<ControlElementKind> {
        if (raw.isBlank()) return emptySet()
        val out = LinkedHashSet<ControlElementKind>()
        for (part in raw.split(",")) {
            ControlThemeIo.kindFromJsonName(part)?.let { out.add(it) }
        }
        return out
    }

    private fun coveredKindsToString(kinds: Set<ControlElementKind>): String =
        kinds.sortedBy { it.ordinal }.joinToString(",") { ControlThemeIo.kindJsonName(it) }

    private fun save(theme: ControlTheme) {
        preview = null
        KV.put(HawkConfig.CONTROL_THEME_PRESET, theme.presetId)
        KV.put(HawkConfig.CONTROL_THEME_SIZE, theme.sizeScale)
        KV.put(HawkConfig.CONTROL_THEME_FONT_SCALE, theme.fontScale)
        KV.put(HawkConfig.CONTROL_THEME_SHAPE, theme.shape)
        KV.put(HawkConfig.CONTROL_THEME_CORNER, theme.cornerMode)
        KV.put(HawkConfig.CONTROL_CORNER_SCALE, theme.cornerScale)
        KV.put(HawkConfig.CONTROL_RELIEF_ENABLED, theme.reliefEnabled)
        KV.put(HawkConfig.CONTROL_RELIEF_PROFILE, theme.reliefProfile)
        KV.put(HawkConfig.CONTROL_RELIEF_MAGNITUDE, theme.reliefMagnitude)
        KV.put(HawkConfig.CONTROL_CURVATURE, theme.curvature)
        KV.put(HawkConfig.CONTROL_CURVATURE_ENABLED, theme.curvatureEnabled)
        KV.put(HawkConfig.CONTROL_CURVATURE_OUTWARD, theme.curvatureOutward)
        KV.put(HawkConfig.CONTROL_LIGHT_BRIGHTNESS, theme.lightBrightness)
        KV.put(HawkConfig.CONTROL_WATER_GLOSS, theme.waterGloss)
        KV.put(HawkConfig.CONTROL_FONT_COLOR, theme.fontColorArgb)
        KV.put(HawkConfig.CONTROL_THEME_CUSTOM_COLOR, theme.customColorArgb)
        KV.put(HawkConfig.CONTROL_BASE_COLOR, theme.baseColorArgb)
        KV.put(HawkConfig.CONTROL_SELECTED_COLOR, theme.selectedContentColorArgb)
        KV.put(HawkConfig.CONTROL_BORDER_COLOR, theme.borderColorArgb)
        KV.put(HawkConfig.CONTROL_CAPSULE_STYLE, theme.capsuleStyle)
        KV.put(HawkConfig.CONTROL_THEME_COVERED_KINDS, coveredKindsToString(theme.coveredKinds))
        current = theme
    }

    /** 3.11 两组值的唯一写路（仿 [save]）：写该组 20 个字段进组 KV 键 + 更新 state + 清掉该组预览。 */
    private fun saveGroup(group: ControlGroup, theme: ControlTheme) {
        KV.put(groupKey(group, "size"), theme.sizeScale)
        KV.put(groupKey(group, "font_scale"), theme.fontScale)
        KV.put(groupKey(group, "shape"), theme.shape)
        KV.put(groupKey(group, "corner"), theme.cornerMode)
        KV.put(groupKey(group, "corner_scale"), theme.cornerScale)
        KV.put(groupKey(group, "relief_enabled"), theme.reliefEnabled)
        KV.put(groupKey(group, "relief_profile"), theme.reliefProfile)
        KV.put(groupKey(group, "relief_magnitude"), theme.reliefMagnitude)
        KV.put(groupKey(group, "curvature"), theme.curvature)
        KV.put(groupKey(group, "curvature_enabled"), theme.curvatureEnabled)
        KV.put(groupKey(group, "curvature_outward"), theme.curvatureOutward)
        KV.put(groupKey(group, "light_brightness"), theme.lightBrightness)
        KV.put(groupKey(group, "water_gloss"), theme.waterGloss)
        KV.put(groupKey(group, "font_color"), theme.fontColorArgb)
        KV.put(groupKey(group, "custom_color"), theme.customColorArgb)
        KV.put(groupKey(group, "base_color"), theme.baseColorArgb)
        KV.put(groupKey(group, "selected_color"), theme.selectedContentColorArgb)
        KV.put(groupKey(group, "border_color"), theme.borderColorArgb)
        KV.put(groupKey(group, "capsule_style"), theme.capsuleStyle)
        groupCurrent = groupCurrent + (group to theme)
        groupPreview = groupPreview - group
    }

    /** 3.11 该组当前值（内部用；缺失时回落全局主题，不会空）。 */
    private fun groupTheme(group: ControlGroup): ControlTheme = groupCurrent[group] ?: current

    /** 3.11 直接存一组的整套值（导入 groups 节用；仍走 KV + state）。 */
    fun setGroupTheme(group: ControlGroup, theme: ControlTheme) {
        saveGroup(group, theme)
    }

    /** 恢复默认（默认预设全量值，含覆盖范围回全七种）。 */
    fun reset() {
        save(ControlThemePresets.DEFAULT)
        // 3.11 全局 + 两组全部回到 DEFAULT；卡片组水光另写 40、底色另写 0（跟主题 slate）、
        // 选中颜色另写 0（跟主题）、描边颜色另写 0（跟主题强调色）；胶囊组边框形状另写跟主题
        //（3.18 恒为跟主题），底色与选中颜色跟 DEFAULT（参考图水滴蓝紫与选中金字）。
        // saveGroup 按组清预览，循环结束后再清一次兜底。
        saveGroup(ControlGroup.CARD, ControlThemePresets.DEFAULT.copy(waterGloss = DEFAULT_CARD_WATER_GLOSS, baseColorArgb = 0, selectedContentColorArgb = 0, borderColorArgb = 0))
        saveGroup(ControlGroup.CAPSULE, ControlThemePresets.DEFAULT.copy(cornerMode = DEFAULT_CAPSULE_CORNER_MODE))
        groupPreview = emptyMap()
    }

    /** 改覆盖范围：勾选的元素用当前主题，没勾选的回落默认主题。 */
    fun setCoveredKinds(kinds: Set<ControlElementKind>) {
        save(current.copy(coveredKinds = kinds, presetId = current.presetId))
    }

    /** 该种类的元素是否套当前主题（读 uiRevision：覆盖改动也触发重组）。 */
    fun isKindCovered(kind: ControlElementKind): Boolean {
        uiRevision
        return kind in current.coveredKinds
    }

    /** 该种类实际用的主题：在范围里 = 当前主题，不在 = 默认预设（回落规则）。 */
    fun effectiveThemeFor(kind: ControlElementKind): ControlTheme =
        if (kind in current.coveredKinds) current else ControlThemePresets.DEFAULT

    // —— 3.09 全收归一：原主题 5 项元素设置的新写路（唯一写路；旧 EffectsState.setXxx 转调这里） ——
    // 3.08 双开关已删：控件主题就是唯一的、不带开关的元素样式层。

    /** 圆角倍率（浮点，0..2）：与 cornerMode（模式）并存，互不覆盖。 */
    fun setCornerScale(value: Float) {
        val coerced = value.coerceIn(EffectsState.CORNER_RANGE)
        save(current.copy(cornerScale = coerced, presetId = current.presetId))
    }

    /** 浮雕总开关：只写开关位，不动滑杆值与 profile；关时渲染归零（reliefMagnitude 门控），开即恢复。 */
    fun setReliefEnabled(enabled: Boolean) {
        save(current.copy(reliefEnabled = enabled, presetId = current.presetId))
    }

    /** 浮雕 profile（0 平面切面 / 1 球面弧形 / 2 几何刻面；已下线的 3/4/5/6 落回球面弧形）。 */
    fun setReliefProfile(value: Int) {
        val profile = when (value) {
            EffectsState.RELIEF_PROFILE_COCOA, EffectsState.RELIEF_PROFILE_CYBER,
            EffectsState.RELIEF_PROFILE_PORCELAIN -> EffectsState.RELIEF_PROFILE_DOME
            else -> value.coerceIn(EffectsState.RELIEF_PROFILE_FLAT, EffectsState.RELIEF_PROFILE_FACETED)
        }
        save(current.copy(reliefProfile = profile, presetId = current.presetId))
    }

    /** 浮雕滑杆值（0..200）。 */
    fun setReliefMagnitude(value: Float) {
        val coerced = value.coerceIn(EffectsState.ELEVATION_RANGE)
        save(current.copy(reliefMagnitude = coerced, presetId = current.presetId))
    }

    /** 曲面滑杆值（0..200%）。 */
    fun setCurvature(value: Int) {
        val level = value.toFloat().coerceIn(EffectsState.CURVATURE_RANGE).toInt()
        save(current.copy(curvature = level, presetId = current.presetId))
    }

    /** 曲面总开关：只写开关位，不动滑杆值与方向；关时渲染归零（curvatureMagnitude 门控），开即恢复。 */
    fun setCurvatureEnabled(enabled: Boolean) {
        save(current.copy(curvatureEnabled = enabled, presetId = current.presetId))
    }

    /** 曲率方向：开 = 往外折，关 = 往里折（默认）；只写方向位。 */
    fun setCurvatureOutward(enabled: Boolean) {
        save(current.copy(curvatureOutward = enabled, presetId = current.presetId))
    }

    /** 光感亮度（0..200，默认 140；渲染乘子 = 值 / 80）。 */
    fun setLightBrightness(value: Int) {
        val level = value.coerceIn(0, 200)
        save(current.copy(lightBrightness = level, presetId = current.presetId))
    }

    // —— 滑杆拖动预览（只改内存，不写 KV；松手提交时 save() 清掉） ——

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setCornerScalePreview(value: Float) {
        preview = (preview ?: current).copy(cornerScale = value.coerceIn(EffectsState.CORNER_RANGE))
    }

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setReliefMagnitudePreview(value: Float) {
        preview = (preview ?: current).copy(reliefMagnitude = value.coerceIn(EffectsState.ELEVATION_RANGE))
    }

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setCurvaturePreview(value: Float) {
        preview = (preview ?: current).copy(curvature = value.coerceIn(EffectsState.CURVATURE_RANGE).toInt())
    }

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setLightBrightnessPreview(value: Float) {
        preview = (preview ?: current).copy(lightBrightness = value.coerceIn(EffectsState.LIGHT_BRIGHTNESS_RANGE).toInt())
    }

    fun setSizeScale(value: Float) {
        val coerced = value.coerceIn(SIZE_RANGE)
        save(current.copy(sizeScale = coerced, presetId = current.presetId))
    }

    fun setFontScale(value: Float) {
        val coerced = value.coerceIn(FONT_SCALE_RANGE)
        save(current.copy(fontScale = coerced, presetId = current.presetId))
    }

    fun setShape(value: Int) {
        val shape = if (value in EffectsState.CARD_SHAPE_OPTIONS) value else EffectsState.CARD_SHAPE_RECT
        save(current.copy(shape = shape, presetId = current.presetId))
    }

    fun setCornerMode(value: Int) {
        val mode = value.coerceIn(ControlTheme.CORNER_FOLLOW_THEME, ControlTheme.CORNER_PILL)
        save(current.copy(cornerMode = mode, presetId = current.presetId))
    }

    fun setCustomColorArgb(argb: Int) {
        save(current.copy(customColorArgb = argb, presetId = current.presetId))
    }

    /** 手动改任一字段后 presetId 不再对应整套预设时，调用方可用此法直接存一整套（仍走 KV + state）。 */
    fun setTheme(theme: ControlTheme) {
        save(theme)
    }

    // —— 3.11 两组值 setter（钳制口径与全局同款 setter 一致；提交时 saveGroup 按组清预览） ——
    // 滑杆范围：浮雕 / 曲面 / 光感 / 水光一律 0..200（EffectsState.ELEVATION_RANGE /
    // CURVATURE_RANGE / LIGHT_BRIGHTNESS_RANGE / WATER_GLOSS_RANGE），不用 0..300。
    // 字号 / 圆角倍率没有组 setter（两组都不给这两项，见设置页分组；值仍随预设 / 迁移进组，
    // 渲染照读组值）。
    // 边框形状（胶囊组）走 cornerMode：EPISODE / LINE / CHIP 的边框只认 cornerMode
    // （见 ControlSurface resolveSurfaceShape，shape 的多边形会裁掉文字），
    // 所以胶囊的“边框形状”是 cornerMode；3.18 起胶囊组 cornerMode 恒为跟主题，
    // 圆角大小由组 cornerScale（圆角倍率滑杆）乘 12dp 定。

    fun setGroupSizeScale(group: ControlGroup, value: Float) {
        val coerced = value.coerceIn(SIZE_RANGE)
        saveGroup(group, groupTheme(group).copy(sizeScale = coerced))
    }

    /** 圆角倍率（浮点，0..2）：只写组 KV "corner_scale"，走 saveGroup。 */
    fun setGroupCornerScale(group: ControlGroup, value: Float) {
        val coerced = value.coerceIn(EffectsState.CORNER_RANGE)
        saveGroup(group, groupTheme(group).copy(cornerScale = coerced))
    }

    fun setGroupShape(group: ControlGroup, value: Int) {
        val shape = if (value in EffectsState.CARD_SHAPE_OPTIONS) value else EffectsState.CARD_SHAPE_RECT
        saveGroup(group, groupTheme(group).copy(shape = shape))
    }

    fun setGroupCornerMode(group: ControlGroup, value: Int) {
        val mode = value.coerceIn(ControlTheme.CORNER_FOLLOW_THEME, ControlTheme.CORNER_PILL)
        saveGroup(group, groupTheme(group).copy(cornerMode = mode))
    }

    fun setGroupReliefEnabled(group: ControlGroup, enabled: Boolean) {
        saveGroup(group, groupTheme(group).copy(reliefEnabled = enabled))
    }

    fun setGroupReliefProfile(group: ControlGroup, value: Int) {
        val profile = when (value) {
            EffectsState.RELIEF_PROFILE_COCOA, EffectsState.RELIEF_PROFILE_CYBER,
            EffectsState.RELIEF_PROFILE_PORCELAIN -> EffectsState.RELIEF_PROFILE_DOME
            else -> value.coerceIn(EffectsState.RELIEF_PROFILE_FLAT, EffectsState.RELIEF_PROFILE_FACETED)
        }
        saveGroup(group, groupTheme(group).copy(reliefProfile = profile))
    }

    fun setGroupReliefMagnitude(group: ControlGroup, value: Float) {
        val coerced = value.coerceIn(EffectsState.ELEVATION_RANGE)
        saveGroup(group, groupTheme(group).copy(reliefMagnitude = coerced))
    }

    fun setGroupCurvature(group: ControlGroup, value: Int) {
        val level = value.toFloat().coerceIn(EffectsState.CURVATURE_RANGE).toInt()
        saveGroup(group, groupTheme(group).copy(curvature = level))
    }

    fun setGroupCurvatureEnabled(group: ControlGroup, enabled: Boolean) {
        saveGroup(group, groupTheme(group).copy(curvatureEnabled = enabled))
    }

    fun setGroupCurvatureOutward(group: ControlGroup, enabled: Boolean) {
        saveGroup(group, groupTheme(group).copy(curvatureOutward = enabled))
    }

    fun setGroupLightBrightness(group: ControlGroup, value: Int) {
        val level = value.coerceIn(0, 200)
        saveGroup(group, groupTheme(group).copy(lightBrightness = level))
    }

    /** 水光强度（0..400）。 */
    fun setGroupWaterGloss(group: ControlGroup, value: Int) {
        val level = value.coerceIn(0, 400)
        saveGroup(group, groupTheme(group).copy(waterGloss = level))
    }

    /** 字体颜色（0 = 跟主题白字；非 0 = 自定义字色 ARGB）。 */
    fun setGroupFontColorArgb(group: ControlGroup, argb: Int) {
        saveGroup(group, groupTheme(group).copy(fontColorArgb = argb))
    }

    fun setGroupCustomColorArgb(group: ControlGroup, argb: Int) {
        saveGroup(group, groupTheme(group).copy(customColorArgb = argb))
    }

    /** 底色（0 = 跟随主题 slate；非 0 = 自定义底色 ARGB，只对胶囊组生效）。 */
    fun setGroupBaseColorArgb(group: ControlGroup, argb: Int) {
        saveGroup(group, groupTheme(group).copy(baseColorArgb = argb))
    }

    /** 选中颜色（0 = 跟随主题主色；非 0 = 自定义选中字色 ARGB，只对胶囊组生效）。 */
    fun setGroupSelectedContentColorArgb(group: ControlGroup, argb: Int) {
        saveGroup(group, groupTheme(group).copy(selectedContentColorArgb = argb))
    }

    /** 描边颜色（0 = 跟随主题强调色；非 0 = 自定义描边颜色 ARGB，只对胶囊组生效）。 */
    fun setGroupBorderColorArgb(group: ControlGroup, argb: Int) {
        saveGroup(group, groupTheme(group).copy(borderColorArgb = argb))
    }

    /** 胶囊风格（"water" = 水滴 / "light" = 轻透 / "raised" = 立体；非法值归一到水滴）。 */
    fun setGroupCapsuleStyle(group: ControlGroup, value: String) {
        saveGroup(group, groupTheme(group).copy(capsuleStyle = ControlTheme.normalizeCapsuleStyle(value)))
    }

    /**
     * 胶囊装饰（3.26，全局单键；默认不画）。读时先读 uiRevision（组合期间订阅），改完 bump 即时生效。
     * 只水滴画（轻透不画）；选「没有」不画。
     */
    fun capsuleDecoration(): Int {
        uiRevision
        val stored = KV.get(HawkConfig.CONTROL_CAPSULE_DECORATION, ControlTheme.DECORATION_NONE)
        // 3.30：雨水已删，存过雨水（4）的迁到可爱熊（10），一次迁移。
        if (stored == 4) {
            KV.put(HawkConfig.CONTROL_CAPSULE_DECORATION, ControlTheme.DECORATION_JOLLY)
            return ControlTheme.DECORATION_JOLLY
        }
        return ControlTheme.normalizeDecoration(stored)
    }

    fun setCapsuleDecoration(value: Int) {
        KV.put(HawkConfig.CONTROL_CAPSULE_DECORATION, ControlTheme.normalizeDecoration(value))
        bump()
    }

    /**
     * 胶囊装饰动态开关（3.26，默认开）。开 = 雪/枫叶往下飘、云横漂、星星轻微闪烁、闪电间歇亮、
     * 烟花间歇绽放、气球往上飘、金鱼左右游、可爱熊跳舞；关 = 装饰全静态（位置冻住、星星不闪、闪电只留淡折线、
     * 烟花只留淡火花线、金鱼停中央）。开关只管动不动，不管选哪种装饰；选「没有」时开关不起作用。
     */
    fun isCapsuleDecorationAnimated(): Boolean {
        uiRevision
        return KV.get(HawkConfig.CONTROL_CAPSULE_DECORATION_ANIMATED, true)
    }

    fun setCapsuleDecorationAnimated(animated: Boolean) {
        KV.put(HawkConfig.CONTROL_CAPSULE_DECORATION_ANIMATED, animated)
        bump()
    }

    // —— 3.11 两组值滑杆拖动预览（只改内存 groupPreview，不写 KV；松手提交时 saveGroup 按组清掉） ——

    fun setGroupReliefMagnitudePreview(group: ControlGroup, value: Float) {
        val base = groupPreview[group] ?: groupTheme(group)
        groupPreview = groupPreview + (group to base.copy(reliefMagnitude = value.coerceIn(EffectsState.ELEVATION_RANGE)))
    }

    fun setGroupCurvaturePreview(group: ControlGroup, value: Float) {
        val base = groupPreview[group] ?: groupTheme(group)
        groupPreview = groupPreview + (group to base.copy(curvature = value.coerceIn(EffectsState.CURVATURE_RANGE).toInt()))
    }

    fun setGroupLightBrightnessPreview(group: ControlGroup, value: Float) {
        val base = groupPreview[group] ?: groupTheme(group)
        groupPreview = groupPreview + (group to base.copy(lightBrightness = value.coerceIn(EffectsState.LIGHT_BRIGHTNESS_RANGE).toInt()))
    }

    fun setGroupWaterGlossPreview(group: ControlGroup, value: Float) {
        val base = groupPreview[group] ?: groupTheme(group)
        groupPreview = groupPreview + (group to base.copy(waterGloss = value.coerceIn(EffectsState.WATER_GLOSS_RANGE).toInt()))
    }

    // —— 隐藏（每元素可隐藏，设置页“已隐藏的元素”恢复显示） ——

    private fun readHiddenElements(): List<String> =
        parseIdList(KV.get(HawkConfig.CONTROL_HIDDEN_ELEMENTS, ""))

    fun isElementHidden(elementId: String): Boolean {
        uiRevision
        // 总开关关时隐藏不生效（元素全显示），避免关了还有半截效果。
        if (!isControlThemeEnabled()) return false
        return elementId in readHiddenElements()
    }

    fun getHiddenElements(): List<String> {
        uiRevision
        return readHiddenElements()
    }

    fun setElementHidden(elementId: String, hidden: Boolean) {
        val ids = readHiddenElements()
        val next = if (hidden) {
            if (elementId in ids) ids else ids + elementId
        } else {
            ids - elementId
        }
        if (next != ids) {
            KV.put(HawkConfig.CONTROL_HIDDEN_ELEMENTS, next.joinToString(","))
            bump()
        }
    }

    // —— 重排 / 三档位置 ——

    /** 存的顺序 + 当前实际键合并：存过且在的按存的排，没存过 / 新出现的追加在后。 */
    private fun orderedIds(stored: List<String>, current: List<String>): List<String> {
        if (stored.isEmpty()) return current
        val currentSet = current.toSet()
        val out = ArrayList<String>()
        for (id in stored) {
            if (id in currentSet && id !in out) out.add(id)
        }
        for (id in current) {
            if (id !in out) out.add(id)
        }
        return out
    }

    private fun readOrder(key: String): List<String> =
        parseIdList(KV.get(key, ""))

    private fun writeOrder(key: String, ids: List<String>) {
        KV.put(key, ids.joinToString(","))
        bump()
    }

    /** 首页底部导航默认键（与 MainScreen.AppTab 一一对应）。 */
    val DEFAULT_HOME_NAV_KEYS: List<String> = listOf("home", "records", "following", "settings")

    /** 音乐页底部操作默认键（与 MusicBottomActions 五段一一对应）。 */
    val DEFAULT_MUSIC_BOTTOM_IDS: List<String> = listOf(
        "music_bottom_playmode",
        "music_bottom_collect",
        "music_bottom_cast",
        "music_bottom_queue",
        "music_bottom_settings",
    )

    fun getHomeNavOrder(): List<String> {
        uiRevision
        // 总开关关时重排不生效（回默认顺序）。
        if (!isControlThemeEnabled()) return DEFAULT_HOME_NAV_KEYS
        return orderedIds(readOrder(HawkConfig.CONTROL_ORDER_HOME_NAV), DEFAULT_HOME_NAV_KEYS)
    }

    fun setHomeNavOrder(ids: List<String>) {
        writeOrder(HawkConfig.CONTROL_ORDER_HOME_NAV, ids.filter { it in DEFAULT_HOME_NAV_KEYS })
    }

    fun getHomeSectionOrder(): List<String> {
        uiRevision
        return readOrder(HawkConfig.CONTROL_ORDER_HOME_SECTIONS)
    }

    fun setHomeSectionOrder(ids: List<String>) {
        writeOrder(HawkConfig.CONTROL_ORDER_HOME_SECTIONS, ids)
    }

    /** 首页分区按存的顺序排（没存过 = 原顺序）；current 为当前分区 sort id。 */
    fun orderedHomeSectionIds(current: List<String>): List<String> {
        uiRevision
        // 总开关关时重排不生效（回原顺序）。
        if (!isControlThemeEnabled()) return current
        return orderedIds(readOrder(HawkConfig.CONTROL_ORDER_HOME_SECTIONS), current)
    }

    /** 首页当前出现过的分区 sort id（设置页重排列表的来源，首页加载时记录）。 */
    fun getKnownHomeSections(): List<String> {
        uiRevision
        return readOrder(HawkConfig.CONTROL_ORDER_HOME_SECTIONS_KNOWN)
    }

    fun setKnownHomeSections(ids: List<String>) {
        val clean = ids.filter { it.isNotEmpty() }.distinct()
        if (clean != readOrder(HawkConfig.CONTROL_ORDER_HOME_SECTIONS_KNOWN)) {
            KV.put(HawkConfig.CONTROL_ORDER_HOME_SECTIONS_KNOWN, clean.joinToString(","))
            bump()
        }
    }

    fun getMusicBottomOrder(): List<String> {
        uiRevision
        // 总开关关时重排不生效（回默认顺序）。
        if (!isControlThemeEnabled()) return DEFAULT_MUSIC_BOTTOM_IDS
        return orderedIds(readOrder(HawkConfig.CONTROL_ORDER_MUSIC_BOTTOM), DEFAULT_MUSIC_BOTTOM_IDS)
    }

    fun setMusicBottomOrder(ids: List<String>) {
        writeOrder(HawkConfig.CONTROL_ORDER_MUSIC_BOTTOM, ids.filter { it in DEFAULT_MUSIC_BOTTOM_IDS })
    }

    // 3.17：三档位置已删——音乐底栏恒居中、首页分类 chip 行恒靠左，不再存 KV、不再读值。
}
