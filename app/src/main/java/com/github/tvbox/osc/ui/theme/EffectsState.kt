package com.github.tvbox.osc.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

/** The six app-wide visual effects (主题设置 · 应用效果), each independently resettable. */
data class EffectsConfig(
    val cornerScale: Float = EffectsState.DEFAULT_CORNER_SCALE,
    val animSpeed: Int = EffectsState.DEFAULT_ANIM_SPEED,
    val cardElevationDp: Float = EffectsState.DEFAULT_CARD_ELEVATION_DP,
    val reliefEnabled: Boolean = false,
    val reliefProfile: Int = EffectsState.DEFAULT_RELIEF_PROFILE,
    val curvature: Int = EffectsState.DEFAULT_CURVATURE,
    val curvatureEnabled: Boolean = false,
    val curvatureOutward: Boolean = false,
    // 卡片形状（2.70，用户定；2.71 加 8边形）：0 = 矩形（默认），5 = 五边形，6 = 六边形，8 = 八边形，
    // 99 = 心形；与球面 warp 叠加——形状裁轮廓，球面 warp 照常鼓图，立体边沿形状走。
    val cardShape: Int = EffectsState.DEFAULT_CARD_SHAPE,
    val ambientLevel: Int = EffectsState.DEFAULT_AMBIENT_LEVEL,
    val ambientEnabled: Boolean = true,
    val fontStep: Int = EffectsState.DEFAULT_FONT_STEP,
    val frostedLevel: Int = EffectsState.DEFAULT_FROSTED_LEVEL,
    val wallpaperLevel: Int = EffectsState.DEFAULT_WALLPAPER_LEVEL,
    val wallpaperEffectEnabled: Boolean = true,
    val globalTransparent: Boolean = false,
    val lightBrightness: Int = EffectsState.DEFAULT_LIGHT_BRIGHTNESS,
    // 3.11 水光强度（0..200）：表面顶部亮面高光的强度，0 = 无水光（纯平），
    // 越大越亮。胶囊组默认 120（= 现水胶囊 level 60/100 换算）；卡片组默认 40。
    val waterGloss: Int = EffectsState.DEFAULT_WATER_GLOSS,
    // 3.11 字体颜色（0 = 跟主题，胶囊上默认白字；非 0 = 自定义字色 ARGB）。
    val fontColorArgb: Int = 0,
    val highTransparencyLevel: Int = EffectsState.DEFAULT_HIGH_TRANSPARENCY_LEVEL,
    val posterTransparencyLevel: Int = EffectsState.DEFAULT_POSTER_TRANSPARENCY_LEVEL,
    val detailVeilLevel: Int = EffectsState.DEFAULT_DETAIL_VEIL_LEVEL,
) {
    /** Multiplier applied to animation durations; 0 = animations off. */
    val animFactor: Float
        get() = when (animSpeed) {
            EffectsState.ANIM_OFF -> 0f
            EffectsState.ANIM_SLOW -> 1.6f
            EffectsState.ANIM_FAST -> 0.55f
            else -> 1f
        }

    val fontScale: Float
        get() = when (fontStep) {
            EffectsState.FONT_SMALL -> 0.9f
            EffectsState.FONT_LARGE -> 1.15f
            else -> 1f
        }

    /**
     * 悬浮立体 magnitude (0 = flat, 1 = full convex strength at
     * slider 100, up to 2 at slider 200). cardElevationDp stores
     * the 0..200 slider value; the direction is always convex
     * (flat bevel bright side / dome bulge / facet bright side)
     * since logtest42d retired the concave sign.
     */
    /**
     * 立体悬浮总开关（2.03）：关 = 全扁平（渲染强度恒 0，不论预设
     * 立体值与滑杆值）；开 = 预设立体值与滑杆值正常生效。渲染只走
     * reliefMagnitude 一处（EffectsModifiers 与 FloatingNavBar），
     * 故门控此处即全覆盖，无需改各渲染点。
     */
    val reliefMagnitude: Float get() = if (reliefEnabled) cardElevationDp / 100f else 0f

    /** 空间曲率 magnitude 0..2（0 = 平面，1 = 卷成球，2 = 正圆）。 */
    val curvatureMagnitude: Float get() = if (curvatureEnabled) curvature / 100f else 0f

    /**
     * 真变形程度 0..2（2.12 球体，2.13 解耦，2.14 扩到 200%）：
     * 曲率单独驱动 warp，不再乘悬浮立体——只拉曲率滑杆海报就
     * 成球（0=平面，1=卷成球，2=正圆）；悬浮立体只管卡片/胶囊
     * 的边缘鼓起。曲率开关关 = 无 warp。无阴影。
     */
    val warpDegree: Float
        get() = curvatureMagnitude

    /** True when the relief profile is the spherical dome (false = flat cut plane). */
    val isReliefDome: Boolean get() = reliefProfile == EffectsState.RELIEF_PROFILE_DOME

    /** True when the relief profile is the geometric faceted cut. */
    val isReliefFaceted: Boolean get() = reliefProfile == EffectsState.RELIEF_PROFILE_FACETED

    /** True when the relief profile is blue-white porcelain. */
    val isReliefPorcelain: Boolean get() = reliefProfile == EffectsState.RELIEF_PROFILE_PORCELAIN

    /** True when the relief profile is the cocoa keyboard keycap. */
    val isReliefCocoa: Boolean get() = reliefProfile == EffectsState.RELIEF_PROFILE_COCOA

    /** True when the relief profile is cyberpunk armor. */
    val isReliefCyber: Boolean get() = reliefProfile == EffectsState.RELIEF_PROFILE_CYBER

    /** 氛围背景 strength 0..1 (0 = off). */
    val ambientFraction: Float get() = if (ambientEnabled) ambientLevel / 100f else 0f

    /** 毛玻璃栏 strength 0..1 (0 = off). */
    val frostedFraction: Float get() = frostedLevel / 100f

    /** 透明背景 page alpha (logtest43, 0档曲线修正）: the 透明背景
     * slider controls PAGE transparency, not a white veil. v<=40 is
     * 0.75 -> 0.60 linear, so at 0 the page stays translucent and
     * the wallpaper stays visible and vivid （高光锃亮、颜色深、光里
     * 透亮， no blur, no dimming) instead of being buried under a
     * fully opaque page; v=40 keeps the 0.60 reference point and
     * v>40 is unchanged (0.60 -> 0 at 100). Blur and scrim curves
     * are untouched. */
    val wallpaperPageAlpha: Float get() =
        if (wallpaperLevel <= 40) 0.75f - 0.15f * (wallpaperLevel / 40f)
        else 1f - wallpaperLevel / 100f

    /** Wallpaper backdrop blur is retired: 40-100 darkens via scrim
     * only, the wallpaper itself stays sharp at every level. */
    val wallpaperBlurDp: Float get() = 0f

    /** Dark scrim alpha over the wallpaper (logtest43 third
     * correction): 0 up to level 40 (no dimming, vivid wallpaper);
     * above 40 it grows linearly to 0.50 at 100, so at 100 the
     * backdrop is a dark, translucent wash that keeps
     * text readable over the fully transparent page. */
    val wallpaperScrimAlpha: Float get() =
        if (!wallpaperEffectEnabled) 0f
        else if (wallpaperLevel <= 40) 0f else (wallpaperLevel - 40) / 60f * 0.5f

    /** Wallpaper highlight lift 0..1 for level < 40 ("打高光"): 0
     * at 40 (original colors, untouched), 1 at 0 (brightest,
     * deepest, most vivid); linear and continuous in between. */
    val wallpaperHighlightT: Float get() =
        if (!wallpaperEffectEnabled) 0f
        else if (wallpaperLevel >= 40) 0f else (40 - wallpaperLevel) / 40f

    /** 光感亮度 multiplier for relief light-side alphas: value/80 (120 = 1.5x, logtest43 brighter). */
    val lightFactor: Float get() = lightBrightness / 80f

    /**
     * 高透明 multiplier for content card/capsule background alphas
     * (69: full transparency): 1 at level 0 (current opacity),
     * 0 at level 100 (backgrounds fully transparent, only text
     * and relief faces remain). No floor anywhere: every call
     * site multiplies by this factor directly, so 100 really
     * means fully transparent. Settings pages never read this
     * (flat rule).
     */
    val highTransparencyFactor: Float get() = 1f - highTransparencyLevel / 100f

    /**
     * 海报透明 multiplier for poster-image alphas (73): 1 at level 0
     * (posters fully opaque, the pre-73 look), 0 at level 100
     * (poster images fully transparent, only text and relief faces
     * remain, wallpaper shows through). Independent of
     * [highTransparencyFactor]: posters follow this slider,
     * capsules follow 高透明, never the other way round.
     */
    val posterTransparencyFactor: Float get() = 1f - posterTransparencyLevel / 100f
}

