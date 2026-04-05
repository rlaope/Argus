package io.argus.server.analysis;

import io.argus.core.event.ContentionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes thread contention events and provides statistics.
 *
 * <p>Tracks lock contention hotspots, duration, and thread-level statistics.
 */
public final class ContentionAnalyzer {

    private static final int TOP_HOTSPOTS_LIMIT = 10;

    private final AtomicLong totalContentionEvents = new AtomicLong(0);
    private final AtomicLong totalContentionTimeNanos = new AtomicLong(0);
    private final Map<String, ContentionStats> monitorStats = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> threadContentionTime = new ConcurrentHashMap<>();

    /**
     * Records a contention event for analysis.
     *
     * @param event the contention event to record
     */
    public void recordContentionEvent(ContentionEvent event) {
        totalContentionEvents.incrementAndGet();
        totalContentionTimeNanos.addAndGet(event.durationNanos());

        // Track by monitor class
        String monitorClass = event.monitorClass() != null ? event.monitorClass() : "Unknown";
        monitorStats.computeIfAbsent(monitorClass, k -> new ContentionStats())
                .record(event.durationNanos(), event.type());

        // Track by thread
        threadContentionTime.computeIfAbsent(event.threadId(), k -> new AtomicLong())
                .addAndGet(event.durationNanos());
    }

    /**
     * Returns the contention analysis results.
     *
     * @return the contention analysis result
     */
    public ContentionAnalysisResult getAnalysis() {
        long totalEvents = totalContentionEvents.get();
        long totalTimeNanos = totalContentionTimeNanos.get();
        long totalTimeMs = totalTimeNanos / 1_000_000;

        // Get top hotspots
        List<ContentionHotspot> hotspots = monitorStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalTimeNanos.get(), a.getValue().totalTimeNanos.get()))
                .limit(TOP_HOTSPOTS_LIMIT)
                .map(e -> {
                    ContentionStats stats = e.getValue();
                    double percentage = totalTimeNanos > 0
                            ? (stats.totalTimeNanos.get() * 100.0) / totalTimeNanos : 0;
                    return new ContentionHotspot(
                            e.getKey(),
                            stats.eventCount.get(),
                            stats.totalTimeNanos.get() / 1_000_000,
                            stats.enterCount.get(),
                            stats.waitCount.get(),
                            percentage
                    );
                })
                .toList();

        // Build thread contention map
        Map<String, Long> threadContention = new ConcurrentHashMap<>();
        threadContentionTime.forEach((threadId, time) ->
                threadContention.put("Thread-" + threadId, time.get() / 1_000_000));

        return new ContentionAnalysisResult(
                totalEvents,
                totalTimeMs,
                hotspots,
                threadContention
        );
    }

    /**
     * Returns the top contention hotspots.
     *
     * @param limit maximum number of hotspots to return
     * @return list of contention hotspots
     */
    public List<ContentionHotspot> getTopHotspots(int limit) {
        long totalTimeNanos = totalContentionTimeNanos.get();

        return monitorStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalTimeNanos.get(), a.getValue().totalTimeNanos.get()))
                .limit(limit)
                .map(e -> {
                    ContentionStats stats = e.getValue();
                    double percentage = totalTimeNanos > 0
                            ? (stats.totalTimeNanos.get() * 100.0) / totalTimeNanos : 0;
                    return new ContentionHotspot(
                            e.getKey(),
                            stats.eventCount.get(),
                            stats.totalTimeNanos.get() / 1_000_000,
                            stats.enterCount.get(),
                            stats.waitCount.get(),
                            percentage
                    );
                })
                .toList();
    }

    /**
     * Clears all recorded data.
     */
    public void clear() {
        totalContentionEvents.set(0);
        totalContentionTimeNanos.set(0);
        monitorStats.clear();
        threadContentionTime.clear();
    }

    /**
     * Internal statistics for a monitor class.
     */
    private static class ContentionStats {
        final AtomicLong eventCount = new AtomicLong(0);
        final AtomicLong totalTimeNanos = new AtomicLong(0);
        final AtomicLong enterCount = new AtomicLong(0);
        final AtomicLong waitCount = new AtomicLong(0);

        void record(long durationNanos, ContentionEvent.ContentionType type) {
            eventCount.incrementAndGet();
            totalTimeNanos.addAndGet(durationNanos);
            if (type == ContentionEvent.ContentionType.ENTER) {
                enterCount.incrementAndGet();
            } else {
                waitCount.incrementAndGet();
            }
        }
    }

    /**
     * A contention hotspot identified by analysis.
     */
    public static final class ContentionHotspot {
        private final String monitorClass;
        private final long eventCount;
        private final long totalTimeMs;
        private final long enterCount;
        private final long waitCount;
        private final double percentage;

        public ContentionHotspot(String monitorClass, long eventCount, long totalTimeMs,
                                 long enterCount, long waitCount, double percentage) {
            this.monitorClass = monitorClass;
            this.eventCount = eventCount;
            this.totalTimeMs = totalTimeMs;
            this.enterCount = enterCount;
            this.waitCount = waitCount;
            this.percentage = percentage;
        }

        public String monitorClass() { return monitorClass; }
        public long eventCount() { return eventCount; }
        public long totalTimeMs() { return totalTimeMs; }
        public long enterCount() { return enterCount; }
        public long waitCount() { return waitCount; }
        public double percentage() { return percentage; }

        /**
         * Returns the average contention time in ms.
         */
        public double avgTimeMs() {
            return eventCount > 0 ? (double) totalTimeMs / eventCount : 0;
        }
    }

    /**
     * Result of contention analysis.
     */
    public static final class ContentionAnalysisResult {
        private final long totalContentionEvents;
        private final long totalContentionTimeMs;
        private final List<ContentionHotspot> hotspots;
        private final Map<String, Long> threadContentionTime;

        public ContentionAnalysisResult(long totalContentionEvents, long totalContentionTimeMs,
                                        List<ContentionHotspot> hotspots,
                                        Map<String, Long> threadContentionTime) {
            this.totalContentionEvents = totalContentionEvents;
            this.totalContentionTimeMs = totalContentionTimeMs;
            this.hotspots = hotspots;
            this.threadContentionTime = threadContentionTime;
        }

        public long totalContentionEvents() { return totalContentionEvents; }
        public long totalContentionTimeMs() { return totalContentionTimeMs; }
        public List<ContentionHotspot> hotspots() { return hotspots; }
        public Map<String, Long> threadContentionTime() { return threadContentionTime; }
    }
}
