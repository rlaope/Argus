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

        return new GCAnalysisResult(
                total,
                totalPause / 1_000_000, // Convert to ms
                avgPause,
                maxPause,
                recent,
                causes,
                lastHeapUsed,
                lastHeapCommitted,
                lastGCTime
        );
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
    public record GCSummary(
            Instant timestamp,
            String gcName,
            String gcCause,
            double pauseTimeMs,
            long heapUsedBefore,
            long heapUsedAfter,
            long memoryReclaimed
    ) {
    }

    /**
     * Result of GC analysis.
     */
    public record GCAnalysisResult(
            long totalGCEvents,
            long totalPauseTimeMs,
            double avgPauseTimeMs,
            long maxPauseTimeMs,
            List<GCSummary> recentGCs,
            Map<String, Long> causeDistribution,
            long currentHeapUsed,
            long currentHeapCommitted,
            Instant lastGCTime
    ) {
    }
}