/** Provided by AVBoxTheme so any composable can scale its animation specs. */
val LocalAnimFactor = compositionLocalOf { 1f }

/** Latest hero-poster seed color (ARGB) published by the home page. */
object AmbientState {
    var seedArgb by mutableStateOf<Int?>(null)
}

object EffectsState {

    const val DEFAULT_CORNER_SCALE = 1f
    val CORNER_RANGE: ClosedFloatingPointRange<Float> = 0f..2f

    const val ANIM_OFF = 0
    const val ANIM_SLOW = 1
    const val ANIM_STANDARD = 2
    const val ANIM_FAST = 3
    const val DEFAULT_ANIM_SPEED = ANIM_STANDARD

    const val DEFAULT_CARD_ELEVATION_DP = 0f  // 悬浮立体默认关

    /** 悬浮立体 profile: flat cut plane (logtest39 bands). */
    const val RELIEF_PROFILE_FLAT = 0

    /** 悬浮立体 profile: shallow spherical dome (logtest41, no flat bands). */
    const val RELIEF_PROFILE_DOME = 1

    /** 悬浮立体 profile: geometric gemstone facets (logtest42, flat tones + cut hairlines). */
    const val RELIEF_PROFILE_FACETED = 2

    /** 悬浮立体 profile: blue-white porcelain (glaze-white base + cobalt-blue edges). */
    const val RELIEF_PROFILE_PORCELAIN = 3

