package com.github.tvbox.osc.music

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

// 封面取色内置双主题（2.90）：纯数学、无 AI、无联网。输入封面取出的
// 3–5 个色，输出一对明暗主题（各含背景 / 主文字 / 强调 / 次要文字四色
// + 中文名 + 一句话描述）。对标 Folia 的
// src/utils/builtinTheme/{coverPaletteAnalysis,generateBuiltinDualTheme,
// themeColorRanges,themeNameTable,themeDescriptionTable}.ts，
// 数值、公式、名表、描述表逐项照抄。
// wordColors 为空（Folia 内置主题 wordColors: []），不做关键词着色。

data class BuiltinTheme(
    val name: String,
    val description: String,
    val backgroundColor: Int,
    val primaryColor: Int,
    val accentColor: Int,
    val secondaryColor: Int,
)

data class BuiltinDualTheme(val light: BuiltinTheme, val dark: BuiltinTheme)

data class CoverPalette(
    val baseHue: Double,
    val baseSaturation: Double,
    val baseLightness: Double,
    val accentHue: Double,
    val scheme: HarmonyScheme,
    val tone: PaletteTone,
)

enum class HarmonyScheme { ANALOGOUS, COMPLEMENTARY, SPLIT, TRIADIC, DUOTONE, MONOCHROME }

enum class PaletteTone { INK, TINTED, RICH }

enum class BuiltinThemeMode { LIGHT, DARK }

object MusicBuiltinTheme {

    private val cache = ConcurrentHashMap<String, BuiltinDualTheme>()

    fun cached(songKey: String): BuiltinDualTheme? = cache[songKey]

    fun putCache(songKey: String, theme: BuiltinDualTheme) {
        cache[songKey] = theme
    }

    // ---- 对比度目标与背景饱和度下限（themeColorRanges.ts:18-21） ----

    const val PRIMARY_MIN_CONTRAST = 9.0
    const val ACCENT_MIN_CONTRAST = 3.2
    const val SECONDARY_MIN_CONTRAST = 4.5
    const val MIN_BACKGROUND_SATURATION = 0.04

    // ---- 背景 L/S 范围表（themeColorRanges.ts:24-42） ----

    private data class BackgroundTone(
        val darkLightness: Pair<Double, Double>,
        val darkSaturation: Pair<Double, Double>,
        val lightLightness: Pair<Double, Double>,
        val lightSaturation: Pair<Double, Double>,
    )

    private val BACKGROUND_TONES: Map<PaletteTone, BackgroundTone> = mapOf(
        PaletteTone.INK to BackgroundTone(
            darkLightness = 0.055 to 0.085,
            darkSaturation = 0.10 to 0.22,
            lightLightness = 0.940 to 0.965,
            lightSaturation = 0.05 to 0.12,
        ),
        PaletteTone.TINTED to BackgroundTone(
            darkLightness = 0.075 to 0.110,
            darkSaturation = 0.20 to 0.36,
            lightLightness = 0.920 to 0.950,
            lightSaturation = 0.10 to 0.20,
        ),
        PaletteTone.RICH to BackgroundTone(
            darkLightness = 0.095 to 0.140,
            darkSaturation = 0.32 to 0.50,
            lightLightness = 0.900 to 0.935,
            lightSaturation = 0.18 to 0.30,
        ),
    )

    // ---- 各角色 S/L 起始范围（themeColorRanges.ts:47-60） ----

    private data class RoleRanges(
        val saturation: Pair<Double, Double>,
        val lightness: Pair<Double, Double>,
    )

    private val PRIMARY_RANGES: Map<BuiltinThemeMode, RoleRanges> = mapOf(
        BuiltinThemeMode.LIGHT to RoleRanges(0.15 to 0.40, 0.10 to 0.20),
        BuiltinThemeMode.DARK to RoleRanges(0.10 to 0.30, 0.90 to 0.96),
    )

