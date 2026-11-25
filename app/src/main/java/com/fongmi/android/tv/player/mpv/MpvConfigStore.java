package com.fongmi.android.tv.player.mpv;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.util.AppContextHolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// AVBox 适配：webhtv MpvConfigStore 的功能子集，与原版保持同一目录布局，
// 同一份默认 mpv.conf、同一套 option 解析与 GPU 处理检测逻辑；未移植的是
// 配置档案（profiles）与自定义按钮脚本管理（AVBox 没有对应设置入口），
// ensureCustomButtonScript 因此为空操作。原版靠 Prefers 记录的档案类型在
// 这里简化为：已有非空 mpv.conf 一律视为用户文件、永不覆盖。
public final class MpvConfigStore {

    public static final String TARGET_MPV_CONF = "mpv.conf";
    public static final String TARGET_INPUT_CONF = "input.conf";
    public static final String TARGET_SCRIPTS = "scripts";
    public static final String CUSTOM_BUTTON_MESSAGE = "webhtv-custom-button";
    public static final String CUSTOM_BUTTON_STATE_PROPERTY = "user-data/webhtv-custom-buttons";

    private static final String CONFIG_DIR = "mpv";
    private static final String CONFIG_FILE = "mpv.conf";
    private static final String PROFILE_DIR = "profiles";

    private MpvConfigStore() {
    }

    public static File configDir() {
        Context context = AppContextHolder.context();
        if (context == null) throw new IllegalStateException("app context not installed");
        return configDir(context);
    }

    public static File configDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        ensureSubDir(dir, TARGET_SCRIPTS);
        ensureSubDir(dir, "script-opts");
        ensureSubDir(dir, "fonts");
        ensureSubDir(dir, "shaders");
        ensureSubDir(dir, PROFILE_DIR);
        return dir;
    }

    public static File configFile() {
        return new File(configDir(), CONFIG_FILE);
    }

    public static File scriptsDir() {
        return new File(configDir(), TARGET_SCRIPTS);
    }

    private static void ensureSubDir(File base, String name) {
        File dir = new File(base, name);
        if (!dir.exists()) dir.mkdirs();
    }

    public static synchronized void ensureReady() {
        File file = configFile();
        if (!file.isFile() || file.length() == 0) {
            try {
                writeText(file, defaultConfig());
            } catch (IOException ignored) {
            }
        }
    }

    public static void ensureCustomButtonScript() {
        // 自定义按钮脚本未移植到 AVBox，无脚本可写。
    }

    public static boolean hasGpuVideoProcessing() {
        File[] scripts = scriptsDir().listFiles(file -> file.isFile() && isUserScriptName(file.getName()));
        if (scripts != null && scripts.length > 0) return true;
        try {
            return containsGpuVideoProcessing(readText(configFile()));
        } catch (IOException e) {
            return true;
        }
    }

    public static String getOptionValue(String name) {
        if (TextUtils.isEmpty(name)) return "";
        try {
            return findOptionValue(readText(configFile()), name);
        } catch (IOException e) {
            return "";
        }
    }

    static String findOptionValue(String content, String name) {
        if (content == null || name == null) return "";
        String expected = name.trim().toLowerCase(Locale.ROOT);
        String result = "";
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            if (line.startsWith("--")) line = line.substring(2);
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            String option = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (expected.equals(option)) result = line.substring(separator + 1).trim();
        }
        return result;
    }

    static boolean containsGpuVideoProcessing(String content) {
        if (content == null || content.isEmpty()) return false;
        String[] lines = content.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim().toLowerCase(Locale.ROOT);
            if (line.isEmpty() || line.startsWith("#")) continue;
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            int separator = line.indexOf('=');
            String option = (separator < 0 ? line : line.substring(0, separator)).trim();
            String value = separator < 0 ? "" : line.substring(separator + 1).trim();
            if ((option.equals("vf") || option.startsWith("vf-") || option.equals("lavfi-complex")) && !value.isEmpty()) return true;
            if (option.startsWith("glsl-shader") || option.equals("icc-profile") || option.startsWith("tone-mapping")) return true;
            if ((option.equals("deband") || option.equals("interpolation")) && !isDisabledOptionValue(value)) return true;
            if (option.equals("video-output-levels")) return true;
            if (option.equals("scale") || option.equals("cscale") || option.equals("dscale") || option.equals("tscale")) return true;
        }
        return false;
    }

    private static boolean isDisabledOptionValue(String value) {
        return value.isEmpty() || value.equals("no") || value.equals("false") || value.equals("0");
    }

    private static boolean isUserScriptName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".lua");
    }

    static String defaultConfig() {
        return "# WebHTV MPV default config\n"
                + "# Loaded by libmpv from files/mpv/mpv.conf. Keep Android-only output options in app code.\n"
                + "\n"
                + "profile=fast\n"
                + "http-allow-redirect=yes\n"
                + "sub-ass=yes\n"
                + "sub-ass-override=" + MpvSubtitleStylePolicy.ASS_OVERRIDE + "\n"
                + "embeddedfonts=yes\n"
                + "sub-fix-timing=yes\n"
                + "sub-use-margins=yes\n"
                + "sub-font-provider=fontconfig\n"
                + "volume-max=100\n";
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
