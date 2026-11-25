package com.github.tvbox.osc.util;

import com.github.catvod.utils.Prefers;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AdFilterCache {

    private static final String KEY = "ad_filter_signatures";
    private static final int MAX = 800;
    private static final long CLEAN_TTL = 30 * 60 * 1000L;

    private static final Set<String> SIGS = Collections.synchronizedSet(new LinkedHashSet<String>());
    private static final Map<String, Long> CLEAN = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private AdFilterCache() {
    }

    public static boolean hitAd(String sig) {
        if (sig == null || sig.isEmpty()) return false;
        load();
        return SIGS.contains(sig);
    }

    public static void addAd(String sig) {
        if (sig == null || sig.isEmpty()) return;
        load();
        synchronized (SIGS) {
            if (SIGS.contains(sig)) return;
            SIGS.add(sig);
            while (SIGS.size() > MAX) {
                Iterator<String> iterator = SIGS.iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            save();
        }
    }

    public static boolean hitClean(String url) {
        if (url == null || url.isEmpty()) return false;
        Long at = CLEAN.get(url);
        return at != null && System.currentTimeMillis() - at < CLEAN_TTL;
    }

    public static void addClean(String url) {
        if (url == null || url.isEmpty()) return;
        if (CLEAN.size() > 400) CLEAN.clear();
        CLEAN.put(url, System.currentTimeMillis());
    }

    public static int adCount() {
        load();
        return SIGS.size();
    }

    public static void clear() {
        SIGS.clear();
        CLEAN.clear();
        loaded = true;
        Prefers.put(KEY, "");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            String raw = Prefers.getString(KEY, "");
            if (raw == null || raw.isEmpty()) return;
            for (String sig : raw.split(",")) if (!sig.isEmpty()) SIGS.add(sig);
        } catch (Throwable ignored) {
        }
    }

    private static void save() {
        try {
            StringBuilder builder = new StringBuilder();
            synchronized (SIGS) {
                for (String sig : SIGS) {
                    if (builder.length() > 0) builder.append(',');
                    builder.append(sig);
                }
            }
            Prefers.put(KEY, builder.toString());
        } catch (Throwable ignored) {
        }
    }
}