    /** 悬浮立体 profile: cocoa keyboard (plump chocolate keycap, bun-like bulge + soft shadow). */
    const val RELIEF_PROFILE_COCOA = 4

    /** 悬浮立体 profile: cyberpunk armor (dark plates + cyan/magenta neon glowing seams + circuit traces). */
    const val RELIEF_PROFILE_CYBER = 5

    /** Settled 2026-10-25: relief profile names are all four characters —
     * 平面切面、球面弧形、几何刻面、青花瓷釉、可可键盘、赛博朋克
     * (黑金铠甲 cancelled 2026-10-25, replaced by 可可键盘; never add it;
     * 青花瓷球 cancelled 2026-10-26, profile 6 removed in 72). */

    /** Default profile is flat, so existing installs keep the current look. */
    const val DEFAULT_RELIEF_PROFILE = RELIEF_PROFILE_DOME  // 默认球面弧形，立体感更强

    /**
     * 悬浮立体 slider scale (logtest42d): magnitude-only 0..200.
     * 0 = flat, 100 = full convex strength, 200 = double strength.
     * (logtest37-42 used signed -50..+50; logtest36 used 0..100;
     * pre-36 stored raw dp 0..24. All migrate to this scale in
     * [loadElevation].)
     */
    // 2.12 球体：悬浮立体恢复之前的 200（2.10 的 300 撤销；2.10 存的
    // 立体值 >200 的进 2.12 钳到 200，点一次主题即恢复该主题原值 42–190）。
    val ELEVATION_RANGE: ClosedFloatingPointRange<Float> = 0f..200f

    /** 空间曲率默认 35%（球体方案的提议默认值）。 */
    const val DEFAULT_CURVATURE = 35

