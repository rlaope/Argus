package io.argus.server.analysis;

import io.argus.core.event.GCEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes garbage collection events and provides statistics.
 *
 * <p>Tracks GC events including pause times, heap usage, and cause distribution.
 */
public final class GCAnalyzer {

    private static final int MAX_RECENT_GCS = 20;
    private static final int MAX_HISTORY_SIZE = 100;

    private final AtomicLong totalGCEvents = new AtomicLong(0);
    private final AtomicLong totalPauseTimeNanos = new AtomicLong(0);
    private final AtomicLong maxPauseTimeNanos = new AtomicLong(0);
    private final Map<String, AtomicLong> causeDistribution = new ConcurrentHashMap<>();
    private final List<GCSummary> recentGCs = new CopyOnWriteArrayList<>();

    // Latest heap state
    private volatile long lastHeapUsed = 0;
    private volatile long lastHeapCommitted = 0;
    private volatile Instant lastGCTime = null;

    // GC overhead tracking
    private volatile long overheadWindowStartTime = System.currentTimeMillis();
    private volatile long overheadWindowPauseNanos = 0;
    private volatile double currentGcOverheadPercent = 0;

    /**
     * Records a GC event for analysis.
     *
     * @param event the GC event to record
     */
    public void recordGCEvent(GCEvent event) {
        totalGCEvents.incrementAndGet();

        // Track pause time
        if (event.duration() > 0) {
            totalPauseTimeNanos.addAndGet(event.duration());
            updateMax(maxPauseTimeNanos, event.duration());

            // Update GC overhead calculation
            updateGCOverhead(event.duration());
        }

        // Track cause distribution
        if (event.gcCause() != null) {
            causeDistribution.computeIfAbsent(event.gcCause(), k -> new AtomicLong())
                    .incrementAndGet();
        }

        // Update heap state
        if (event.heapUsedAfter() > 0) {
            lastHeapUsed = event.heapUsedAfter();
        }
        if (event.heapCommitted() > 0) {
            lastHeapCommitted = event.heapCommitted();
        }
        lastGCTime = event.timestamp();

        // Add to recent GCs
        GCSummary summary = new GCSummary(
                event.timestamp(),
                event.gcName(),
                event.gcCause(),
                event.durationMs(),
                event.heapUsedBefore(),
                event.heapUsedAfter(),
                event.memoryReclaimed()
        );

        recentGCs.add(summary);
        while (recentGCs.size() > MAX_HISTORY_SIZE) {
            recentGCs.removeFirst();
        }
    }

    private synchronized void updateGCOverhead(long pauseNanos) {
        long currentTime = System.currentTimeMillis();
        overheadWindowPauseNanos += pauseNanos;

        // Calculate overhead every 10 seconds
        long windowDurationMs = currentTime - overheadWindowStartTime;
        if (windowDurationMs >= 10000) {
            // Convert window duration to nanos for calculation
            long windowDurationNanos = windowDurationMs * 1_000_000L;
            currentGcOverheadPercent = (overheadWindowPauseNanos * 100.0) / windowDurationNanos;

            // Reset window
            overheadWindowStartTime = currentTime;
            overheadWindowPauseNanos = 0;
        }
    }

    /**
     * Returns the GC analysis results.
     *
     * @return the GC analysis result
     */
    public GCAnalysisResult getAnalysis() {
        long total = totalGCEvents.get();
        long totalPause = totalPauseTimeNanos.get();
        double avgPause = total > 0 ? (totalPause / 1_000_000.0) / total : 0;
        long maxPause = maxPauseTimeNanos.get() / 1_000_000;

        // Get recent GCs (last 20)
        List<GCSummary> recent = recentGCs.stream()
                .sorted(Comparator.comparing(GCSummary::timestamp).reversed())
                .limit(MAX_RECENT_GCS)
                .toList();

        // Build cause distribution map
        Map<String, Long> causes = new ConcurrentHashMap<>();
        causeDistribution.forEach((cause, count) -> causes.put(cause, count.get()));

        // GC overhead warning if > 10%
        boolean overheadWarning = currentGcOverheadPercent > 10.0;

        // Compute real-time rate and leak metrics from all recorded events
        List<GCSummary> allSummaries = new ArrayList<>(recentGCs);
        GcMetricsComputer.RateMetrics rates = GcMetricsComputer.computeRates(allSummaries);
        GcMetricsComputer.LeakMetrics leak = GcMetricsComputer.detectLeak(allSummaries);

        return new GCAnalysisResult(
                total,
                totalPause / 1_000_000, // Convert to ms
                avgPause,
                maxPause,
                recent,
                causes,
                lastHeapUsed,
                lastHeapCommitted,
                lastGCTime,
                currentGcOverheadPercent,
                overheadWarning,
                rates.allocationRateKBPerSec(),
                rates.promotionRateKBPerSec(),
                leak.leakSuspected(),
                leak.confidencePercent()
        );
    }

    /**
     * Returns the current GC overhead percentage.
     *
     * @return GC overhead as a percentage (0-100)
     */
    public double getCurrentGcOverheadPercent() {
        return currentGcOverheadPercent;
    }

