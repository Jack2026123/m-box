package com.github.tvbox.osc.util;

import com.github.catvod.crawler.DebugLogStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdFilterEngine {

    private static final String TAG = "AdFilter";
    private static final Pattern EXTINF = Pattern.compile("^#EXTINF:\\s*([0-9.]+)");
    private static final Pattern AD_MARK = Pattern.compile("(?i)(#EXT-X-CUE-OUT|#EXT-X-CUE-IN|#EXT-X-SCTE35|SCTE35)");
    private static final Pattern DATERANGE = Pattern.compile("(?i)#EXT-X-DATERANGE");
    private static final Pattern STREAM_INF = Pattern.compile("(?i)^#EXT-X-STREAM-INF");
    private static final Pattern ATTR_URI = Pattern.compile("(?i)URI=\"([^\"]+)\"");

    private static final double MIN_SEGMENT_SECONDS = 5.0;
    private static final double MAX_SEGMENT_SECONDS = 120.0;
    private static final int MAX_CANDIDATES = 16;
    private static final long PROBE_BUDGET_MS = 1800L;

    public static final class Result {

        public final String text;
        public final int removedSegments;
        public final int removedBlocks;
        public final double removedSeconds;
        public final long costMs;
        public final boolean changed;
        public final String note;

        Result(String text, int removedSegments, int removedBlocks, double removedSeconds, long costMs, boolean changed, String note) {
            this.text = text;
            this.removedSegments = removedSegments;
            this.removedBlocks = removedBlocks;
            this.removedSeconds = removedSeconds;
            this.costMs = costMs;
            this.changed = changed;
            this.note = note;
        }
    }

    private static final class Segment {

        int block;
        double duration;
        String uri;
        int startLine;
        int endLine;
        boolean marker;
        boolean probed;
        boolean clue;
        String md5;
        double bitrate;
        double score;
    }

    private static final class Block {

        final int index;
        final List<Segment> segments = new ArrayList<>();
        double score;
        int clues;
        boolean ad;

        Block(int index) {
            this.index = index;
        }
    }

    private AdFilterEngine() {
    }

    public static Result filter(String playlist, String baseUrl, Map<String, String> headers) {
        long begin = System.currentTimeMillis();
        if (playlist == null || playlist.isEmpty()) return pass(playlist, "空占位", begin);
        String[] lines;
        try {
            lines = playlist.split("\r?\n", -1);
        } catch (Throwable e) {
            return pass(playlist, "切行失败", begin);
        }
        if (isMaster(lines)) return filterMaster(lines, baseUrl, begin);
        return filterMedia(lines, baseUrl, headers, begin);
    }

    private static Result pass(String text, String note, long begin) {
        return new Result(text, 0, 0, 0, System.currentTimeMillis() - begin, false, note);
    }

    private static boolean isMaster(String[] lines) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (STREAM_INF.matcher(line).find()) return true;
            if (EXTINF.matcher(line).find()) return false;
        }
        return false;
    }

    private static Result filterMaster(String[] lines, String baseUrl, long begin) {
        boolean changed = false;
        boolean expectUri = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (STREAM_INF.matcher(line).find()) {
                expectUri = true;
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                String replaced = absolutizeAttr(line, baseUrl);
                if (replaced != null) {
                    lines[i] = replaced;
                    changed = true;
                }
                continue;
            }
            if (line.startsWith("#")) continue;
            if (!expectUri) continue;
            expectUri = false;
            String absolute = resolve(baseUrl, line);
            if (absolute != null && !absolute.equals(line)) {
                lines[i] = absolute;
                changed = true;
            }
        }
        if (!changed) return pass(join(lines), "母清单未改写", begin);
        return new Result(join(lines), 0, 0, 0, System.currentTimeMillis() - begin, true, "母清单子链绝对化");
    }

    private static Result filterMedia(String[] lines, String baseUrl, Map<String, String> headers, long begin) {
        List<Segment> segments = parse(lines, baseUrl);
        if (segments.isEmpty()) return pass(join(lines), "无分片", begin);
        int absolutized = absolutize(lines, segments, baseUrl);
        if (absolutized > 0) DebugLogStore.add(TAG, "分片地址绝对化 " + absolutized + " 行（防相对路径打到本机 404）");
        boolean live = !hasEndList(lines);
        int sample = AdFilterSetting.getSampleSegments();
        int workers = AdFilterSetting.getMaxWorkers();
        float threshold = AdFilterSetting.getThreshold();
        float lowRatio = AdFilterSetting.getLowRatio();
        int cooldown = AdFilterSetting.getCooldown();

        Map<Integer, Block> blocks = group(segments);
        List<Segment> candidates = pick(blocks.values(), sample, live);
        if (!live && !candidates.isEmpty()) doProbe(candidates, headers, workers);
        Map<Block, String> reasons = new LinkedHashMap<>();
        double baseline = baseline(segments, blocks);
        decide(blocks, baseline, threshold, lowRatio, sample, live, reasons, cooldown);

        List<Segment> removed = new ArrayList<>();
        int removedBlocks = 0;
        double removedSeconds = 0;
        boolean[] skip = new boolean[lines.length];
        for (Block block : blocks.values()) {
            if (!block.ad) continue;
            removedBlocks++;
            for (Segment segment : block.segments) {
                removed.add(segment);
                removedSeconds += segment.duration;
                for (int i = segment.startLine; i <= segment.endLine && i < skip.length; i++) skip[i] = true;
                if (!live && segment.md5 != null) AdFilterCache.addAd(segment.md5);
            }
        }
        if (removed.isEmpty()) {
            if (!live) for (Segment segment : segments) if (segment.probed && !segment.clue && segment.md5 != null) AdFilterCache.addClean(segment.uri);
            return pass(join(lines), live ? "直播清单未命中" : "未命中广告特征", begin);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (skip[i] && !isGlobalHeader(lines[i])) continue;
            builder.append(lines[i]);
            if (i < lines.length - 1) builder.append('\n');
        }
        if (!live) for (Segment segment : segments) if (segment.probed && !segment.clue && segment.md5 != null) AdFilterCache.addClean(segment.uri);
        String reason = reasons.isEmpty() ? "-" : reasons.values().iterator().next();
        long cost = System.currentTimeMillis() - begin;
        DebugLogStore.add(TAG, String.format(Locale.US, "删除 %d 块 / %d 段 / %.1fs，模式=%s，阈值=%.2f，采样=%d，耗时=%dms，理由=%s",
                removedBlocks, removed.size(), removedSeconds, AdFilterSetting.modeName(AdFilterSetting.getMode()), threshold, sample, cost, reason));
        return new Result(builder.toString(), removed.size(), removedBlocks, removedSeconds, cost, true, reason);
    }

    private static boolean isGlobalHeader(String line) {
        String t = line.trim();
        return t.equals("#EXTM3U") || t.startsWith("#EXT-X-VERSION") || t.startsWith("#EXT-X-TARGETDURATION")
                || t.startsWith("#EXT-X-MEDIA-SEQUENCE") || t.startsWith("#EXT-X-PLAYLIST-TYPE")
                || t.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") || t.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")
                || t.startsWith("#EXT-X-ALLOW-CACHE") || t.startsWith("#EXT-X-KEY");
    }

    private static List<Segment> parse(String[] lines, String baseUrl) {
        List<Segment> segments = new ArrayList<>();
        int block = 0;
        int pendingStart = -1;
        double pendingDuration = 0;
        boolean pendingMarker = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (pendingStart < 0) pendingStart = i;
                if (line.toUpperCase(Locale.US).startsWith("#EXT-X-DISCONTINUITY")) {
                    if (!segments.isEmpty()) block++;
                    if (pendingStart < 0) pendingStart = i;
                }
                if (AD_MARK.matcher(line).find()) pendingMarker = true;
                if (DATERANGE.matcher(line).find() && line.toUpperCase(Locale.US).contains("SCTE35")) pendingMarker = true;
                Matcher matcher = EXTINF.matcher(line);
                if (matcher.find()) {
                    try {
                        pendingDuration = Double.parseDouble(matcher.group(1));
                    } catch (Throwable ignored) {
                    }
                }
                continue;
            }
            Segment segment = new Segment();
            segment.block = block;
            segment.duration = pendingDuration;
            segment.uri = resolve(baseUrl, line);
            segment.startLine = pendingStart >= 0 ? pendingStart : i;
            segment.endLine = i;
            segment.marker = pendingMarker;
            segments.add(segment);
            pendingStart = -1;
            pendingDuration = 0;
            pendingMarker = false;
        }
        return segments;
    }

    private static Map<Integer, Block> group(List<Segment> segments) {
        Map<Integer, Block> blocks = new LinkedHashMap<>();
        for (Segment segment : segments) {
            Block block = blocks.get(segment.block);
            if (block == null) {
                block = new Block(segment.block);
                blocks.put(segment.block, block);
            }
            block.segments.add(segment);
        }
        return blocks;
    }

    private static List<Segment> pick(Iterable<Block> blocks, int sample, boolean live) {
        List<Segment> markers = new ArrayList<>();
        List<Segment> others = new ArrayList<>();
        for (Block block : blocks) {
            int size = block.segments.size();
            for (int i = 0; i < size; i++) {
                Segment segment = block.segments.get(i);
                boolean take = segment.marker || i < sample || i == size - 1;
                if (!take || segment.duration <= 0 || segment.duration >= MAX_SEGMENT_SECONDS) continue;
                if (segment.marker) markers.add(segment);
                else others.add(segment);
            }
        }
        List<Segment> candidates = new ArrayList<>(markers);
        int limit = Math.min(MAX_CANDIDATES, Math.max(sample * 4, 8));
        for (Segment segment : others) {
            if (candidates.size() >= limit) break;
            candidates.add(segment);
        }
        if (live && candidates.size() > 24) return candidates.subList(0, 24);
        return candidates;
    }

    private static void doProbe(List<Segment> candidates, Map<String, String> headers, int workers) {
        ExecutorService pool = null;
        try {
            pool = Executors.newFixedThreadPool(Math.max(1, Math.min(workers, candidates.size())));
            List<Future<AdFilterProbe.Result>> futures = new ArrayList<>();
            for (final Segment segment : candidates) {
                futures.add(pool.submit(new Callable<AdFilterProbe.Result>() {
                    @Override
                    public AdFilterProbe.Result call() {
                        return AdFilterProbe.probe(segment.uri, headers);
                    }
                }));
            }
            long deadline = System.currentTimeMillis() + PROBE_BUDGET_MS;
            int used = 0;
            for (int i = 0; i < futures.size(); i++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    AdFilterProbe.Result result = futures.get(i).get(Math.min(left, AdFilterProbe.TIMEOUT_MS), TimeUnit.MILLISECONDS);
                    used++;
                    Segment segment = candidates.get(i);
                    if (result == null || !result.ok) continue;
                    segment.probed = true;
                    segment.md5 = result.md5;
                    if (result.size > 0 && segment.duration > 0) segment.bitrate = result.size * 8d / segment.duration;
                } catch (Throwable ignored) {
                }
            }
            if (used < candidates.size()) DebugLogStore.add(TAG, "探测预算用完：候选 " + candidates.size() + " 段，已探 " + used + " 段");
        } catch (Throwable ignored) {
        } finally {
            if (pool != null) pool.shutdownNow();
        }
    }

    private static double baseline(List<Segment> segments, Map<Integer, Block> blocks) {
        List<Double> values = new ArrayList<>();
        Block dominant = dominantBlock(blocks);
        if (dominant != null) for (Segment segment : dominant.segments) if (segment.probed && segment.bitrate > 0) values.add(segment.bitrate);
        if (values.size() < 2) {
            values.clear();
            for (Segment segment : segments) if (segment.probed && segment.bitrate > 0) values.add(segment.bitrate);
        }
        if (values.size() < 2) return 0;
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    private static void decide(Map<Integer, Block> blocks, double baseline, float threshold, float lowRatio, int sample, boolean live, Map<Block, String> reasons, int cooldown) {
        double removedSeconds = 0;
        double highLine = 1.0 + threshold * 2.0;
        Block dominant = null;
        String dominantDir = null;
        Map<Block, Boolean> structural = new LinkedHashMap<>();
        if (!live) {
            dominant = dominantBlock(blocks);
            if (dominant != null) dominantDir = dirPrefix(dominant);
            if (dominant != null && dominantDir != null) {
                for (Block block : blocks.values()) {
                    if (block == dominant) continue;
                    double total = 0;
                    for (Segment segment : block.segments) total += segment.duration;
                    String dir = dirPrefix(block);
                    structural.put(block, dir != null && !dir.equals(dominantDir) && total <= cooldown && block.segments.size() >= 2);
                }
            }
        }
        for (Block block : blocks.values()) {
            boolean hasCueOut = false;
            boolean hasCueIn = false;
            for (Segment segment : block.segments) {
                if (segment.marker) {
                    hasCueIn = true;
                }
            }
            hasCueOut = hasCueIn;
            int clues = 0;
            double score = 0;
            for (Segment segment : block.segments) {
                double segmentScore = 0;
                boolean clue = false;
                if (segment.md5 != null && AdFilterCache.hitAd(segment.md5)) {
                    segmentScore += 3.0;
                    clue = true;
                } else if (AdFilterCache.hitClean(segment.uri)) {
                    segment.clue = false;
                    segment.score = 0;
                    continue;
                }
                if (baseline > 0 && segment.probed && segment.bitrate > 0) {
                    double ratio = segment.bitrate / baseline;
                    if (ratio >= highLine + 0.4) {
                        segmentScore += 1.8;
                        clue = true;
                    } else if (ratio >= highLine) {
                        segmentScore += 1.2;
                        clue = true;
                    } else if (ratio <= lowRatio && segment.marker) {
                        segmentScore += 0.6;
                        clue = true;
                    }
                }
                if (segment.marker) {
                    segmentScore += 1.0;
                    clue = true;
                }
                if (Boolean.TRUE.equals(structural.get(block))) {
                    segmentScore += 1.0;
                    clue = true;
                }
                if (segment.duration > 0 && segment.duration >= MIN_SEGMENT_SECONDS && segment.duration <= 60) segmentScore += 0.3;
                if (segment.duration > 0 && segment.duration < MIN_SEGMENT_SECONDS) segmentScore -= 0.3;
                segment.score = segmentScore;
                segment.clue = clue;
                score += segmentScore;
                if (clue) clues++;
            }
            block.score = score;
            block.clues = clues;
            if (live) {
                block.ad = hasCueOut && clues > 0;
                if (block.ad) reasons.put(block, "直播结构标记");
                continue;
            }
            int need = Math.min(Math.max(1, sample), Math.max(1, block.segments.size()));
            boolean enough = clues >= need;
            if (enough && score >= need * 0.8 && removedSeconds < cooldown) {
                block.ad = true;
                reasons.put(block, String.format(Locale.US, "区块#%d 证据%d段 得分%.1f%s", block.index, clues, score, Boolean.TRUE.equals(structural.get(block)) ? " 结构:异目录小块" : ""));
                for (Segment segment : block.segments) removedSeconds += segment.duration;
            }
        }
    }

    private static Block dominantBlock(Map<Integer, Block> blocks) {
        Block dominant = null;
        double best = 0;
        for (Block block : blocks.values()) {
            double total = 0;
            for (Segment segment : block.segments) total += segment.duration;
            if (total > best) {
                best = total;
                dominant = block;
            }
        }
        return dominant;
    }

    private static String dirPrefix(Block block) {
        Map<String, Integer> counts = new HashMap<>();
        String best = null;
        int bestCount = 0;
        for (Segment segment : block.segments) {
            String dir = dirOf(segment.uri);
            if (dir == null) continue;
            Integer count = counts.get(dir);
            int value = count == null ? 1 : count + 1;
            counts.put(dir, value);
            if (value > bestCount) {
                bestCount = value;
                best = dir;
            }
        }
        return best != null && bestCount * 2 >= block.segments.size() ? best : null;
    }

    private static String dirOf(String uri) {
        if (uri == null) return null;
        int slash = uri.lastIndexOf('/');
        return slash > 0 ? uri.substring(0, slash + 1) : null;
    }

    private static int absolutize(String[] lines, List<Segment> segments, String baseUrl) {
        int rewritten = 0;
        for (Segment segment : segments) {
            if (segment.uri == null || segment.endLine < 0 || segment.endLine >= lines.length) continue;
            if (!segment.uri.equals(lines[segment.endLine].trim())) {
                lines[segment.endLine] = segment.uri;
                rewritten++;
            }
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || (!line.startsWith("#EXT-X-KEY") && !line.startsWith("#EXT-X-MAP"))) continue;
            String replaced = absolutizeAttr(line, baseUrl);
            if (replaced != null) {
                lines[i] = replaced;
                rewritten++;
            }
        }
        return rewritten;
    }

    private static String absolutizeAttr(String line, String baseUrl) {
        try {
            Matcher matcher = ATTR_URI.matcher(line);
            if (!matcher.find()) return null;
            String uri = resolve(baseUrl, matcher.group(1));
            if (uri == null || uri.equals(matcher.group(1))) return null;
            return line.substring(0, matcher.start(1)) + uri + line.substring(matcher.end(1));
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean hasEndList(String[] lines) {
        for (String raw : lines) if (raw.trim().toUpperCase(Locale.US).startsWith("#EXT-X-ENDLIST")) return true;
        return false;
    }

    private static String resolve(String baseUrl, String uri) {
        try {
            if (uri == null) return null;
            String trimmed = uri.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
            java.net.URI base = java.net.URI.create(baseUrl);
            return base.resolve(trimmed).toString();
        } catch (Throwable e) {
            return uri;
        }
    }

    private static String join(String[] lines) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) builder.append('\n');
            builder.append(lines[i]);
        }
        return builder.toString();
    }

    public static Map<String, String> copyHeaders(Map<String, String> headers) {
        return headers == null ? new HashMap<String, String>() : new LinkedHashMap<>(headers);
    }
}
