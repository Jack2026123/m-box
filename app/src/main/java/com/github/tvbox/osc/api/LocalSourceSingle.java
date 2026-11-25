package com.github.tvbox.osc.api;

import android.text.TextUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

// Single-file registration for the generated local-source config: only the
// given file's entries are added to or removed from the generated file, and
// everything else in it is left untouched. The new entries themselves are
// produced by the generator's own scanFile (run over the file's parent
// folder, then filtered to this file by #path), so naming, engine choice,
// sniffing and jar bindings match a full rescan exactly. Anything this
// helper cannot do safely (no generated file yet, file outside every
// enabled dir, unparseable generated file, write failure) returns false so
// the caller falls back to a full rebuild of the generated file from disk
// (OpenSourceFileActivity) or to LocalSourceDialog.scanAndLoad (list dialog).
public class LocalSourceSingle {

    public static boolean registerFile(File file) {
        return apply(null, file);
    }

    public static boolean removeFile(File file) {
        return apply(file, null);
    }

    public static boolean moveFileEntry(File oldFile, File newFile) {
        return apply(oldFile, newFile);
    }

    public static boolean addScanDir(String path) {
        if (TextUtils.isEmpty(path)) return false;
        String trim = path.trim();
        JsonObject cfg = LocalSource.loadSettings();
        ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
        ArrayList<String> enabled = LocalSource.stringList(cfg, "scan_enabled_dirs");
        boolean changed = false;
        if (!dirs.contains(trim)) {
            dirs.add(trim);
            changed = true;
        }
        if (!enabled.contains(trim)) {
            enabled.add(trim);
            changed = true;
        }
        if (!changed) return true;
        cfg.add("scan_dirs", LocalSource.toJsonArray(dirs));
        cfg.add("scan_enabled_dirs", LocalSource.toJsonArray(enabled));
        LocalSource.saveSettings(cfg);
        return true;
    }

    public static boolean isUnderEnabledDir(File file, JsonObject cfg) {
        String canon = LocalSource.canonPath(file.getAbsolutePath());
        if (TextUtils.isEmpty(canon)) return false;
        for (String dir : LocalSource.enabledDirs(cfg)) {
            String root = LocalSource.canonPath(dir);
            if (!TextUtils.isEmpty(root) && (canon.equals(root) || canon.startsWith(root + File.separator))) return true;
        }
        return false;
    }