    private val ACCENT_RANGES: Map<BuiltinThemeMode, RoleRanges> = mapOf(
        BuiltinThemeMode.LIGHT to RoleRanges(0.55 to 0.88, 0.38 to 0.55),
        BuiltinThemeMode.DARK to RoleRanges(0.55 to 0.92, 0.55 to 0.72),
    )

    private val SECONDARY_RANGES: Map<BuiltinThemeMode, RoleRanges> = mapOf(
        BuiltinThemeMode.LIGHT to RoleRanges(0.15 to 0.45, 0.32 to 0.46),
        BuiltinThemeMode.DARK to RoleRanges(0.15 to 0.45, 0.62 to 0.76),
    )

    // ---- 命名表（themeNameTable.ts，逐字抄） ----

    private const val BUILTIN_NAME_SUFFIX = " Built-in"
    private const val NEUTRAL_SATURATION_CEILING = 0.12

    private val NEUTRAL_PREFIXES = listOf("月白", "银灰", "素墨", "雾白", "灰烬")

    private val HUE_PREFIX_BUCKETS: List<Pair<Double, List<String>>> = listOf(
        20.0 to listOf("绯色", "朱砂", "珊瑚", "赤霞", "石榴"),
        45.0 to listOf("琥珀", "暮光", "橘光", "麦浪", "落日"),
        70.0 to listOf("鎏金", "稻香", "芒草", "秋黄", "沙丘"),
        105.0 to listOf("新柳", "青苔", "竹露", "草木", "春芜"),
        165.0 to listOf("苔痕", "松风", "薄荷", "翡翠", "深林"),
        205.0 to listOf("潮汐", "雾蓝", "海雾", "天青", "汀洲"),
        255.0 to listOf("深蓝", "靛青", "群青", "远洋", "蓝调"),
        300.0 to listOf("紫烟", "丁香", "暮霭", "紫棠", "薰衣"),
        360.0 to listOf("玫影", "桃夭", "藕荷", "蔷薇", "胭脂"),
    )

    private val LIGHT_SUFFIXES = listOf("破晓", "晨光", "微光", "初雪", "白昼", "浮云", "日砂")
    private val DARK_SUFFIXES = listOf("子夜", "夜色", "深潭", "暗涌", "沉夜", "夜航", "星屑")

    // ---- 描述表（themeDescriptionTable.ts，60 句逐字抄） ----

    private enum class DescriptionFamily { WARM, GOLD, GREEN, BLUE, PURPLE, NEUTRAL }

    private val LIGHT_DESCRIPTIONS: Map<DescriptionFamily, List<String>> = mapOf(
        DescriptionFamily.WARM to listOf(
            "阳光落在窗台上，我忽然想起了那年的夏天。",
            "橘子汽水的味道，一整个下午都没有散去。",
            "我把心事晒在阳台，风一吹就全都跑掉了。",
            "热气腾腾的黄昏里，我们谁也没有先开口。",
            "像是握着一颗刚出炉的糖，烫手却舍不得放。",
        ),
        DescriptionFamily.GOLD to listOf(
            "麦田的风从耳边掠过，我又想起那条回家的路。",
            "午后的光把一切都镀成蜜色，时间慢得像糖。",
            "我把耳机塞进耳朵，整个世界忽然温柔起来。",
            "阳光穿过树叶的缝隙，落成一地细碎的金子。",
            "骑车经过那条巷子时，风里全是稻草的味道。",
        ),
        DescriptionFamily.GREEN to listOf(
            "雨后的青草味钻进鼻腔，我忽然想去很远的地方。",
            "推开窗，整个春天都涌了进来，我却说不出话。",
            "阳光落在叶脉上，我数着它们发呆了一下午。",
            "山间的风很轻，把我心里的褶皱也抚平了。",
            "我躺在草地上，听见这个世界安静地生长。",
        ),
        DescriptionFamily.BLUE to listOf(
            "海风吹过晾晒的白衬衫，那年夏天还没有结束。",
            "推开窗是一整片蓝，我忽然不想去任何地方了。",
            "天空蓝得不像话，我把心事都寄给了云。",
            "清晨的水汽还没散，整座城市都是浅蓝色的。",
            "我沿着河堤走了很久，风一直安静地陪着我。",
        ),
        DescriptionFamily.PURPLE to listOf(
            "薰衣草的味道很淡，像一段快要忘掉的心事。",
            "黄昏把云染成淡紫，我站在原地看了很久。",
            "梦醒之后天光微紫，我还留在昨夜的情节里。",
            "紫丁香开了满树，我却只想起一个人的名字。",
            "我把那封信折成四折，藏进了春天的口袋里。",
        ),
        DescriptionFamily.NEUTRAL to listOf(
            "灰白的天空下，我把耳机音量调到了最大。",
            "雾还没散，整座城市都像一张过曝的旧照片。",
            "我在空荡的房间里坐着，阳光淡得几乎没有。",
            "所有颜色都退去了，只剩下我和这段旋律。",
            "冬天的早晨很安静，我听见自己呼吸的声音。",
        ),
    )

