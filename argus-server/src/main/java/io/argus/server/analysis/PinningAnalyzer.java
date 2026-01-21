package io.argus.server.analysis;

import io.argus.core.event.VirtualThreadEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
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
                .sorted(Comparator.comparingLong((Map.Entry<String, HotspotData> e) -> e.getValue().count)
                        .reversed())
                .limit(10)
                .toList();

        int rank = 1;
        for (Map.Entry<String, HotspotData> entry : sorted) {
            String hash = entry.getKey();
            HotspotData data = entry.getValue();
            double percentage = total > 0 ? (data.count * 100.0 / total) : 0;

            hotspots.add(new PinningHotspot(
                    rank++,
                    data.count,
                    percentage,
                    hash,
                    data.topFrame,
                    data.fullStackTrace
            ));
        }

        return new PinningAnalysisResult(total, uniqueCount, hotspots);
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
        // Remove entries with count = 1 to make room
        hotspotsByHash.entrySet().removeIf(entry -> entry.getValue().count <= 1);
    }

    /**
     * Internal data holder for hotspot tracking.
     */
    private static class HotspotData {
        final String fullStackTrace;
        final String topFrame;
        volatile long count;

        HotspotData(String fullStackTrace, String topFrame, long count) {
            this.fullStackTrace = fullStackTrace;
            this.topFrame = topFrame;
            this.count = count;
        }

        void incrementCount() {
            count++;
        }
    }

    /**
     * Result of pinning analysis.
     */
    public record PinningAnalysisResult(
            long totalPinnedEvents,
            int uniqueStackTraces,
            List<PinningHotspot> hotspots
    ) {
    }
}