    /** 空间曲率滑杆 0..200%（2.14：100% 卷成球，200% 正圆）。 */
    val CURVATURE_RANGE: ClosedFloatingPointRange<Float> = 0f..200f

    /** 卡片形状（2.70/2.71）：0 = 矩形，5 = 五边形，6 = 六边形，8 = 八边形，99 = 心形，100 = 圆形。 */
    const val DEFAULT_CARD_SHAPE = 0
    const val CARD_SHAPE_RECT = 0
    const val CARD_SHAPE_PENTAGON = 5
    const val CARD_SHAPE_HEXAGON = 6
    const val CARD_SHAPE_OCTAGON = 8
    const val CARD_SHAPE_HEART = 99
    const val CARD_SHAPE_CIRCLE = 100
    val CARD_SHAPE_OPTIONS: List<Int> =
        listOf(CARD_SHAPE_RECT, CARD_SHAPE_HEART)

    /** Pre-36 fx_card_elevation stored raw dp in 0..24; larger stored values are already new-scale. */
    private const val LEGACY_ELEVATION_MAX_DP = 24f

    /** One-shot flag: set once fx_card_elevation has been converted to the signed -50..+50 scale. */
    private const val ELEVATION_SIGNED_KEY = "fx_card_elevation_signed_v2"

    /** One-shot flag: set once fx_card_elevation has been converted from signed -50..+50 to magnitude 0..100. */
    private const val ELEVATION_MAGNITUDE_KEY = "fx_card_elevation_magnitude_v1"

    const val FONT_SMALL = 0
    const val FONT_STANDARD = 1
    const val FONT_LARGE = 2
    const val DEFAULT_FONT_STEP = FONT_STANDARD

    const val DEFAULT_AMBIENT_LEVEL = 0
    const val DEFAULT_FROSTED_LEVEL = 0
    const val DEFAULT_WALLPAPER_LEVEL = 40

    /** 详情海报上面那层遮罩 default 50 = 现行 0.5（1.52 起）。 */
    const val DEFAULT_DETAIL_VEIL_LEVEL = 50

    /** 光感亮度 default 120 = 1.5x the pre-42 highlight brightness (logtest43 brighter). */
    const val DEFAULT_LIGHT_BRIGHTNESS = 140  // 高光略提亮，立体边缘更清晰

    /** 3.11 水光强度默认 400（0..400）：3.25 由 120 提到 400，胶囊组默认；卡片组默认 40（见 ControlThemeStore）。 */
    const val DEFAULT_WATER_GLOSS = 400

    /** 水光强度滑杆 0..400（3.25 由 0..200 放开）。 */
    val WATER_GLOSS_RANGE: ClosedFloatingPointRange<Float> = 0f..400f

    val LEVEL_RANGE: ClosedFloatingPointRange<Float> = 0f..100f



    /** 高透明 default 0: current opacity; 100 = backgrounds fully transparent (69). */
    const val DEFAULT_HIGH_TRANSPARENCY_LEVEL = 0

    /** 海报透明 default 0: posters fully opaque (the pre-73 look); 100 = poster images fully transparent (73). */
    const val DEFAULT_POSTER_TRANSPARENCY_LEVEL = 0


    /** 光感亮度 slider range 0..200 (displayed as a number). */
    val LIGHT_BRIGHTNESS_RANGE: ClosedFloatingPointRange<Float> = 0f..200f

    /** Pre-35 the two level effects were on/off switches; a stored "on" migrates to this level. */
    private const val MIGRATED_LEVEL = 60

    private var current by mutableStateOf(load())