    private val DARK_DESCRIPTIONS: Map<DescriptionFamily, List<String>> = mapOf(
        DescriptionFamily.WARM to listOf(
            "余温还留在指尖，夜色已经漫过了整条街。",
            "炉火熄了，我仍坐在原地想那句没说完的话。",
            "深夜的红灯一闪一闪，像谁没能说出口的爱。",
            "我在暗处点了一支烟，把心事烧成了灰烬。",
            "热烈过后的沉默里，我听见自己的心跳。",
        ),
        DescriptionFamily.GOLD to listOf(
            "路灯把影子拉得很长，我一个人慢慢走回去。",
            "夜里的灯火像散落的碎金，我数着它们入睡。",
            "我在黑暗里回忆那个金色的下午，久久不愿醒。",
            "秋夜的风有点凉，可我还是不想早点回家。",
            "屋里只剩一盏暖灯，照着我还没写完的信。",
        ),
        DescriptionFamily.GREEN to listOf(
            "深林的夜没有星星，只有风穿过树梢的声音。",
            "我在暗绿色的夜里走着，像走进了一场旧梦。",
            "夜露沾湿了裤脚，我还舍不得离开这片安静。",
            "整座山都睡着了，只有我的心事还醒着。",
            "苔藓在黑暗里悄悄蔓延，像我没说出口的思念。",
        ),
        DescriptionFamily.BLUE to listOf(
            "深夜的海把所有声音都吞掉了，只剩下呼吸。",
            "我在蓝色的黑暗里漂着，谁也找不到我。",
            "窗外下着雨，我把整个夜晚都听成了潮声。",
            "星星沉在海底，我在岸上等一句晚安。",
            "午夜的车窗外一片深蓝，我不想到站。",
        ),
        DescriptionFamily.PURPLE to listOf(
            "暮色一寸寸沉下去，我的心事也跟着沉了。",
            "紫色的夜里我做了一个梦，梦里全是旧时光。",
            "霓虹把夜色染成暧昧的紫，我不敢多看一眼。",
            "我在深夜反复播放这首歌，像反复回到了过去。",
            "月光穿过窗帘的缝，把我的孤独照得很轻。",
        ),
        DescriptionFamily.NEUTRAL to listOf(
            "黑白的夜里，我把所有心事都调成了静音。",
            "墨色漫过窗台，我在这片安静里慢慢沉下去。",
            "我关掉了所有的灯，只留这首歌陪着我。",
            "深夜的房间没有颜色，只有旋律在轻轻发亮。",
            "世界安静得像一场默片，而我是唯一的观众。",
        ),
    )

    // ---- 封面调色板分析（coverPaletteAnalysis.ts） ----

    private const val MIN_SUPPORT_SATURATION = 0.15
    private const val MIN_SUPPORT_HUE_DISTANCE = 25.0
    private const val MAX_SUPPORT_HUE_DISTANCE = 160.0
    private const val MONOCHROME_SATURATION_CEILING = 0.18

