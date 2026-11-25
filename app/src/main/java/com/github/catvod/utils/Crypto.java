package com.github.catvod.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Crypto {

    public static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    public static byte[] sha256(File file) throws IOException {
        MessageDigest digest = newDigest("SHA-256");
        if (digest == null) return null;
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return digest.digest();
        } catch (IOException e) {
            return null;
        }
    }

    public static String md5(String text) {
        if (text == null) return "";
        MessageDigest digest = newDigest("MD5");
        if (digest == null) return "";
        byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : hash) builder.append(String.format("%02x", b));
        return builder.toString();
    }
}