    /**
     * 3.09 更正（总开关分流）：渲染读这里，5 项（形状 / 圆角 / 浮雕 / 曲面 / 光感）
     * 总开关开 → 读随心所欲的值（ControlThemeStore）；关 → 读原主题自己的值
     * （current，ThemePreset/EffectsState 原样，跟 3.07 之前一样）。
     * 读 [ControlThemeStore] 会订阅它的 state，开关一切换读这里的地方即重组；
     * 渲染处（EffectsModifiers / PressableCard / VodPoster / FloatingNavBar）一字不动。
     */
    val config: EffectsConfig get() = if (ControlThemeStore.isControlThemeEnabled()) {
        current.copy(
            cornerScale = ControlThemeStore.config.cornerScale,
            cardElevationDp = ControlThemeStore.config.reliefMagnitude,
            reliefEnabled = ControlThemeStore.config.reliefEnabled,
            reliefProfile = ControlThemeStore.config.reliefProfile,
            curvature = ControlThemeStore.config.curvature,
            curvatureEnabled = ControlThemeStore.config.curvatureEnabled,
            curvatureOutward = ControlThemeStore.config.curvatureOutward,
            cardShape = ControlThemeStore.config.shape,
            lightBrightness = ControlThemeStore.config.lightBrightness,
            waterGloss = ControlThemeStore.config.waterGloss,
            fontColorArgb = ControlThemeStore.config.fontColorArgb,
        )
    } else {
        current
    }

    private fun loadLevel(levelKey: String, legacyKey: String): Int {
        val stored = KV.get(levelKey, -1)
        if (stored >= 0) return stored.coerceIn(0, 100)
        return if (KV.get(legacyKey, false)) MIGRATED_LEVEL else 0
    }

    /**
     * Legacy migration for fx_card_elevation, in two one-shot steps.
     * Step 1 (unchanged, via [ELEVATION_SIGNED_KEY]): values written
     * by 0..100 builds (logtest36/37-dev) halve (v/2, so a dragged
     * 100 becomes +50); pre-36 raw-dp values (0..24) first convert
     * to the 0..100 scale exactly as logtest36 did (v/24*100) and
     * then halve (= v/24*50); negative values are already
     * signed-scale and pass through.
     * Step 2 (logtest42d, via [ELEVATION_MAGNITUDE_KEY]): the signed
     * -50..+50 value maps by magnitude to 0..100 (|v|/50*100, so
     * -50 -> 100, +50 -> 100, 0 -> 0) and the concave sign is
     * dropped. Each flag is written BEFORE its converted value and
     * each conversion only runs while its flag is unset, so a value
     * can never convert twice across restarts; if the process died
     * between the two writes of a step, the stale value is simply
     * clamped into range on the next load (flat, the safe default).
     */
    private fun loadElevation(): Float {
        val signed = if (KV.get(ELEVATION_SIGNED_KEY, false)) {
            KV.get(HawkConfig.FX_CARD_ELEVATION, DEFAULT_CARD_ELEVATION_DP)
        } else {
            val stored = KV.get(HawkConfig.FX_CARD_ELEVATION, DEFAULT_CARD_ELEVATION_DP)
            val converted = when {
                stored < 0f -> stored
                stored <= LEGACY_ELEVATION_MAX_DP ->
                    stored / LEGACY_ELEVATION_MAX_DP * 100f / 2f
                else -> stored / 2f
            }.coerceIn(-50f, 50f)
            KV.put(ELEVATION_SIGNED_KEY, true)
            KV.put(HawkConfig.FX_CARD_ELEVATION, converted)
            converted
        }
        if (KV.get(ELEVATION_MAGNITUDE_KEY, false)) {
            return signed.coerceIn(ELEVATION_RANGE)
        }
        val magnitude = (kotlin.math.abs(signed) / 50f * 100f).coerceIn(ELEVATION_RANGE)
        KV.put(ELEVATION_MAGNITUDE_KEY, true)
        KV.put(HawkConfig.FX_CARD_ELEVATION, magnitude)
        return magnitude
    }

