package com.github.catvod.utils;

import android.os.StatFs;

import java.io.File;
import java.security.MessageDigest;

// AVBox 适配垫片：只实现 mpvplayer 包用到的 md5 与目录/空间查询。
public class Util {

    public static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Throwable e) {
            return String.valueOf(text == null ? 0 : text.hashCode());
        }
    }

    public static long getDirectorySize(File directory) {
        return com.fongmi.android.tv.utils.FileUtil.getDirectorySize(directory);
    }

    public static StorageSpace getStorageSpace(File directory) {
        com.fongmi.android.tv.utils.FileUtil.StorageSpace space = com.fongmi.android.tv.utils.FileUtil.getStorageSpace(directory);
        return new StorageSpace(space.available(), space.availableBytes(), space.totalBytes());
    }

    public record StorageSpace(boolean available, long availableBytes, long totalBytes) {
    }
}