    // 饱和且亮度居中的颜色最能代表封面；近黑/近白的像素只描述背景。
    fun coverColorScore(hsl: Hsl): Double =
        hsl.s * max(0.0, 1.0 - abs(hsl.l - 0.5) * 1.2)

    fun <T> pickWeighted(random: Random, entries: List<Pair<T, Double>>): T {
        var total = 0.0
        for (entry in entries) total += entry.second
        var cursor = random.nextDouble() * total
        for (entry in entries) {
            cursor -= entry.second
            if (cursor <= 0.0) {
                return entry.first
            }
        }
        return entries[entries.size - 1].first
    }

    private fun <T> pickFrom(items: List<T>, random: Random): T =
        items[minOf(items.size - 1, floor(random.nextDouble() * items.size).toInt())]

    private fun pickBaseColor(parsedColors: List<Hsl>, random: Random): Hsl {
        if (parsedColors.isEmpty()) {
            // 无封面可读：直接随机取色，无封面的歌每次也各不相同。
            return Hsl(random.nextDouble() * 360.0, 0.35 + random.nextDouble() * 0.3, 0.5)
        }
        var scored = parsedColors[0]
        for (candidate in parsedColors) {
            if (coverColorScore(candidate) > coverColorScore(scored)) {
                scored = candidate
            }
        }
        return if (coverColorScore(scored) > 0.02) scored else parsedColors[0]
    }

    private fun pickSupportHue(parsedColors: List<Hsl>, base: Hsl): Double? {
        for (candidate in parsedColors) {
            if (candidate.s < MIN_SUPPORT_SATURATION) continue
            val distance = hueDistance(candidate.h, base.h)
            if (distance >= MIN_SUPPORT_HUE_DISTANCE && distance <= MAX_SUPPORT_HUE_DISTANCE) {
                return candidate.h
            }
        }
        return null
    }

    private fun pickScheme(baseSaturation: Double, supportHue: Double?, random: Random): HarmonyScheme {
        // 封面上真实的第二色是最“来自这张封面”的强调色来源。
        if (supportHue != null && random.nextDouble() < 0.65) {
            return HarmonyScheme.DUOTONE
        }
        return pickWeighted(random, listOf(
            HarmonyScheme.ANALOGOUS to 3.0,
            HarmonyScheme.COMPLEMENTARY to 2.0,
            HarmonyScheme.SPLIT to 2.0,
            HarmonyScheme.TRIADIC to 1.5,
            HarmonyScheme.MONOCHROME to if (baseSaturation < MONOCHROME_SATURATION_CEILING) 2.5 else 0.0,
        ))
    }

    private fun resolveAccentHue(
        scheme: HarmonyScheme,
        baseHue: Double,
        supportHue: Double?,
        random: Random,
    ): Double {
        val sign = if (random.nextDouble() < 0.5) -1.0 else 1.0
        return when (scheme) {
            HarmonyScheme.DUOTONE -> supportHue ?: baseHue
            HarmonyScheme.ANALOGOUS -> baseHue + sign * (20.0 + random.nextDouble() * 15.0)
            HarmonyScheme.COMPLEMENTARY -> baseHue + 150.0 + random.nextDouble() * 30.0
            HarmonyScheme.SPLIT -> baseHue + if (sign < 0) 150.0 else 210.0
            HarmonyScheme.TRIADIC -> baseHue + sign * 120.0
            HarmonyScheme.MONOCHROME -> baseHue
        }
    }

    fun analyzeCoverPalette(coverColors: List<Int>, random: Random): CoverPalette {
        val parsedColors = coverColors.map { argbToHsl(it) }
        val base = pickBaseColor(parsedColors, random)
        val supportHue = pickSupportHue(parsedColors, base)
        val scheme = pickScheme(base.s, supportHue, random)
        val accentJitter = if (scheme == HarmonyScheme.MONOCHROME) 0.0 else (random.nextDouble() - 0.5) * 16.0
        return CoverPalette(
            baseHue = normalizeHue(base.h),
            baseSaturation = base.s,
            baseLightness = base.l,
            accentHue = normalizeHue(resolveAccentHue(scheme, base.h, supportHue, random) + accentJitter),
            scheme = scheme,
            tone = pickWeighted(random, listOf(
                PaletteTone.INK to 3.0,
                PaletteTone.TINTED to 4.0,
                PaletteTone.RICH to 2.5,
            )),
        )
    }

