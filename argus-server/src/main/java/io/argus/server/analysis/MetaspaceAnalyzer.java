package io.argus.server.analysis;

import io.argus.core.event.MetaspaceEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes metaspace events and provides statistics.
 *
 * <p>Tracks metaspace usage, growth rate, and history.
 */
public final class MetaspaceAnalyzer {

    private static final int MAX_HISTORY_SIZE = 60;

    private final List<MetaspaceSnapshot> history = new CopyOnWriteArrayList<>();
    private final AtomicLong totalEvents = new AtomicLong(0);

    // Current state
    private volatile long currentUsed = 0;
    private volatile long currentCommitted = 0;
    private volatile long currentReserved = 0;
    private volatile long currentClassCount = 0;
    private volatile Instant lastUpdateTime = null;

    // Peak tracking
    private volatile long peakUsed = 0;

    // Growth tracking
    private volatile long initialUsed = -1;
    private volatile Instant initialTime = null;

    /**
     * Records a metaspace event for analysis.
     *
     * @param event the metaspace event to record
     */
    public void recordMetaspaceEvent(MetaspaceEvent event) {
        totalEvents.incrementAndGet();

        // Track initial value for growth calculation
        if (initialUsed < 0) {
            initialUsed = event.metaspaceUsed();
            initialTime = event.timestamp();
        }

        // Update current state
        currentUsed = event.metaspaceUsed();
        currentCommitted = event.metaspaceCommitted();
        currentReserved = event.metaspaceReserved();
        currentClassCount = event.classCount();
        lastUpdateTime = event.timestamp();

        // Track peak
        if (currentUsed > peakUsed) {
            peakUsed = currentUsed;
        }

        // Add to history
        MetaspaceSnapshot snapshot = new MetaspaceSnapshot(
                event.timestamp(),
                event.metaspaceUsed(),
                event.metaspaceCommitted(),
                event.metaspaceReserved(),
                event.classCount()
        );

        history.add(snapshot);
        while (history.size() > MAX_HISTORY_SIZE) {
            history.removeFirst();
        }
    }

    /**
     * Returns the metaspace analysis results.
     *
     * @return the metaspace analysis result
     */
    public MetaspaceAnalysisResult getAnalysis() {
        // Calculate growth rate (bytes per minute)
        double growthRatePerMin = 0;
        if (initialTime != null && lastUpdateTime != null && initialUsed >= 0) {
            long durationMs = java.time.Duration.between(initialTime, lastUpdateTime).toMillis();
            if (durationMs > 0) {
                double durationMinutes = durationMs / 60000.0;
                growthRatePerMin = (currentUsed - initialUsed) / durationMinutes;
            }
        }

        return new MetaspaceAnalysisResult(
                currentUsed,
                currentCommitted,
                currentReserved,
                currentClassCount,
                peakUsed,
                growthRatePerMin,
                new ArrayList<>(history),
                lastUpdateTime
        );
    }

    /**
     * Returns the metaspace history for charting.
     *
     * @return list of metaspace snapshots
     */
    public List<MetaspaceSnapshot> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Returns the current metaspace used.
     *
     * @return current metaspace used in bytes
     */
    public long getCurrentUsed() {
        return currentUsed;
    }

    /**
     * Returns the current metaspace committed.
     *
     * @return current metaspace committed in bytes
     */
    public long getCurrentCommitted() {
        return currentCommitted;
    }

    /**
     * Clears all recorded data.
     */
    public void clear() {
        history.clear();
        totalEvents.set(0);
        currentUsed = 0;
        currentCommitted = 0;
        currentReserved = 0;
        currentClassCount = 0;
        lastUpdateTime = null;
        peakUsed = 0;
        initialUsed = -1;
        initialTime = null;
    }

    /**
     * Snapshot of metaspace state at a point in time.
     */
    public record MetaspaceSnapshot(
            Instant timestamp,
            long used,
            long committed,
            long reserved,
            long classCount
    ) {
        /**
         * Returns the metaspace used in MB.
         */
        public double usedMB() {
            return used / (1024.0 * 1024.0);
        }

        /**
         * Returns the metaspace committed in MB.
         */
        public double committedMB() {
            return committed / (1024.0 * 1024.0);
        }
    }

    /**
     * Result of metaspace analysis.
     */
    public record MetaspaceAnalysisResult(
            long currentUsed,
            long currentCommitted,
            long currentReserved,
            long currentClassCount,
            long peakUsed,
            double growthRatePerMin,
            List<MetaspaceSnapshot> history,
            Instant lastUpdateTime
    ) {
        /**
         * Returns the current used in MB.
         */
        public double currentUsedMB() {
            return currentUsed / (1024.0 * 1024.0);
        }

        /**
         * Returns the current committed in MB.
         */
        public double currentCommittedMB() {
            return currentCommitted / (1024.0 * 1024.0);
        }

        /**
         * Returns the peak used in MB.
         */
        public double peakUsedMB() {
            return peakUsed / (1024.0 * 1024.0);
        }

        /**
         * Returns the growth rate in MB per minute.
         */
        public double growthRateMBPerMin() {
            return growthRatePerMin / (1024.0 * 1024.0);
        }
    }
}
