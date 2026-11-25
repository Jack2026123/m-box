package com.github.catvod.utils;

import android.content.Context;
import android.os.StatFs;

import com.github.tvbox.osc.util.AppContextHolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// AVBox 适配垫片：只实现 mpvplayer 包用到的缓存目录与应用标签，
// 目录落在 AVBox 自身的 cacheDir 下，与原版语义一致。
public class Path {

    public static List<File> list(File dir) {
        File[] children = dir == null ? null : dir.listFiles();
        if (children == null) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(children));
    }

    public static File cache(String name) {
        Context context = AppContextHolder.context();
        File base = context == null ? new File(System.getProperty("java.io.tmpdir", "/tmp"), "avbox-cache") : context.getCacheDir();
        File dir = new File(base, name);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static String label() {
        Context context = AppContextHolder.context();
        if (context == null) return "AVBox";
        try {
            CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
            return label == null ? "AVBox" : label.toString();
        } catch (Throwable e) {
            return "AVBox";
        }
    }

    public static File files(String name) {
        Context context = AppContextHolder.context();
        File base = context == null ? new File(System.getProperty("java.io.tmpdir", "/tmp"), "avbox-files") : context.getFilesDir();
        File file = new File(base, name);
        return file;
    }

    public static File mpv() {
        Context context = AppContextHolder.context();
        File base = context == null ? new File(System.getProperty("java.io.tmpdir", "/tmp"), "avbox-files") : context.getFilesDir();
        File dir = new File(base, "mpv");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File mpv(String name) {
        return new File(mpv(), name);
    }

    public static File mpvCache() {
        Context context = AppContextHolder.context();
        File base = context == null ? new File(System.getProperty("java.io.tmpdir", "/tmp"), "avbox-cache") : context.getCacheDir();
        File dir = new File(base, "mpv");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File exoCache() {
        Context context = AppContextHolder.context();
        File base = context == null ? new File(System.getProperty("java.io.tmpdir", "/tmp"), "avbox-cache") : context.getCacheDir();
        File dir = new File(base, "exoplayer");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File font() {
        File dir = new File(mpv(), "fonts");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File thunder(String name) {
        Context context = AppContextHolder.context();
        File base = context == null ? new File(System.getProperty("java.io.tmpdir", "/tmp"), "avbox-cache") : context.getCacheDir();
        File dir = new File(base, "thunder");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    public static boolean exists(File file) {
        return file != null && file.exists();
    }

    public static String read(File file) {
        if (file == null || !file.exists()) return "";
        try (InputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            int count;
            while (offset < data.length && (count = input.read(data, offset, data.length - offset)) != -1) offset += count;
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static long size(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) total += size(child);
        }
        return total;
    }

    public static long available(File file) {
        try {
            StatFs stat = new StatFs(file.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Throwable e) {
            return 0;
        }
    }

    public static void clear(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) clear(child);
            }
        }
        file.delete();
    }

    public static void move(File source, File target) throws IOException {
        if (source == null || target == null) throw new IOException("null file");
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Unable to create target directory");
        if (source.renameTo(target)) return;
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        }
        if (!source.delete()) throw new IOException("Unable to delete source after copy");
    }
}