    // ---- 命名 / 描述（themeNameTable.ts / themeDescriptionTable.ts） ----

    // 明暗两版共享同一前缀、只差后缀，格式为 前缀 + 后缀 + " Built-in"。
    fun pickBuiltinThemeNames(palette: CoverPalette, random: Random): Pair<String, String> {
        val prefixes = if (palette.baseSaturation < NEUTRAL_SATURATION_CEILING) {
            NEUTRAL_PREFIXES
        } else {
            HUE_PREFIX_BUCKETS.firstOrNull { palette.baseHue < it.first }?.second
                ?: HUE_PREFIX_BUCKETS[HUE_PREFIX_BUCKETS.size - 1].second
        }
        val prefix = pickFrom(prefixes, random)
        return Pair(
            prefix + pickFrom(LIGHT_SUFFIXES, random) + BUILTIN_NAME_SUFFIX,
            prefix + pickFrom(DARK_SUFFIXES, random) + BUILTIN_NAME_SUFFIX,
        )
    }

    private fun resolveDescriptionFamily(palette: CoverPalette): DescriptionFamily {
        if (palette.baseSaturation < NEUTRAL_SATURATION_CEILING) return DescriptionFamily.NEUTRAL
        if (palette.baseHue < 45.0 || palette.baseHue >= 330.0) return DescriptionFamily.WARM
        if (palette.baseHue < 105.0) return DescriptionFamily.GOLD
        if (palette.baseHue < 165.0) return DescriptionFamily.GREEN
        if (palette.baseHue < 255.0) return DescriptionFamily.BLUE
        return DescriptionFamily.PURPLE
    }

    // 明暗两版取同一色族各抽一句。
    fun pickBuiltinThemeDescriptions(palette: CoverPalette, random: Random): Pair<String, String> {
        val family = resolveDescriptionFamily(palette)
        return Pair(
            pickFrom(LIGHT_DESCRIPTIONS.getValue(family), random),
            pickFrom(DARK_DESCRIPTIONS.getValue(family), random),
        )
    }

    // ---- 单模式主题生成（generateBuiltinDualTheme.ts） ----

    private fun between(random: Random, range: Pair<Double, Double>): Double =
        range.first + random.nextDouble() * (range.second - range.first)

    private fun buildBackgroundColor(mode: BuiltinThemeMode, palette: CoverPalette, random: Random): Int {
        val tone = BACKGROUND_TONES.getValue(palette.tone)
        val lightnessRange = if (mode == BuiltinThemeMode.DARK) tone.darkLightness else tone.lightLightness
        val saturationRange = if (mode == BuiltinThemeMode.DARK) tone.darkSaturation else tone.lightSaturation
        // 主色越灰，背景饱和度越往 0.55 倍压；主色越饱和，背景越接近区间随机值。
        val coverWeight = 0.55 + 0.45 * clamp01(palette.baseSaturation / 0.7)
        return hslToArgb(Hsl(
            h = palette.baseHue,
            s = max(MIN_BACKGROUND_SATURATION, between(random, saturationRange) * coverWeight),
            l = between(random, lightnessRange),
        ))
    }

