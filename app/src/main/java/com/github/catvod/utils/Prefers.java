package com.github.catvod.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.tvbox.osc.base.App;

// AVBox 适配垫片：webhtv 版 Prefers 基于 FongMi 的 SharedPreferences 存储，
// 这里用同名同签名实现，底层换成 AVBox 自己的一个 SharedPreferences 文件，
// 只承载本地源功能自己的键（local_source_*），与其他设置互不干扰。
public class Prefers {

    private static SharedPreferences prefs() {
        Context context = App.getInstance();
        if (context == null) return null;
        return context.getSharedPreferences("avbox_prefers", Context.MODE_PRIVATE);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences prefs = prefs();
        return prefs == null ? defaultValue : prefs.getBoolean(key, defaultValue);
    }

    public static String getString(String key) {
        return getString(key, "");
    }

    public static String getString(String key, String defaultValue) {
        SharedPreferences prefs = prefs();
        if (prefs == null) return defaultValue;
        String value = prefs.getString(key, defaultValue);
        return value == null ? defaultValue : value;
    }

    public static int getInt(String key, int defaultValue) {
        SharedPreferences prefs = prefs();
        return prefs == null ? defaultValue : prefs.getInt(key, defaultValue);
    }

    public static void put(String key, Object value) {
        SharedPreferences prefs = prefs();
        if (prefs == null || value == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else editor.putString(key, String.valueOf(value));
        editor.apply();
    }

    public static void remove(String key) {
        SharedPreferences prefs = prefs();
        if (prefs != null) prefs.edit().remove(key).apply();
    }
}
