package com.github.tvbox.osc.util;

import com.github.catvod.net.OkHttp;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class AdFilterProbe {

    private static final int PEEK = 64 * 1024;
    public static final long TIMEOUT_MS = 2500L;

    public static final class Result {

        public final boolean ok;
        public final long size;
        public final String md5;

        Result(boolean ok, long size, String md5) {
            this.ok = ok;
            this.size = size;
            this.md5 = md5;
        }
    }

    private AdFilterProbe() {
    }

    public static Result probe(String url, Map<String, String> headers) {
        if (url == null || url.isEmpty()) return new Result(false, 0, null);
        Response response = null;
        try {
            OkHttpClient client = OkHttp.client(TIMEOUT_MS);
            Request.Builder builder = new Request.Builder().url(url).get();
            builder.header("Range", "bytes=0-" + (PEEK - 1));
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String name = entry.getKey();
                    String value = entry.getValue();
                    if (name == null || value == null || name.isEmpty()) continue;
                    if ("range".equalsIgnoreCase(name) || "host".equalsIgnoreCase(name)) continue;
                    try {
                        builder.header(name, value);
                    } catch (Throwable ignored) {
                    }
                }
            }
            response = client.newCall(builder.build()).execute();
            if (!response.isSuccessful() && response.code() != 206) return new Result(false, 0, null);
            long size = parseSize(response.header("Content-Range"), response.header("Content-Length"));
            String md5 = md5(response.body());
            return new Result(true, size, md5);
        } catch (Throwable e) {
            return new Result(false, 0, null);
        } finally {
            try {
                if (response != null) response.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static long parseSize(String contentRange, String contentLength) {
        try {
            if (contentRange != null && contentRange.contains("/")) {
                String total = contentRange.substring(contentRange.lastIndexOf('/') + 1).trim();
                if (!total.isEmpty() && !"*".equals(total)) return Long.parseLong(total);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (contentLength != null) return Long.parseLong(contentLength.trim());
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String md5(ResponseBody body) {
        if (body == null) return null;
        InputStream input = null;
        try {
            input = body.byteStream();
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while (total < PEEK && (read = input.read(buffer, 0, Math.min(buffer.length, PEEK - total))) > 0) {
                digest.update(buffer, 0, read);
                total += read;
            }
            if (total <= 0) return null;
            return hex(digest.digest());
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int value = b & 0xFF;
            if (value < 16) builder.append('0');
            builder.append(Integer.toHexString(value));
        }
        return builder.toString();
    }
}
