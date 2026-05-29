package io.argus.server.analysis;

import io.argus.core.event.VirtualThreadEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes virtual thread pinning events and identifies hotspots.
 *
 * <p>Groups pinned events by their stack trace, counts occurrences,
 * and provides sorted hotspots for identifying problematic code locations.
 */
public final class PinningAnalyzer {

    private static final int MAX_HOTSPOTS = 100;

    private final Map<String, HotspotData> hotspotsByHash = new ConcurrentHashMap<>();
    private final AtomicLong totalPinnedEvents = new AtomicLong(0);

    /**
     * Records a pinned event for analysis.
     *
     * @param event the pinned event to record
     */
    public void recordPinnedEvent(VirtualThreadEvent event) {
        if (event.stackTrace() == null || event.stackTrace().isEmpty()) {
            return;
        }

        totalPinnedEvents.incrementAndGet();

        String hash = hashStackTrace(event.stackTrace());
        hotspotsByHash.compute(hash, (key, existing) -> {
            if (existing == null) {
                return new HotspotData(
                        event.stackTrace(),
                        extractTopFrame(event.stackTrace()),
                        1
                );
            } else {
                existing.incrementCount();
                return existing;
            }
        });

        // Evict old entries if too many unique stack traces
        if (hotspotsByHash.size() > MAX_HOTSPOTS * 2) {
            evictLowCountEntries();
        }
    }

    /**
     * Returns the analysis results.
     *
     * @return the pinning analysis containing hotspots
     */
    public PinningAnalysisResult getAnalysis() {
        long total = totalPinnedEvents.get();
        int uniqueCount = hotspotsByHash.size();

        List<PinningHotspot> hotspots = new ArrayList<>();

        // Sort by count descending
        List<Map.Entry<String, HotspotData>> sorted = hotspotsByHash.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, HotspotData> e) -> e.getValue().count.get())
                        .reversed())
                .limit(10)
                .toList();

        int rank = 1;
        for (Map.Entry<String, HotspotData> entry : sorted) {
            String hash = entry.getKey();
            HotspotData data = entry.getValue();
            long count = data.count.get();
            double percentage = total > 0 ? (count * 100.0 / total) : 0;

            hotspots.add(new PinningHotspot(
                    rank++,
                    count,
                    percentage,
                    hash,
                    data.topFrame,
                    data.fullStackTrace,
                    PinningTaxonomy.classify(data.fullStackTrace)
            ));
        }

        // Tally events per post-JEP-491 bucket across all recorded stack traces.
        Map<PinningTaxonomy, Long> byTaxonomy = new EnumMap<>(PinningTaxonomy.class);
        for (HotspotData data : hotspotsByHash.values()) {
            byTaxonomy.merge(PinningTaxonomy.classify(data.fullStackTrace), data.count.get(), Long::sum);
        }

        return new PinningAnalysisResult(total, uniqueCount, hotspots, byTaxonomy);
    }

    /**
     * Clears all recorded data.
     */
    public void clear() {
        hotspotsByHash.clear();
        totalPinnedEvents.set(0);
    }

    private String hashStackTrace(String stackTrace) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(stackTrace.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return Integer.toHexString(stackTrace.hashCode());
        }
    }

    private String extractTopFrame(String stackTrace) {
        if (stackTrace == null || stackTrace.isEmpty()) {
            return "(unknown)";
        }

        // Stack trace format: "    at com.example.Class.method(File.java:123)\n..."
        String[] lines = stackTrace.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("at ")) {
                return trimmed.substring(3).trim();
            }
        }

        // If no "at " found, return first non-empty line
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }

        return "(unknown)";
    }

    private void evictLowCountEntries() {
        // Retain the MAX_HOTSPOTS highest-count entries and drop the rest. The old
        // removeIf(count <= 1) wiped the entire map whenever every site had been seen
        // exactly once — losing all hotspots precisely when pinning was widely spread.
        if (hotspotsByHash.size() <= MAX_HOTSPOTS) {
            return;
        }
        long[] counts = hotspotsByHash.values().stream()
                .mapToLong(d -> d.count.get())
                .toArray();
        // The map can shrink between the size() guard above and this snapshot (a
        // concurrent clear() or overlapping eviction). Re-check against the snapshot
        // so counts[counts.length - MAX_HOTSPOTS] can never index negative.
        if (counts.length <= MAX_HOTSPOTS) {
            return;
        }
        java.util.Arrays.sort(counts); // ascending
        // Threshold = the count at the MAX_HOTSPOTS-th highest position.
        long threshold = counts[counts.length - MAX_HOTSPOTS];
        // Drop entries strictly below the threshold — keeps every heavy hitter.
        hotspotsByHash.entrySet().removeIf(entry -> entry.getValue().count.get() < threshold);
        // Ties at the threshold can still leave us over capacity (e.g. every site seen
        // exactly once → all counts equal). Trim the tied entries until bounded so the
        // map can never grow without limit; heavier hitters above the threshold are
        // never touched.
        if (hotspotsByHash.size() > MAX_HOTSPOTS) {
            java.util.Iterator<Map.Entry<String, HotspotData>> it = hotspotsByHash.entrySet().iterator();
            while (hotspotsByHash.size() > MAX_HOTSPOTS && it.hasNext()) {
                if (it.next().getValue().count.get() == threshold) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Internal data holder for hotspot tracking.
     */
    private static class HotspotData {
        final String fullStackTrace;
        final String topFrame;
        final AtomicLong count;

        HotspotData(String fullStackTrace, String topFrame, long count) {
            this.fullStackTrace = fullStackTrace;
            this.topFrame = topFrame;
            this.count = new AtomicLong(count);
        }

        void incrementCount() {
            count.incrementAndGet();
        }
    }

    /**
     * Result of pinning analysis.
     */
    public static final class PinningAnalysisResult {
        private final long totalPinnedEvents;
        private final int uniqueStackTraces;
        private final List<PinningHotspot> hotspots;
        private final Map<PinningTaxonomy, Long> eventsByTaxonomy;

        public PinningAnalysisResult(long totalPinnedEvents, int uniqueStackTraces, List<PinningHotspot> hotspots,
                                     Map<PinningTaxonomy, Long> eventsByTaxonomy) {
            this.totalPinnedEvents = totalPinnedEvents;
            this.uniqueStackTraces = uniqueStackTraces;
            this.hotspots = hotspots;
            this.eventsByTaxonomy = eventsByTaxonomy;
        }

        public long totalPinnedEvents() { return totalPinnedEvents; }
        public int uniqueStackTraces() { return uniqueStackTraces; }
        public List<PinningHotspot> hotspots() { return hotspots; }

        /** Event counts grouped by post-JEP-491 pinning bucket; absent buckets are simply not present. */
        public Map<PinningTaxonomy, Long> eventsByTaxonomy() { return eventsByTaxonomy; }
    }
}
