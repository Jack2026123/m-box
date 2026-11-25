package com.github.catvod.crawler.diagnostics;

// AVBox 适配垫片：诊断分类位掩码与 webhtv 同构；accepts 恒 false（诊断关闭时
// 不追加任何组件日志级别），nativePrefix 原样返回。
public final class DiagnosticCategories {

    public enum Category {
        VIDEO,
        AUDIO,
        SUBTITLE,
        NETWORK,
        SYSTEM
    }

    public static final int ALL = -1;

    private DiagnosticCategories() {
    }

    public static boolean accepts(int categories, Category category) {
        return false;
    }

    public static String nativePrefix(String prefix) {
        return prefix == null ? "" : prefix;
    }
}