    private fun load(): EffectsConfig = EffectsConfig(
        // 3.09 更正：原主题 5 项的值留着当总开关关时的兜底，这里只读旧 KV（不读 control_ 新键），
        // 不删、不清空；随心所欲那份由 ControlThemeStore.load 首次从旧值复制，两边各自独立。
        cornerScale = KV.get(HawkConfig.FX_CORNER_SCALE, DEFAULT_CORNER_SCALE),
        animSpeed = KV.get(HawkConfig.FX_ANIM_SPEED, DEFAULT_ANIM_SPEED),
        cardElevationDp = loadElevation(),
        reliefEnabled = KV.get(HawkConfig.FX_RELIEF_ENABLED, false),
        reliefProfile = migrateReliefProfile(
            KV.get(HawkConfig.FX_RELIEF_PROFILE, DEFAULT_RELIEF_PROFILE),
        ),
        curvature = KV.get(HawkConfig.FX_SPATIAL_CURVATURE, DEFAULT_CURVATURE)
            .coerceIn(0, 200),
        curvatureEnabled = KV.get(HawkConfig.FX_CURVATURE_ENABLED, false),
        curvatureOutward = KV.get(HawkConfig.FX_CURVATURE_OUTWARD, false),
        cardShape = KV.get(HawkConfig.FX_CARD_SHAPE, DEFAULT_CARD_SHAPE)
            .let { if (it in CARD_SHAPE_OPTIONS) it else DEFAULT_CARD_SHAPE },
        ambientLevel = loadLevel(HawkConfig.FX_AMBIENT_LEVEL, HawkConfig.FX_AMBIENT_BG),
        ambientEnabled = KV.get(HawkConfig.FX_AMBIENT_ENABLED, true),
        fontStep = KV.get(HawkConfig.FX_FONT_STEP, DEFAULT_FONT_STEP),
        frostedLevel = loadLevel(HawkConfig.FX_FROSTED_LEVEL, HawkConfig.FX_FROSTED_BARS),
        wallpaperLevel = KV.get(HawkConfig.FX_WALLPAPER_LEVEL, DEFAULT_WALLPAPER_LEVEL)
            .coerceIn(0, 100),
        wallpaperEffectEnabled = KV.get(HawkConfig.FX_WALLPAPER_EFFECT_ENABLED, true),
        globalTransparent = KV.get(HawkConfig.FX_GLOBAL_TRANSPARENT, false),
        lightBrightness = KV.get(HawkConfig.FX_LIGHT_BRIGHTNESS, DEFAULT_LIGHT_BRIGHTNESS)
            .coerceIn(0, 200),
        highTransparencyLevel = KV.get(HawkConfig.FX_HIGH_TRANSPARENCY, DEFAULT_HIGH_TRANSPARENCY_LEVEL)
            .coerceIn(0, 100),
        posterTransparencyLevel = KV.get(HawkConfig.FX_POSTER_TRANSPARENCY, DEFAULT_POSTER_TRANSPARENCY_LEVEL)
            .coerceIn(0, 100),
        detailVeilLevel = KV.get(HawkConfig.FX_DETAIL_VEIL, DEFAULT_DETAIL_VEIL_LEVEL)
            .coerceIn(0, 100),
    )

    fun setCornerScale(value: Float) {
        KV.put(HawkConfig.FX_CORNER_SCALE, value)
        current = current.copy(cornerScale = value)
    }

    fun setAnimSpeed(value: Int) {
        KV.put(HawkConfig.FX_ANIM_SPEED, value)
        current = current.copy(animSpeed = value)
    }

    /** 立体悬浮总开关：只写开关位，不动立体值；开后即恢复当前预设/滑杆的立体效果。 */
    fun setReliefEnabled(enabled: Boolean) {
        KV.put(HawkConfig.FX_RELIEF_ENABLED, enabled)
        current = current.copy(reliefEnabled = enabled)
    }

    fun setCardElevationDp(value: Float) {
        val coerced = value.coerceIn(ELEVATION_RANGE)
        KV.put(HawkConfig.FX_CARD_ELEVATION, coerced)
        current = current.copy(cardElevationDp = coerced)
    }

    /** 空间曲率总开关：只写开关位，不动曲率值；开后即恢复当前预设/滑杆的曲率效果。 */
    fun setCurvatureEnabled(enabled: Boolean) {
        KV.put(HawkConfig.FX_CURVATURE_ENABLED, enabled)
        current = current.copy(curvatureEnabled = enabled)
    }

