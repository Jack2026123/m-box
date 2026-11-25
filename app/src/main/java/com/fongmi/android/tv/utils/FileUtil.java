package com.fongmi.android.tv.utils;

import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Path;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

// AVBox 适配垫片：只实现 mpvplayer 包用到的目录大小与存储空间查询，逻辑与原版等价。
public final class FileUtil {

    private static final int COPY_BUFFER_SIZE = 8192;

    private FileUtil() {
    }

    public static long getDirectorySize(File directory) {
        if (directory == null || !directory.exists()) return 0;
        if (directory.isFile()) return directory.length();
        long total = 0;
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) total += getDirectorySize(child);
        }
        return total;
    }

    public static StorageSpace getStorageSpace(File directory) {
        try {
            StatFs stat = new StatFs(directory.getAbsolutePath());
            long total = stat.getTotalBytes();
            long available = stat.getAvailableBytes();
            return new StorageSpace(available > 0, available, total);
        } catch (Throwable e) {
            return new StorageSpace(false, 0, 0);
        }
    }

    public record StorageSpace(boolean available, long availableBytes, long totalBytes) {
    }

    public static void copyAtomically(Uri source, File target) throws IOException {
        try (InputStream input = openInputStream(source)) {
            copyAtomically(input, target);
        }
    }

    public static void copyAtomically(File source, File target) throws IOException {
        if (source.getCanonicalFile().equals(target.getCanonicalFile())) return;
        try (InputStream input = new FileInputStream(source)) {
            copyAtomically(input, target);
        }
    }

    public static void writeAtomically(byte[] data, File target) throws IOException {
        try (InputStream input = new ByteArrayInputStream(data)) {
            copyAtomically(input, target);
        }
    }

    private static InputStream openInputStream(Uri uri) throws IOException {
        if (App.get() == null) throw new IOException("No application context");
        InputStream input = App.get().getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Unable to open input stream");
        return input;
    }

    private static void copyAtomically(InputStream input, File target) throws IOException {
        File temp = createTempFile(target);
        try {
            copyToFile(input, temp);
            Path.move(temp, target);
        } finally {
            Path.clear(temp);
        }
    }

    private static File createTempFile(File target) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("Target has no parent directory");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Unable to create target directory");
        return File.createTempFile("copy-", ".tmp", parent);
    }

    private static void copyToFile(InputStream input, File target) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            transfer(input, output, Long.MAX_VALUE);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void transfer(InputStream input, OutputStream output, long maxBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long totalBytes = 0L;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
            totalBytes += count;
            if (totalBytes > maxBytes) throw new IOException("File is too large");
            output.write(buffer, 0, count);
        }
    }

    public static String getDisplayName(Uri uri) {
        return getDisplayName(uri, uri.toString());
    }

    public static String getDisplayName(Uri uri, String fallback) {
        String name = android.content.ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme()) ? queryDisplayName(uri) : null;
        if (!TextUtils.isEmpty(name)) return name;
        name = uri.getLastPathSegment();
        return TextUtils.isEmpty(name) ? fallback : name;
    }

    private static String queryDisplayName(Uri uri) {
        String[] projection = {OpenableColumns.DISPLAY_NAME};
        try (Cursor cursor = App.get().getContentResolver().query(uri, projection, null, null, null)) {
            return cursor == null || !cursor.moveToFirst() ? null : cursor.getString(0);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
