package com.github.tvbox.osc.ui.theme

import com.materialkolor.PaletteStyle

/**
 * 一键主题预设：选中后把颜色 + 立体 + 胶囊 + 玻璃等参数一次性写死套用。
 */
data class ThemePreset(
    val id: String,
    val name: String,
    val subtitle: String,
    // —— 颜色 ——
    val mode: Int,                 // ThemeMode
    val seedArgb: Int,
    val style: PaletteStyle,
    val pureBlack: Boolean,
    // —— 立体 / 效果 ——
    val reliefProfile: Int,
    val elevation: Float,          // 0..200
    val curvature: Int,            // 0..200（2.14：100% 卷成球，200% 正圆）
    val lightBrightness: Int,      // 0..200
    val cornerScale: Float,
    val highTransparency: Int,     // 0..100
    val posterTransparency: Int,
    val ambientLevel: Int,
    val frostedLevel: Int,
    val wallpaperLevel: Int,
    val detailVeilLevel: Int,
    // —— 液态玻璃 ——
    val glassBlurDp: Float,
    val glassDistortionDp: Float,
    val glassTranslucency: Float,
)

object ThemePresets {

    /**
     * 一键套用：颜色 + 立体 + 胶囊 + 玻璃全部写死。
     */
    fun apply(preset: ThemePreset, saveBefore: Boolean = true) {
        if (saveBefore) saveSnapshot()
        // 颜色
        AppThemeState.setSource(ThemeSource.CUSTOM)
        AppThemeState.setMode(preset.mode)
        AppThemeState.setSeed(preset.seedArgb)
        AppThemeState.setStyle(preset.style)
        AppThemeState.setPureBlack(preset.pureBlack)
        AppThemeState.setTextColor(0)

        // 立体 / 效果（2.10 跟主题：用户指出悬浮开关已是扁平的
        // 守门——开关关时渲染门控为 0，主题写什么值都不会显示，
        // 所以主题恢复写立体值是安全的；开关开时换主题即按
        // 新主题的立体值来，滑杆显示跟随。2.04 起全归 0 的
        // 36 套预设立体值已从 2.02 恢复原值。2.12：空间曲率同
        // 样跟主题（各主题曲率 = 立体值/2），曲率开关关时不显示。）
        // 3.09 更正：这 5 项写的是原主题那份（EffectsState + 旧 KV），是总开关
        // 关时的兜底来源，切主题/包时原值跟着变；不许写随心所欲那份（control_ 键）。
        EffectsState.setReliefProfile(preset.reliefProfile)
        EffectsState.setCardElevationDp(preset.elevation)
        EffectsState.setCurvature(preset.curvature)
        EffectsState.setLightBrightness(preset.lightBrightness)
        EffectsState.setCornerScale(preset.cornerScale)
        EffectsState.setHighTransparencyLevel(preset.highTransparency)
        EffectsState.setPosterTransparencyLevel(preset.posterTransparency)
        EffectsState.setAmbientLevel(preset.ambientLevel)
        EffectsState.setFrostedLevel(preset.frostedLevel)
        EffectsState.setWallpaperLevel(preset.wallpaperLevel)
        EffectsState.setDetailVeilLevel(preset.detailVeilLevel)

        // 液态玻璃：只写数值类参数。1.93 起不再强制开底部导航与应用
        // 控件（用户定默认关，点主题也不替用户打开）；边缘色散仍跟主题走。
        LiquidGlassState.setBlurDp(preset.glassBlurDp)
        LiquidGlassState.setDistortionDp(preset.glassDistortionDp)
        LiquidGlassState.setTranslucency(preset.glassTranslucency)
        LiquidGlassState.setDispersion(true)

        // 记住上次选中的预设 id（可选，便于 UI 高亮）
        com.github.tvbox.osc.util.KV.put("theme_preset_id", preset.id)
    }

    // 上一套快照（1.91）：点主题前把当前全量参数存成一串，格子末尾的
    // “返回上一套”可整套还原（含玻璃开关与文字色，滑杆还原补不回来的两处）。
    // 只在点主题时更新，手动拖杆不碰它；KV 持久化，重启后还在。
    private const val SNAPSHOT_KEY = "theme_snapshot_v1"

    // 2.12：快照升到 v2，末尾追加空间曲率；v1 快照（26 段）仍可还原，
    // 曲率不动（保持当前值）。

    fun hasSnapshot(): Boolean =
        com.github.tvbox.osc.util.KV.contains(SNAPSHOT_KEY)

    fun saveSnapshot() {
        val theme = AppThemeState.config
        val fx = EffectsState.config
        val glass = LiquidGlassState.config
        val raw = listOf(
            "v2",
            theme.source.toString(),
            theme.mode.toString(),
            theme.seedArgb.toString(),
            theme.style.name,
            theme.pureBlack.toString(),
            theme.textColorArgb.toString(),
            fx.reliefProfile.toString(),
            fx.cardElevationDp.toString(),
            fx.lightBrightness.toString(),
            fx.cornerScale.toString(),
            fx.highTransparencyLevel.toString(),
            fx.posterTransparencyLevel.toString(),
            fx.ambientLevel.toString(),
            fx.frostedLevel.toString(),
            fx.wallpaperLevel.toString(),
            fx.detailVeilLevel.toString(),
            "0", // 3.17：水浮已删，槽位留空占位（格式仍 27 段）。
            "0",
            "0",
            glass.navbarEnabled.toString(),
            glass.controlsEnabled.toString(),
            glass.blurDp.toString(),
            glass.distortionDp.toString(),
            glass.translucency.toString(),
            glass.dispersion.toString(),
            fx.curvature.toString(),
        ).joinToString("|")
        com.github.tvbox.osc.util.KV.put(SNAPSHOT_KEY, raw)
    }

