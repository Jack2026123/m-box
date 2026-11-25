package com.fongmi.android.tv.setting;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

// AVBox 适配垫片：只提供 mpvplayer 包引用的去广告开关，AVBox 默认关闭
// （HLS 播放列表不做广告段剥离，原样透传给 libmpv）。
// 移植扩展：wrapDisplay 供音乐 UI 的 BaseActivity 包裹语言/显示——
// AVBox 自有语言体系不改，这里只做简繁语言透传（读 com.fongmi 侧自己的键）。
public final class Setting {
    private static final String KEY_LANGUAGE = "setting_language";
    private static final int LANGUAGE_FOLLOW_SYSTEM = 0;
    private static final int LANGUAGE_SIMPLIFIED = 1;
    private static final int LANGUAGE_TRADITIONAL = 2;

    private Setting() {
    }

    public static String getUa() {
        return "";
    }

    public static void putUa(String ua) {
    }

    public static boolean isAdblock() {
        return false;
    }

    public static void putAdblock(boolean adblock) {
    }

    /** 音乐 UI 语言包裹：读 原音乐壳 侧语言键（0 系统/1 简/2 繁），与 AVBox 语言互不影响。 */
    public static Context wrapDisplay(Context context) {
        try {
            int language = com.github.catvod.utils.Prefers.getInt(KEY_LANGUAGE, LANGUAGE_FOLLOW_SYSTEM);
            if (language == LANGUAGE_FOLLOW_SYSTEM) return context;
            Locale locale = language == LANGUAGE_TRADITIONAL ? Locale.TRADITIONAL_CHINESE : Locale.SIMPLIFIED_CHINESE;
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(locale);
            config.setLocales(new LocaleList(locale));
            return context.createConfigurationContext(config);
        } catch (Throwable e) {
            return context;
        }
    }
}
