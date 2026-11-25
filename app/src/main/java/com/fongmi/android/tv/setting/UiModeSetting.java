package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/**
 * 双界面模式 + AVBox 主题的全部偏好项（批次 X / 双界面 P1）。
 * 固定契约（W2 按此调用，签名不可改）：
 * ui_mode（默认 0=经典页面）、av_theme_mode（默认 0=自动）、av_pure_black（默认 false）、
 * av_seed_source（默认 0=跟随海报）、av_preset_seed（默认 5=蓝）、
 * av_color_style（升级版30 新增，默认 0=柔和）。
 * normalize* 为纯函数：把任意存入值收敛到合法区间，单测直接覆盖。
 * 升级版30：自定义种子来源已删除——存量值 2 经 normalizeSeedSource 一律回落预设，
 * 自定义色值不再被读取（av_custom_seed 存量键残留无害，永不读取）。
 */
public class UiModeSetting {

    public static final int MODE_CURRENT = 0;
    public static final int MODE_AVBOX = 1;

    public static final int THEME_AUTO = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    public static final int SEED_FOLLOW = 0;
    public static final int SEED_PRESET = 1;
    // 升级版30 已删除自定义来源：存量值 2 见 normalizeSeedSource（回落预设），不再设常量。

    public static final int DEFAULT_PRESET_SEED_INDEX = 5; // 蓝
    /** 升级版30：预设色板 8 → 16 色（前 8 色顺序与色值不变，新增只追加）。 */
    public static final int PRESET_COUNT = 16;

    /** 配色风格（升级版30；升级版31 补齐鲜艳 / 表现力；值域与 AvThemeEngine 的 STYLE_* 同值：0=柔和 / 1=中性 / 2=单色 / 3=鲜艳 / 4=表现力）。 */
    public static final int COLOR_STYLE_SOFT = 0;
    public static final int COLOR_STYLE_NEUTRAL = 1;
    public static final int COLOR_STYLE_MONOCHROME = 2;
    public static final int COLOR_STYLE_VIBRANT = 3;
    public static final int COLOR_STYLE_EXPRESSIVE = 4;
    public static final int DEFAULT_COLOR_STYLE = COLOR_STYLE_SOFT;

    private static final String KEY_UI_MODE = "ui_mode";
    private static final String KEY_THEME_MODE = "av_theme_mode";
    private static final String KEY_PURE_BLACK = "av_pure_black";
    private static final String KEY_SEED_SOURCE = "av_seed_source";
    private static final String KEY_PRESET_SEED = "av_preset_seed";
    private static final String KEY_COLOR_STYLE = "av_color_style";

    private UiModeSetting() {
    }

    // ---------------------------------------------------------------
    // 纯逻辑（无 Android 依赖，单测覆盖）
    // ---------------------------------------------------------------

    public static int normalizeUiMode(int mode) {
        return mode == MODE_AVBOX ? MODE_AVBOX : MODE_CURRENT;
    }

    public static int normalizeThemeMode(int mode) {
        return mode == THEME_LIGHT || mode == THEME_DARK ? mode : THEME_AUTO;
    }

    /**
     * 升级版30：存量自定义（旧值 2，已无常量）一律回落预设（1），自定义色值被丢弃；
     * 未知值仍回跟随海报（0），与旧口径一致。回落后的预设色取调用方另读的
     * 预设下标（未曾选过预设者即默认下标 5=蓝），故存量自定义用户的默认落点是蓝。
     */
    public static int normalizeSeedSource(int source) {
        return source == SEED_PRESET || source == 2 ? SEED_PRESET : SEED_FOLLOW;
    }

    public static int normalizePresetIndex(int index) {
        return index >= 0 && index < PRESET_COUNT ? index : DEFAULT_PRESET_SEED_INDEX;
    }

    /** 配色风格归一化：非法值一律回默认柔和（与 AvThemeEngine.normalizeColorStyle 同值域）。 */
    public static int normalizeColorStyle(int style) {
        return style == COLOR_STYLE_NEUTRAL || style == COLOR_STYLE_MONOCHROME
                || style == COLOR_STYLE_VIBRANT || style == COLOR_STYLE_EXPRESSIVE ? style : COLOR_STYLE_SOFT;
    }

    // ---------------------------------------------------------------
    // 界面模式
    // ---------------------------------------------------------------

    // AVBox 打桩：本工程就是 AVBox，默认即 AVBOX 模式（音乐页的 AVBox 主题分支才走得通）；
    // 原音乐壳 原版默认 MODE_CURRENT（壁纸层等 FongMi 形态）。存量值仍以 Prefers 为准。
    public static int getUiMode() {
        return normalizeUiMode(Prefers.getInt(KEY_UI_MODE, MODE_AVBOX));
    }

    public static void putUiMode(int mode) {
        Prefers.put(KEY_UI_MODE, normalizeUiMode(mode));
    }

    public static boolean isAvBox() {
        return getUiMode() == MODE_AVBOX;
    }

    // ---------------------------------------------------------------
    // AVBox 主题
    // ---------------------------------------------------------------

    public static int getThemeMode() {
        return normalizeThemeMode(Prefers.getInt(KEY_THEME_MODE, THEME_AUTO));
    }

    public static void putThemeMode(int mode) {
        Prefers.put(KEY_THEME_MODE, normalizeThemeMode(mode));
    }

    public static boolean isPureBlack() {
        return Prefers.getBoolean(KEY_PURE_BLACK, false);
    }

    public static void putPureBlack(boolean pureBlack) {
        Prefers.put(KEY_PURE_BLACK, pureBlack);
    }

    public static int getSeedSource() {
        return normalizeSeedSource(Prefers.getInt(KEY_SEED_SOURCE, SEED_FOLLOW));
    }

    public static void putSeedSource(int source) {
        Prefers.put(KEY_SEED_SOURCE, normalizeSeedSource(source));
    }

    public static int getPresetSeedIndex() {
        return normalizePresetIndex(Prefers.getInt(KEY_PRESET_SEED, DEFAULT_PRESET_SEED_INDEX));
    }

    public static void putPresetSeedIndex(int index) {
        Prefers.put(KEY_PRESET_SEED, normalizePresetIndex(index));
    }

    // ---------------------------------------------------------------
    // AVBox 配色风格（升级版30：柔和默认；中性/单色新增；升级版31：鲜艳/表现力新增，inline 行展示）
    // ---------------------------------------------------------------

    public static int getColorStyle() {
        return normalizeColorStyle(Prefers.getInt(KEY_COLOR_STYLE, DEFAULT_COLOR_STYLE));
    }

    public static void putColorStyle(int style) {
        Prefers.put(KEY_COLOR_STYLE, normalizeColorStyle(style));
    }
}