    /** 曲率方向开关（2.19）：开 = 往外折（碗形内凹，角往你这边翻），关 = 往里折（球形外凸，角往后卷，默认）。只写方向位，不动曲率值与开关。 */
    fun setCurvatureOutward(enabled: Boolean) {
        KV.put(HawkConfig.FX_CURVATURE_OUTWARD, enabled)
        current = current.copy(curvatureOutward = enabled)
    }

    fun setCurvature(value: Int) {
        // 2.15：与 setCurvaturePreview 同口径，走 CURVATURE_RANGE（0..200）。
        // 2.14 漏了这里（还写死 0..100），松手提交时 200 被钳回 100。
        // 注意 value 是 Int，先转 Float 再进 Float 范围（直接 coerceIn 编不过）。
        val level = value.toFloat().coerceIn(CURVATURE_RANGE).toInt()
        KV.put(HawkConfig.FX_SPATIAL_CURVATURE, level)
        current = current.copy(curvature = level)
    }

    /** 空间曲率重置恢复默认 35%。 */
    fun resetCurvature() = setCurvature(DEFAULT_CURVATURE)

    /** 卡片形状（2.70/2.71）：只认 0/5/6/8/99，其它值落回矩形。 */
    fun setCardShape(value: Int) {
        val shape = if (value in CARD_SHAPE_OPTIONS) value else DEFAULT_CARD_SHAPE
        KV.put(HawkConfig.FX_CARD_SHAPE, shape)
        current = current.copy(cardShape = shape)
    }

    /** 卡片形状重置恢复矩形。 */
    fun resetCardShape() = setCardShape(DEFAULT_CARD_SHAPE)

    /** Stored profile 6 was 71's porcelain ball, removed in 72.
     * Cocoa keyboard, cyberpunk and porcelain profiles were removed
     * in 2.01: stored values fall back to dome. */
    private fun migrateReliefProfile(stored: Int): Int =
        when (stored) {
            6, RELIEF_PROFILE_COCOA, RELIEF_PROFILE_CYBER, RELIEF_PROFILE_PORCELAIN -> RELIEF_PROFILE_DOME
            else -> stored.coerceIn(RELIEF_PROFILE_FLAT, RELIEF_PROFILE_FACETED)
        }

    /** Profile switch commits immediately, so the new face previews at once. */
    fun setReliefProfile(value: Int) {
        val profile = when (value) {
            RELIEF_PROFILE_COCOA, RELIEF_PROFILE_CYBER, RELIEF_PROFILE_PORCELAIN -> RELIEF_PROFILE_DOME
            else -> value.coerceIn(RELIEF_PROFILE_FLAT, RELIEF_PROFILE_FACETED)
        }
        KV.put(HawkConfig.FX_RELIEF_PROFILE, profile)
        current = current.copy(reliefProfile = profile)
    }

    fun setAmbientLevel(value: Int) {
        val level = value.coerceIn(0, 100)
        KV.put(HawkConfig.FX_AMBIENT_LEVEL, level)
        current = current.copy(ambientLevel = level)
    }

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setAmbientLevelPreview(value: Float) {
        current = current.copy(ambientLevel = value.coerceIn(LEVEL_RANGE).toInt())
    }

    fun setAmbientEnabled(enabled: Boolean) {
        KV.put(HawkConfig.FX_AMBIENT_ENABLED, enabled)
        current = current.copy(ambientEnabled = enabled)
    }

    fun setFontStep(value: Int) {
        KV.put(HawkConfig.FX_FONT_STEP, value)
        current = current.copy(fontStep = value)
    }

    fun setFrostedLevel(value: Int) {
        val level = value.coerceIn(0, 100)
        KV.put(HawkConfig.FX_FROSTED_LEVEL, level)
        current = current.copy(frostedLevel = level)
    }

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setFrostedLevelPreview(value: Float) {
        current = current.copy(frostedLevel = value.coerceIn(LEVEL_RANGE).toInt())
    }