    /**
     * Returns the recent GC events for charting.
     *
     * @param limit maximum number of events to return
     * @return list of recent GC summaries
     */
    public List<GCSummary> getRecentGCs(int limit) {
        return recentGCs.stream()
                .sorted(Comparator.comparing(GCSummary::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Returns the current heap usage.
     *
     * @return current heap usage in bytes
     */
    public long getCurrentHeapUsed() {
        return lastHeapUsed;
    }

    /**
     * Returns the current committed heap.
     *
     * @return committed heap in bytes
     */
    public long getCurrentHeapCommitted() {
        return lastHeapCommitted;
    }

    /**
     * Clears all recorded data.
     */
    public void clear() {
        totalGCEvents.set(0);
        totalPauseTimeNanos.set(0);
        maxPauseTimeNanos.set(0);
        causeDistribution.clear();
        recentGCs.clear();
        lastHeapUsed = 0;
        lastHeapCommitted = 0;
        lastGCTime = null;
        overheadWindowStartTime = System.currentTimeMillis();
        overheadWindowPauseNanos = 0;
        currentGcOverheadPercent = 0;
    }

    private void updateMax(AtomicLong max, long value) {
        long current;
        do {
            current = max.get();
            if (value <= current) {
                return;
            }
        } while (!max.compareAndSet(current, value));
    }

    /**
     * Summary of a single GC event.
     */
    public static final class GCSummary {
        private final Instant timestamp;
        private final String gcName;
        private final String gcCause;
        private final double pauseTimeMs;
        private final long heapUsedBefore;
        private final long heapUsedAfter;
        private final long memoryReclaimed;

        public GCSummary(Instant timestamp, String gcName, String gcCause, double pauseTimeMs,
                         long heapUsedBefore, long heapUsedAfter, long memoryReclaimed) {
            this.timestamp = timestamp;
            this.gcName = gcName;
            this.gcCause = gcCause;
            this.pauseTimeMs = pauseTimeMs;
            this.heapUsedBefore = heapUsedBefore;
            this.heapUsedAfter = heapUsedAfter;
            this.memoryReclaimed = memoryReclaimed;
        }

        public Instant timestamp() { return timestamp; }
        public String gcName() { return gcName; }
        public String gcCause() { return gcCause; }
        public double pauseTimeMs() { return pauseTimeMs; }
        public long heapUsedBefore() { return heapUsedBefore; }
        public long heapUsedAfter() { return heapUsedAfter; }
        public long memoryReclaimed() { return memoryReclaimed; }
    }

    /**
     * Result of GC analysis.
     */
    public static final class GCAnalysisResult {
        private final long totalGCEvents;
        private final long totalPauseTimeMs;
        private final double avgPauseTimeMs;
        private final long maxPauseTimeMs;
        private final List<GCSummary> recentGCs;
        private final Map<String, Long> causeDistribution;
        private final long currentHeapUsed;
        private final long currentHeapCommitted;
        private final Instant lastGCTime;
        private final double gcOverheadPercent;
        private final boolean isOverheadWarning;
        private final double allocationRateKBPerSec;
        private final double promotionRateKBPerSec;
        private final boolean leakSuspected;
        private final double leakConfidencePercent;

        public GCAnalysisResult(long totalGCEvents, long totalPauseTimeMs, double avgPauseTimeMs,
                                long maxPauseTimeMs, List<GCSummary> recentGCs,
                                Map<String, Long> causeDistribution, long currentHeapUsed,
                                long currentHeapCommitted, Instant lastGCTime,
                                double gcOverheadPercent, boolean isOverheadWarning,
                                double allocationRateKBPerSec, double promotionRateKBPerSec,
                                boolean leakSuspected, double leakConfidencePercent) {
            this.totalGCEvents = totalGCEvents;
            this.totalPauseTimeMs = totalPauseTimeMs;
            this.avgPauseTimeMs = avgPauseTimeMs;
            this.maxPauseTimeMs = maxPauseTimeMs;
            this.recentGCs = recentGCs;
            this.causeDistribution = causeDistribution;
            this.currentHeapUsed = currentHeapUsed;
            this.currentHeapCommitted = currentHeapCommitted;
            this.lastGCTime = lastGCTime;
            this.gcOverheadPercent = gcOverheadPercent;
            this.isOverheadWarning = isOverheadWarning;
            this.allocationRateKBPerSec = allocationRateKBPerSec;
            this.promotionRateKBPerSec = promotionRateKBPerSec;
            this.leakSuspected = leakSuspected;
            this.leakConfidencePercent = leakConfidencePercent;
        }

        public long totalGCEvents() { return totalGCEvents; }
        public long totalPauseTimeMs() { return totalPauseTimeMs; }
        public double avgPauseTimeMs() { return avgPauseTimeMs; }
        public long maxPauseTimeMs() { return maxPauseTimeMs; }
        public List<GCSummary> recentGCs() { return recentGCs; }
        public Map<String, Long> causeDistribution() { return causeDistribution; }
        public long currentHeapUsed() { return currentHeapUsed; }
        public long currentHeapCommitted() { return currentHeapCommitted; }
        public Instant lastGCTime() { return lastGCTime; }
        public double gcOverheadPercent() { return gcOverheadPercent; }
        public boolean isOverheadWarning() { return isOverheadWarning; }
        public double allocationRateKBPerSec() { return allocationRateKBPerSec; }
        public double promotionRateKBPerSec() { return promotionRateKBPerSec; }
        public boolean leakSuspected() { return leakSuspected; }
        public double leakConfidencePercent() { return leakConfidencePercent; }
    }
}