    private fun buildAccentColor(
        mode: BuiltinThemeMode,
        palette: CoverPalette,
        backgroundColor: Int,
        primaryColor: Int,
        random: Random,
    ): Int {
        val ranges = ACCENT_RANGES.getValue(mode)
        val saturation = between(random, ranges.saturation)
        val lightness = between(random, ranges.lightness)
        val accent = adjustLightnessForContrast(
            hslToArgb(Hsl(palette.accentHue, saturation, lightness)),
            backgroundColor,
            ACCENT_MIN_CONTRAST,
        )
        // 单色/近似和声的强调色可能撞上主文字色：色相距 < 15° 且与主文字色
        // 对比度 < 1.3 时，强调色相 +40° 后重新做对比度求解。
        val collidesWithPrimary = hueDistance(palette.accentHue, palette.baseHue) < 15.0 &&
            contrastRatio(accent, primaryColor) < 1.3
        if (!collidesWithPrimary) {
            return accent
        }
        return adjustLightnessForContrast(
            hslToArgb(Hsl(normalizeHue(palette.accentHue + 40.0), saturation, lightness)),
            backgroundColor,
            ACCENT_MIN_CONTRAST,
        )
    }

    private fun buildModeTheme(
        mode: BuiltinThemeMode,
        palette: CoverPalette,
        random: Random,
        name: String,
        description: String,
    ): BuiltinTheme {
        val backgroundColor = buildBackgroundColor(mode, palette, random)
        val primaryRanges = PRIMARY_RANGES.getValue(mode)
        val primaryColor = adjustLightnessForContrast(
            hslToArgb(Hsl(
                h = palette.baseHue,
                s = between(random, primaryRanges.saturation),
                l = between(random, primaryRanges.lightness),
            )),
            backgroundColor,
            PRIMARY_MIN_CONTRAST,
        )
        val accentColor = buildAccentColor(mode, palette, backgroundColor, primaryColor, random)
        val secondaryRanges = SECONDARY_RANGES.getValue(mode)
        val secondaryColor = adjustLightnessForContrast(
            hslToArgb(Hsl(
                h = lerpHue(palette.baseHue, palette.accentHue, 0.2 + random.nextDouble() * 0.4),
                s = between(random, secondaryRanges.saturation),
                l = between(random, secondaryRanges.lightness),
            )),
            backgroundColor,
            SECONDARY_MIN_CONTRAST,
        )
        return BuiltinTheme(
            name = name,
            description = description,
            backgroundColor = backgroundColor,
            primaryColor = primaryColor,
            accentColor = accentColor,
            secondaryColor = secondaryColor,
        )
    }

    fun generateBuiltinDualTheme(coverColors: List<Int>, random: Random): BuiltinDualTheme {
        val palette = analyzeCoverPalette(coverColors, random)
        val names = pickBuiltinThemeNames(palette, random)
        val descriptions = pickBuiltinThemeDescriptions(palette, random)
        return BuiltinDualTheme(
            light = buildModeTheme(BuiltinThemeMode.LIGHT, palette, random, names.first, descriptions.first),
            dark = buildModeTheme(BuiltinThemeMode.DARK, palette, random, names.second, descriptions.second),
        )
    }

    // ---- 方案 B：四色直拼 ColorScheme（播放页专用，不动 App 其余页面） ----
    //
    // 背景 → background/surface；主文字色 → primary/onBackground/onSurface；
    // 强调色 → secondary/tertiary；次要文字色 → onSurfaceVariant。
    // surfaceContainer* 由背景色按亮度阶梯推导（暗版 +4/+8/+12/+16%，
    // 亮版 −4/−8/−12/−16%）；error 系列沿用现有 scheme。

    private fun shiftLightness(argb: Int, delta: Double): Int {
        val hsl = argbToHsl(argb)
        return hslToArgb(hsl.copy(l = (hsl.l + delta).coerceIn(0.0, 1.0)))
    }