    public static ArrayList<File> categoryDirs() {
        JsonObject cfg = LocalSource.loadSettings();
        ArrayList<File> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String dir : LocalSource.enabledDirs(cfg)) {
            File root = new File(dir);
            if (!root.isDirectory()) continue;
            addCategoryDir(out, seen, root);
            File[] children = root.listFiles();
            if (children == null) continue;
            Arrays.sort(children, LocalSource.BY_NAME);
            for (File child : children) {
                if (child.isDirectory() && !child.getName().startsWith(".")) addCategoryDir(out, seen, child);
            }
        }
        return out;
    }

    private static void addCategoryDir(ArrayList<File> out, LinkedHashSet<String> seen, File dir) {
        String canon = LocalSource.canonPath(dir.getAbsolutePath());
        if (!TextUtils.isEmpty(canon) && seen.add(canon)) out.add(dir);
    }

    public static File resolveCategoryDir(String folderName) {
        if (TextUtils.isEmpty(folderName)) return null;
        for (File dir : categoryDirs()) {
            if (folderName.equals(dir.getName())) return dir;
        }
        return null;
    }

    private static boolean apply(File oldFile, File newFile) {
        synchronized (LocalSource.LOCK) {
            try {
                JsonObject cfg = LocalSource.loadSettings();
                String outPath = LocalSource.outputPath(cfg);
                if (TextUtils.isEmpty(outPath)) return false;
                File generated = new File(outPath);
                if (!generated.isFile()) return false;
                String text = readText(generated);
                if (text == null) return false;
                JsonElement parsed = JsonParser.parseString(text);
                if (!parsed.isJsonObject()) return false;
                JsonObject root = parsed.getAsJsonObject();
                String outDir = LocalSource.outputDir(cfg);
                String canonOld = oldFile == null ? null : LocalSource.canonPath(oldFile.getAbsolutePath());
                String canonNew = newFile == null ? null : LocalSource.canonPath(newFile.getAbsolutePath());
                if (canonOld != null) {
                    removeSitesFor(root, canonOld, outDir);
                    removeLivesFor(root, canonOld, outDir);
                }
                if (newFile != null && newFile.isFile()) {
                    ScanResult result = scanParent(newFile, cfg, outDir);
                    if (result == null) return false;
                    applyJarBindings(result.sites, cfg, outDir);
                    appendSites(root, result.sites, cfg);
                    appendLives(root, result.lives);
                }
                String written = LocalSource.writeGenerated(root);
                if (written == null) return false;
                LocalSource.cache = null;
                return true;
            } catch (Throwable th) {
                return false;
            }
        }
    }

    private static String readText(File file) {
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buffer = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int read;
                while ((read = in.read(buffer)) > 0) {
                    sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
                return sb.toString();
            } finally {
                in.close();
            }
        } catch (Throwable th) {
            return null;
        }
    }

    private static void removeSitesFor(JsonObject root, String canon, String outDir) {
        if (!root.has("sites") || !root.get("sites").isJsonArray()) return;
        JsonArray kept = new JsonArray();
        for (JsonElement element : root.getAsJsonArray("sites")) {
            if (!element.isJsonObject()) continue;
            String fileCanon = siteFileCanon(element.getAsJsonObject(), outDir);
            if (fileCanon != null && LocalSource.normalizePath(fileCanon).equals(LocalSource.normalizePath(canon))) continue;
            kept.add(element);
        }
        root.add("sites", kept);
    }

    private static void removeLivesFor(JsonObject root, String canon, String outDir) {
        if (!root.has("lives") || !root.get("lives").isJsonArray()) return;
        JsonArray kept = new JsonArray();
        for (JsonElement element : root.getAsJsonArray("lives")) {
            if (!element.isJsonObject()) {
                kept.add(element);
                continue;
            }
            String url = LocalSource.getString(element.getAsJsonObject(), "url");
            String fileCanon = liveFileCanon(url, outDir);
            if (fileCanon != null && LocalSource.normalizePath(fileCanon).equals(LocalSource.normalizePath(canon))) continue;
            kept.add(element);
        }
        root.add("lives", kept);
    }

    private static String siteFileCanon(JsonObject site, String outDir) {
        String api = LocalSource.getString(site, "api");
        String ext = LocalSource.getString(site, "ext");
        String homePage = LocalSource.getString(site, "homePage");
        String direct = firstFileRef(api);
        if (direct != null) return resolveRef(direct, outDir);
        if (!TextUtils.isEmpty(ext)) {
            String ref = firstFileRef(ext);
            if (ref != null) return resolveRef(ref, outDir);
        }
        if (!TextUtils.isEmpty(homePage)) {
            String ref = firstFileRef(homePage);
            if (ref != null) return resolveRef(ref, outDir);
        }
        if (!TextUtils.isEmpty(api)) {
            if (api.startsWith("http://0.0.0.0:8901/")) {
                return resolveRef(api.substring("http://0.0.0.0:8901/".length()), outDir);
            }
            if (api.startsWith("./") || api.startsWith("/")) {
                return resolveRef(api, outDir);
            }
        }
        try {
            String resolved = LocalSource.resolveSiteFile(site);
            if (!TextUtils.isEmpty(resolved)) return LocalSource.canonPath(resolved);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String firstFileRef(String value) {
        if (TextUtils.isEmpty(value)) return null;
        if (value.startsWith("file://") || value.startsWith("./") || value.startsWith("/")) return value;
        return null;
    }

    private static String resolveRef(String ref, String outDir) {
        String s = ref;
        if (s.startsWith("file://")) s = s.substring("file://".length());
        if (s.startsWith("./")) s = s.substring(2);
        File file = s.startsWith("/") ? new File(s) : new File(outDir, s);
        return LocalSource.canonPath(file.getAbsolutePath());
    }

    private static String liveFileCanon(String url, String outDir) {
        if (TextUtils.isEmpty(url)) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) return null;
        return resolveRef(url, outDir);
    }

    private static class ScanResult {
        ArrayList<JsonObject> sites = new ArrayList<>();
        ArrayList<JsonObject> lives = new ArrayList<>();
    }

    private static ScanResult scanParent(File file, JsonObject cfg, String outDir) {
        String canonFile = LocalSource.canonPath(file.getAbsolutePath());
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) return null;
        ArrayList<String> roots = LocalSource.enabledDirs(cfg);
        String root = null;
        for (String dir : roots) {
            String canonRoot = LocalSource.canonPath(dir);
            if (!TextUtils.isEmpty(canonRoot) && (canonFile.equals(canonRoot) || canonFile.startsWith(canonRoot + File.separator))) {
                if (root == null || canonRoot.length() > LocalSource.canonPath(root).length()) root = dir;
            }
        }
        if (root == null) return null;
        ArrayList<String> blocked = LocalSource.stringList(cfg, "blocked_dirs");
        ArrayList<String> chain = new ArrayList<>();
        File cursor = parent;
        String canonRoot = LocalSource.canonPath(root);
        while (cursor != null && !LocalSource.canonPath(cursor.getAbsolutePath()).equals(canonRoot)) {
            chain.add(0, cursor.getName());
            cursor = cursor.getParentFile();
        }
        if (cursor == null) return null;
        for (String segment : chain) {
            if (blocked.contains(segment)) return new ScanResult();
        }
        if (LocalSource.stringList(cfg, "excluded_files").contains(file.getName())) return new ScanResult();
        if (chain.size() > LocalSource.maxDepth(cfg)) return new ScanResult();
        if (LocalSource.isSelfFile(file, cfg)) return new ScanResult();
        boolean xbpq = false;
        boolean xyq = false;
        boolean drpy = false;
        boolean live = false;
        boolean jsonDir = false;
        ArrayList<String> above = new ArrayList<>();
        above.add(new File(root).getName());
        for (int i = 0; i + 1 < chain.size(); i++) above.add(chain.get(i));
        for (String name : above) {
            if (LocalSource.containsAny(name, Arrays.asList(LocalSource.TAG_XBPQ))) xbpq = true;
            if (LocalSource.containsAny(name, Arrays.asList(LocalSource.TAG_XYQ))) xyq = true;
            if (LocalSource.containsAny(name, Arrays.asList(LocalSource.TAG_DRPY))) drpy = true;
            if ("直播文件".equals(name)) live = true;
            if ("json".equalsIgnoreCase(name)) jsonDir = true;
        }
        LocalSource.ScanCtx ctx = new LocalSource.ScanCtx();
        LocalSource.scanFile(parent, chain.size(), xbpq, xyq, drpy, live, jsonDir, ctx, cfg, outDir);
        ScanResult result = new ScanResult();
        for (Object item : (ArrayList) ctx.e) {
            if (!(item instanceof JsonObject)) continue;
            JsonObject site = (JsonObject) item;
            String path = LocalSource.getString(site, "#path");
            if (!TextUtils.isEmpty(path) && LocalSource.normalizePath(path).equals(LocalSource.normalizePath(canonFile))) {
                result.sites.add(site);
            }
        }
        for (Object item : (ArrayList) ctx.f) {
            if (!(item instanceof JsonObject)) continue;
            JsonObject liveObj = (JsonObject) item;
            String url = LocalSource.getString(liveObj, "url");
            String fileCanon = liveFileCanon(url, outDir);
            if (fileCanon != null && LocalSource.normalizePath(fileCanon).equals(LocalSource.normalizePath(canonFile))) {
                result.lives.add(liveObj);
            }
        }
        return result;
    }

    private static void applyJarBindings(ArrayList<JsonObject> sites, JsonObject cfg, String outDir) {
        LinkedHashMap<String, String> bindings = LocalSource.jarBindings(cfg);
        for (JsonObject site : sites) {
            String path = LocalSource.getString(site, "#path");
            String boundJar = null;
            if (!TextUtils.isEmpty(path)) {
                boundJar = bindings.get(path);
                if (boundJar == null) boundJar = bindings.get(LocalSource.canonPath(path));
            }
            if (!TextUtils.isEmpty(boundJar)) {
                File jarFile = new File(boundJar);
                if (jarFile.isFile()) site.addProperty("jar", LocalSource.relativize(jarFile, outDir));
            }
        }
    }

    private static void appendSites(JsonObject root, ArrayList<JsonObject> fresh, JsonObject cfg) {
        JsonArray sites = (root.has("sites") && root.get("sites").isJsonArray()) ? root.getAsJsonArray("sites") : new JsonArray();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonElement element : sites) {
            if (!element.isJsonObject()) continue;
            keys.add(LocalSource.getString(element.getAsJsonObject(), "key"));
            names.add(LocalSource.getString(element.getAsJsonObject(), "name"));
        }
        boolean mergeSameName = LocalSource.getBool(cfg, "merge_same_name", false);
        for (JsonObject site : fresh) {
            String key = LocalSource.getString(site, "key");
            String name = LocalSource.getString(site, "name");
            if (mergeSameName) {
                String baseName = name.replaceAll("\\([^()]*\\)\\s*$", "").trim();
                JsonObject prev = null;
                int prevIndex = -1;
                for (int i = 0; i < sites.size(); i++) {
                    JsonElement element = sites.get(i);
                    if (!element.isJsonObject()) continue;
                    String other = LocalSource.getString(element.getAsJsonObject(), "name").replaceAll("\\([^()]*\\)\\s*$", "").trim();
                    if (baseName.equals(other)) {
                        prev = element.getAsJsonObject();
                        prevIndex = i;
                        break;
                    }
                }
                if (prev != null) {
                    if (LocalSource.sitePriority(site) > LocalSource.sitePriority(prev)) {
                        stripHashKeys(site);
                        sites.set(prevIndex, site);
                        names.remove(LocalSource.getString(prev, "name"));
                        names.add(name);
                    }
                    continue;
                }
            }
            if (!TextUtils.isEmpty(key) && keys.contains(key)) {
                int suffix = 1;
                String newKey = key + "_1";
                while (keys.contains(newKey)) {
                    suffix++;
                    newKey = key + "_" + suffix;
                }
                site.addProperty("key", newKey);
                String base = LocalSource.getString(site, "#base");
                String joined = "";
                if (!TextUtils.isEmpty(base)) {
                    ArrayList<String> tags = new ArrayList<>();
                    java.util.regex.Matcher matcher = LocalSource.BRACKET.matcher(base);
                    while (matcher.find()) tags.add(LocalSource.stripBrackets(matcher.group()));
                    StringBuilder tagBuilder = new StringBuilder();
                    for (String tag : tags) {
                        if (TextUtils.isEmpty(tag)) continue;
                        if (tagBuilder.length() > 0) tagBuilder.append('+');
                        tagBuilder.append(tag);
                    }
                    joined = tagBuilder.toString();
                }
                String newName = name;
                if (!TextUtils.isEmpty(joined)) {
                    String candidate = LocalSource.insertTag(name, joined);
                    if (!names.contains(candidate)) newName = candidate;
                }
                if (newName.equals(name)) {
                    int n = Math.max(suffix, 1);
                    String candidate = LocalSource.insertTag(name, String.valueOf(n));
                    while (names.contains(candidate)) {
                        n++;
                        candidate = LocalSource.insertTag(name, String.valueOf(n));
                    }
                    newName = candidate;
                }
                site.addProperty("name", newName);
                key = newKey;
                name = newName;
            }
            stripHashKeys(site);
            sites.add(site);
            keys.add(key);
            names.add(name);
        }
        root.add("sites", sites);
    }

    private static void stripHashKeys(JsonObject site) {
        ArrayList<String> hashKeys = new ArrayList<>();
        for (String key : site.keySet()) {
            if (key.startsWith("#")) hashKeys.add(key);
        }
        for (String key : hashKeys) site.remove(key);
    }

    private static void appendLives(JsonObject root, ArrayList<JsonObject> fresh) {
        if (fresh.isEmpty()) return;
        JsonArray lives = (root.has("lives") && root.get("lives").isJsonArray()) ? root.getAsJsonArray("lives") : new JsonArray();
        for (JsonObject live : fresh) lives.add(live);
        root.add("lives", lives);
    }
}