    fun setWallpaperLevel(value: Int) {
        val level = value.coerceIn(0, 100)
        KV.put(HawkConfig.FX_WALLPAPER_LEVEL, level)
        current = current.copy(wallpaperLevel = level)
    }

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setWallpaperLevelPreview(value: Float) {
        current = current.copy(wallpaperLevel = value.coerceIn(LEVEL_RANGE).toInt())
    }

    fun setLightBrightness(value: Int) {
        val level = value.coerceIn(0, 200)
        KV.put(HawkConfig.FX_LIGHT_BRIGHTNESS, level)
        current = current.copy(lightBrightness = level)
    }

    fun resetCornerScale() = setCornerScale(DEFAULT_CORNER_SCALE)

    fun resetAnimSpeed() = setAnimSpeed(DEFAULT_ANIM_SPEED)

    /** The relief card's reset restores both the slider value and the profile. */
    fun resetCardElevation() {
        setReliefProfile(DEFAULT_RELIEF_PROFILE)
        setCardElevationDp(DEFAULT_CARD_ELEVATION_DP)
    }

    fun resetAmbientLevel() = setAmbientLevel(DEFAULT_AMBIENT_LEVEL)

    fun resetFontStep() = setFontStep(DEFAULT_FONT_STEP)

    fun resetFrostedLevel() = setFrostedLevel(DEFAULT_FROSTED_LEVEL)

    fun resetWallpaperLevel() = setWallpaperLevel(DEFAULT_WALLPAPER_LEVEL)

    fun setDetailVeilLevel(value: Int) {
        val level = value.coerceIn(0, 100)
        KV.put(HawkConfig.FX_DETAIL_VEIL, level)
        current = current.copy(detailVeilLevel = level)
    }

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setDetailVeilLevelPreview(value: Float) {
        current = current.copy(detailVeilLevel = value.coerceIn(LEVEL_RANGE).toInt())
    }

    fun resetDetailVeilLevel() = setDetailVeilLevel(DEFAULT_DETAIL_VEIL_LEVEL)

    fun setWallpaperEffectEnabled(enabled: Boolean) {
        KV.put(HawkConfig.FX_WALLPAPER_EFFECT_ENABLED, enabled)
        current = current.copy(wallpaperEffectEnabled = enabled)
    }

    /** logtest50 全局透明 master: read-time gate only, never fans out
     * to the individual layer keys, so turning it off restores each
     * layer's own slider/switch untouched. */
    fun setGlobalTransparent(enabled: Boolean) {
        KV.put(HawkConfig.FX_GLOBAL_TRANSPARENT, enabled)
        current = current.copy(globalTransparent = enabled)
    }

    /** 光感亮度 reset restores 120 only; relief slider/profile untouched. */
    fun resetLightBrightness() = setLightBrightness(DEFAULT_LIGHT_BRIGHTNESS)

    fun setHighTransparencyLevel(value: Int) {
        val level = value.coerceIn(0, 100)
        KV.put(HawkConfig.FX_HIGH_TRANSPARENCY, level)
        current = current.copy(highTransparencyLevel = level)
    }

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setHighTransparencyLevelPreview(value: Float) {
        current = current.copy(highTransparencyLevel = value.toInt().coerceIn(0, 100))
    }

    fun resetHighTransparency() = setHighTransparencyLevel(DEFAULT_HIGH_TRANSPARENCY_LEVEL)

    fun setPosterTransparencyLevel(value: Int) {
        val level = value.coerceIn(0, 100)
        KV.put(HawkConfig.FX_POSTER_TRANSPARENCY, level)
        current = current.copy(posterTransparencyLevel = level)
    }

    /** In-drag live preview: state only; the slider commits to KV on release. */
    fun setPosterTransparencyLevelPreview(value: Float) {
        current = current.copy(posterTransparencyLevel = value.toInt().coerceIn(0, 100))
    }

    fun resetPosterTransparency() = setPosterTransparencyLevel(DEFAULT_POSTER_TRANSPARENCY_LEVEL)

}