    fun colorScheme(theme: BuiltinTheme, dark: Boolean, fallback: ColorScheme): ColorScheme {
        val background = Color(theme.backgroundColor)
        val primary = Color(theme.primaryColor)
        val accent = Color(theme.accentColor)
        val secondary = Color(theme.secondaryColor)
        // 容器阶梯：暗版逐级提亮、亮版逐级压暗；lowest 与背景同色。
        val containerLow = Color(shiftLightness(theme.backgroundColor, if (dark) 0.04 else -0.04))
        val container = Color(shiftLightness(theme.backgroundColor, if (dark) 0.08 else -0.08))
        val containerHigh = Color(shiftLightness(theme.backgroundColor, if (dark) 0.12 else -0.12))
        val containerHighest = Color(shiftLightness(theme.backgroundColor, if (dark) 0.16 else -0.16))
        // 主文字色与背景的对比度 ≥ 9，背景色写在主文字色上同样 ≥ 9，可作 on 色。
        val onPrimaryLike = background
        val accentContainer = Color(shiftLightness(theme.accentColor, if (dark) 0.12 else -0.12))
        val accentContainerHigh = Color(shiftLightness(theme.accentColor, if (dark) 0.20 else -0.20))
        // surfaceBright 在亮版是最亮的一档（比背景再亮一点），不在容器阶梯的暗端。
        val surfaceBright = Color(shiftLightness(theme.backgroundColor, if (dark) 0.16 else 0.04))
        val containerLowest = Color(shiftLightness(theme.backgroundColor, if (dark) 0.0 else 0.04))
        return if (dark) {
            darkColorScheme(
                primary = primary,
                onPrimary = onPrimaryLike,
                primaryContainer = accent,
                onPrimaryContainer = onPrimaryLike,
                secondary = accent,
                onSecondary = onPrimaryLike,
                secondaryContainer = accentContainer,
                onSecondaryContainer = onPrimaryLike,
                tertiary = accent,
                onTertiary = onPrimaryLike,
                tertiaryContainer = accentContainerHigh,
                onTertiaryContainer = onPrimaryLike,
                background = background,
                onBackground = primary,
                surface = background,
                onSurface = primary,
                surfaceVariant = container,
                onSurfaceVariant = secondary,
                surfaceTint = primary,
                inverseSurface = primary,
                inverseOnSurface = background,
                inversePrimary = accent,
                surfaceDim = Color(shiftLightness(theme.backgroundColor, -0.03)),
                surfaceBright = surfaceBright,
                surfaceContainerLowest = containerLowest,
                surfaceContainerLow = containerLow,
                surfaceContainer = container,
                surfaceContainerHigh = containerHigh,
                surfaceContainerHighest = containerHighest,
                outline = secondary.copy(alpha = 0.5f),
                outlineVariant = secondary.copy(alpha = 0.25f),
                scrim = Color.Black,
                error = fallback.error,
                onError = fallback.onError,
                errorContainer = fallback.errorContainer,
                onErrorContainer = fallback.onErrorContainer,
            )
        } else {
            lightColorScheme(
                primary = primary,
                onPrimary = onPrimaryLike,
                primaryContainer = accent,
                onPrimaryContainer = onPrimaryLike,
                secondary = accent,
                onSecondary = onPrimaryLike,
                secondaryContainer = accentContainer,
                onSecondaryContainer = onPrimaryLike,
                tertiary = accent,
                onTertiary = onPrimaryLike,
                tertiaryContainer = accentContainerHigh,
                onTertiaryContainer = onPrimaryLike,
                background = background,
                onBackground = primary,
                surface = background,
                onSurface = primary,
                surfaceVariant = container,
                onSurfaceVariant = secondary,
                surfaceTint = primary,
                inverseSurface = primary,
                inverseOnSurface = background,
                inversePrimary = accent,
                surfaceDim = Color(shiftLightness(theme.backgroundColor, -0.10)),
                surfaceBright = surfaceBright,
                surfaceContainerLowest = containerLowest,
                surfaceContainerLow = containerLow,
                surfaceContainer = container,
                surfaceContainerHigh = containerHigh,
                surfaceContainerHighest = containerHighest,
                outline = secondary.copy(alpha = 0.5f),
                outlineVariant = secondary.copy(alpha = 0.25f),
                scrim = Color.Black,
                error = fallback.error,
                onError = fallback.onError,
                errorContainer = fallback.errorContainer,
                onErrorContainer = fallback.onErrorContainer,
            )
        }
    }
}