    // 还原上一套：返回可供页面刷新滑杆的预设对象；无快照或损坏返回 null。
    // 还原本身不更新快照，可反复点回同一套。
    fun restoreSnapshot(): ThemePreset? {
        val raw: String = com.github.tvbox.osc.util.KV.get(SNAPSHOT_KEY, "")
        if (raw.isEmpty()) return null
        val p = raw.split("|")
        // v2 = 27 段（末尾曲率）；v1 = 26 段（无曲率，曲率保持当前值）。
        if ((p.size != 26 && p.size != 27) || (p[0] != "v1" && p[0] != "v2")) return null
        val snapshotCurvature = if (p.size == 27) p[26].toInt() else EffectsState.config.curvature
        return try {
            AppThemeState.setSource(p[1].toInt())
            AppThemeState.setMode(p[2].toInt())
            AppThemeState.setSeed(p[3].toInt())
            AppThemeState.setStyle(PaletteStyle.valueOf(p[4]))
            AppThemeState.setPureBlack(p[5].toBoolean())
            AppThemeState.setTextColor(p[6].toInt())
            // 3.09 更正：快照还原照常写回原主题那份（总开关关时的兜底），不写随心所欲那份。
            EffectsState.setReliefProfile(p[7].toInt())
            EffectsState.setCardElevationDp(p[8].toFloat())
            if (p.size == 27) EffectsState.setCurvature(snapshotCurvature)
            EffectsState.setLightBrightness(p[9].toInt())
            EffectsState.setCornerScale(p[10].toFloat())
            EffectsState.setHighTransparencyLevel(p[11].toInt())
            EffectsState.setPosterTransparencyLevel(p[12].toInt())
            EffectsState.setAmbientLevel(p[13].toInt())
            EffectsState.setFrostedLevel(p[14].toInt())
            EffectsState.setWallpaperLevel(p[15].toInt())
            EffectsState.setDetailVeilLevel(p[16].toInt())
            LiquidGlassState.setNavbarEnabled(p[20].toBoolean())
            LiquidGlassState.setControlsEnabled(p[21].toBoolean())
            LiquidGlassState.setBlurDp(p[22].toFloat())
            LiquidGlassState.setDistortionDp(p[23].toFloat())
            LiquidGlassState.setTranslucency(p[24].toFloat())
            LiquidGlassState.setDispersion(p[25].toBoolean())
            ThemePreset(
                id = "snapshot",
                name = "上一套",
                subtitle = "",
                mode = p[2].toInt(),
                seedArgb = p[3].toInt(),
                style = PaletteStyle.valueOf(p[4]),
                pureBlack = p[5].toBoolean(),
                reliefProfile = p[7].toInt(),
                elevation = p[8].toFloat(),
                curvature = snapshotCurvature,
                lightBrightness = p[9].toInt(),
                cornerScale = p[10].toFloat(),
                highTransparency = p[11].toInt(),
                posterTransparency = p[12].toInt(),
                ambientLevel = p[13].toInt(),
                frostedLevel = p[14].toInt(),
                wallpaperLevel = p[15].toInt(),
                detailVeilLevel = p[16].toInt(),
                glassBlurDp = p[22].toFloat(),
                glassDistortionDp = p[23].toFloat(),
                glassTranslucency = p[24].toFloat(),
            )
        } catch (e: Exception) {
            null
        }
    }

    const val CUSTOM_ID = "custom"


    fun markCustom() {
        com.github.tvbox.osc.util.KV.put("theme_preset_id", CUSTOM_ID)
    }

    /** 下拉档位：显示名 to 实际数值 */
    val ELEVATION_LEVELS: List<Pair<String, Float>> = listOf(
        "关闭" to 0f,
        "轻微" to 40f,
        "适中" to 90f,
        "明显" to 130f,
        "强烈" to 170f,
        "极致" to 200f,
    )

    val LIGHT_LEVELS: List<Pair<String, Float>> = listOf(
        "偏暗" to 80f,
        "标准" to 120f,
        "明亮" to 140f,
        "高亮" to 170f,
        "刺眼" to 200f,
    )

    val CAPSULE_LEVELS: List<Pair<String, Float>> = listOf(
        "关闭" to 0f,
        "淡" to 30f,
        "标准" to 60f,
        "明显" to 80f,
        "饱和" to 100f,
    )

    val TRANSPARENCY_LEVELS: List<Pair<String, Float>> = listOf(
        "不透明" to 0f,
        "微透" to 20f,
        "半透" to 50f,
        "很透" to 75f,
        "全透" to 100f,
    )
}
