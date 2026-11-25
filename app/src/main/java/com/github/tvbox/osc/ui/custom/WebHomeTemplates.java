package com.github.tvbox.osc.ui.custom;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 1.17 WebHome 模板：内置模板 + 本地导入的模板。
 *
 * <p>模板是单个自包含 .html 文件（css/js 写在文件里）。内置模板在 assets/webhome/index.html；
 * 用户导入的放在 filesDir/webhome/templates/ 下。当前选中的模板名存在 Prefers（webhome_template，
 * 空 = 内置默认）。模板列表 = 内置 + 扫描目录里的 .html/.htm。
 */
public class WebHomeTemplates {

    public static final String ASSET_URL = "file:///android_asset/webhome/index.html";

    private static final String KEY_CURRENT = "webhome_template";
    private static final String DIR = "webhome/templates";

    public static class Template {
        public final String name; // "" = 内置默认模板
        public final String fileName;

        public Template(String name, String fileName) {
            this.name = name;
            this.fileName = fileName;
        }

        public boolean isDefault() {
            return TextUtils.isEmpty(name);
        }
    }

    /** 导入结果：first = 切过去的模板名（第一个），count = 导入个数。 */
    public static class ImportResult {
        public final String first;
        public final int count;

        public ImportResult(String first, int count) {
            this.first = first;
            this.count = count;
        }
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static List<Template> list(Context ctx) {
        List<Template> out = new ArrayList<>();
        out.add(new Template("", ""));
        File d = dir(ctx);
        File[] files = d.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                String n = f.getName();
                if (f.isFile() && (n.endsWith(".html") || n.endsWith(".htm"))) {
                    out.add(new Template(n.substring(0, n.lastIndexOf('.')), n));
                }
            }
        }
        return out;
    }

    public static String current(Context ctx) {
        return Prefers.getString(KEY_CURRENT, "");
    }

    public static void setCurrent(Context ctx, String name) {
        Prefers.put(KEY_CURRENT, name == null ? "" : name);
    }

    /** 1.23：删除导入的模板文件。内置默认模板（名为空）删不了，返回 false；删的是当前选中模板时，切回内置默认。 */
    public static boolean delete(Context ctx, String name) {
        if (TextUtils.isEmpty(name)) return false;
        boolean ok = false;
        File f = new File(dir(ctx), name + ".html");
        if (f.exists()) ok = f.delete();
        File f2 = new File(dir(ctx), name + ".htm");
        if (f2.exists()) ok = f2.delete() || ok;
        if (ok && name.equals(current(ctx))) setCurrent(ctx, "");
        return ok;
    }

    /** 模板名对应的加载地址；名为空或文件没了都回内置模板。1.22：用 Uri.fromFile 拼地址，中文文件名正确编码。 */
    public static String urlFor(Context ctx, String name) {
        if (TextUtils.isEmpty(name)) return ASSET_URL;
        File f = new File(dir(ctx), name + ".html");
        if (!f.exists()) f = new File(dir(ctx), name + ".htm");
        if (!f.exists()) return ASSET_URL;
        return Uri.fromFile(f).toString();
    }

    /**
     * 从系统文件选择器拿到的 Uri 导入：.html/.htm 拷一份进模板目录；.zip 解开，把里面的
     * .html/.htm 全拷进来。返回第一个导入的模板名 + 个数；失败（不是 html/zip、zip 里没
     * 模板、读失败）返回 null。同名直接覆盖（就是更新同一个模板）。
     */
    public static ImportResult importFrom(Context ctx, Uri uri) {
        if (uri == null) return null;
        try {
            String display = queryName(ctx, uri);
            if (TextUtils.isEmpty(display)) display = "template.html";
            String lower = display.toLowerCase();
            if (lower.endsWith(".zip")) return importZip(ctx, uri);
            if (!lower.endsWith(".html") && !lower.endsWith(".htm")) return null;
            String base = sanitizeName(display);
            if (TextUtils.isEmpty(base)) base = "template";
            File out = new File(dir(ctx), base + ".html");
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) return null;
                copy(in, os);
            }
            return new ImportResult(base, 1);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 解 .zip 模板包：里面的 .html/.htm（任意层级，只取文件名）全拷进模板目录。 */
    private static ImportResult importZip(Context ctx, Uri uri) {
        String first = null;
        int count = 0;
        try {
            InputStream raw = ctx.getContentResolver().openInputStream(uri);
            if (raw == null) return null;
            try (ZipInputStream zin = new ZipInputStream(raw)) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    int slash = name.lastIndexOf('/');
                    if (slash >= 0) name = name.substring(slash + 1);
                    String lower = name.toLowerCase();
                    if (!lower.endsWith(".html") && !lower.endsWith(".htm")) continue;
                    String base = sanitizeName(name);
                    if (TextUtils.isEmpty(base)) continue;
                    File out = new File(dir(ctx), base + ".html");
                    try (OutputStream os = new FileOutputStream(out)) {
                        copy(zin, os);
                    }
                    if (first == null) first = base;
                    count++;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        if (count == 0) return null;
        return new ImportResult(first, count);
    }

    /** 文件名去后缀、去非法字符（只留字母数字下划线中文点横）。 */
    private static String sanitizeName(String display) {
        String base = display;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base.replaceAll("[^A-Za-z0-9_\\u4e00-\\u9fa5.-]", "_");
    }

    private static void copy(InputStream in, OutputStream os) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }

    private static String queryName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
