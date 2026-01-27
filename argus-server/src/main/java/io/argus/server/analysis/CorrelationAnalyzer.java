package io.argus.server.analysis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Analyzes correlations between different event types.
 *
 * <p>Detects patterns such as:
 * <ul>
 *   <li>GC ↔ CPU spike correlations</li>
 *   <li>GC ↔ Pinning correlations</li>
 *   <li>Automatic recommendations based on detected patterns</li>
 * </ul>
 */
public final class CorrelationAnalyzer {

    private static final int MAX_CORRELATIONS = 50;
    private static final Duration CORRELATION_WINDOW = Duration.ofSeconds(1);
    private static final double CPU_SPIKE_THRESHOLD = 0.7; // 70% CPU
    private static final double GC_OVERHEAD_WARNING_THRESHOLD = 0.10; // 10%
    private static final double HIGH_ALLOCATION_RATE_THRESHOLD = 100 * 1024 * 1024; // 100 MB/s

    private final List<CorrelatedEvent> gcCpuCorrelations = new CopyOnWriteArrayList<>();
    private final List<CorrelatedEvent> gcPinningCorrelations = new CopyOnWriteArrayList<>();
    private final List<Recommendation> recommendations = new CopyOnWriteArrayList<>();

    // Tracking for correlation detection
    private final List<GCTimestamp> recentGCEvents = new CopyOnWriteArrayList<>();
    private final List<CPUSpikeTimestamp> recentCPUSpikes = new CopyOnWriteArrayList<>();
    private final List<PinningTimestamp> recentPinningEvents = new CopyOnWriteArrayList<>();

    /**
     * Records a GC event for correlation analysis.
     *
     * @param timestamp   the GC event timestamp
     * @param gcName      the GC name
     * @param pauseTimeMs the pause time in milliseconds
     */
    public void recordGCEvent(Instant timestamp, String gcName, double pauseTimeMs) {
        recentGCEvents.add(new GCTimestamp(timestamp, gcName, pauseTimeMs));

        // Clean old events
        cleanOldEvents();

        // Check for correlations
        checkGCCPUCorrelation(timestamp, gcName, pauseTimeMs);
        checkGCPinningCorrelation(timestamp, gcName, pauseTimeMs);
    }

    /**
     * Records a CPU spike for correlation analysis.
     *
     * @param timestamp the spike timestamp
     * @param cpuLoad   the CPU load (0.0-1.0)
     */
    public void recordCPUSpike(Instant timestamp, double cpuLoad) {
        if (cpuLoad >= CPU_SPIKE_THRESHOLD) {
            recentCPUSpikes.add(new CPUSpikeTimestamp(timestamp, cpuLoad));
            cleanOldEvents();
        }
    }

    /**
     * Records a pinning event for correlation analysis.
     *
     * @param timestamp  the pinning timestamp
     * @param threadName the thread name
     */
    public void recordPinningEvent(Instant timestamp, String threadName) {
        recentPinningEvents.add(new PinningTimestamp(timestamp, threadName));
        cleanOldEvents();
    }

    /**
     * Updates recommendations based on current metrics.
     *
     * @param gcOverheadPercent   current GC overhead percentage
     * @param heapGrowthRateMB    heap growth rate in MB/min
     * @param allocationRateMBps  allocation rate in MB/s
     * @param contentionTimeMs    total contention time in ms
     * @param metaspaceGrowthMB   metaspace growth rate in MB/min
     */
    public void updateRecommendations(double gcOverheadPercent, double heapGrowthRateMB,
                                      double allocationRateMBps, long contentionTimeMs,
                                      double metaspaceGrowthMB) {
        recommendations.clear();

        // GC overhead warning
        if (gcOverheadPercent > GC_OVERHEAD_WARNING_THRESHOLD * 100) {
            recommendations.add(new Recommendation(
                    RecommendationType.GC_OVERHEAD_HIGH,
                    "High GC Overhead",
                    String.format("GC overhead is %.1f%%, exceeding the 10%% threshold. " +
                            "Consider increasing heap size or tuning GC parameters.", gcOverheadPercent),
                    Severity.WARNING
            ));
        }

        // Memory leak suspected
        if (heapGrowthRateMB > 10) { // 10 MB/min growth
            recommendations.add(new Recommendation(
                    RecommendationType.MEMORY_LEAK_SUSPECTED,
                    "Potential Memory Leak",
                    String.format("Heap is growing at %.1f MB/min. This may indicate a memory leak. " +
                            "Consider using heap dump analysis.", heapGrowthRateMB),
                    Severity.WARNING
            ));
        }

        // High allocation rate
        if (allocationRateMBps > HIGH_ALLOCATION_RATE_THRESHOLD / (1024 * 1024)) {
            recommendations.add(new Recommendation(
                    RecommendationType.ALLOCATION_RATE_HIGH,
                    "High Allocation Rate",
                    String.format("Allocation rate is %.1f MB/s. High allocation can cause frequent GC. " +
                            "Consider object pooling or reducing allocations.", allocationRateMBps),
                    Severity.INFO
            ));
        }

        // Contention hotspot
        if (contentionTimeMs > 1000) { // More than 1 second of contention
            recommendations.add(new Recommendation(
                    RecommendationType.CONTENTION_HOTSPOT,
                    "Lock Contention Detected",
                    String.format("Total lock contention time is %d ms. " +
                            "Review synchronized blocks and consider using concurrent alternatives.", contentionTimeMs),
                    Severity.WARNING
            ));
        }

        // Metaspace growth
        if (metaspaceGrowthMB > 1) { // 1 MB/min growth
            recommendations.add(new Recommendation(
                    RecommendationType.METASPACE_GROWTH,
                    "Metaspace Growing",
                    String.format("Metaspace is growing at %.2f MB/min. " +
                            "This may indicate class loader leaks or excessive dynamic class generation.", metaspaceGrowthMB),
                    Severity.INFO
            ));
        }
    }

    /**
     * Returns the correlation analysis results.
     *
     * @return the correlation result
     */
    public CorrelationResult getAnalysis() {
        return new CorrelationResult(
                new ArrayList<>(gcCpuCorrelations),
                new ArrayList<>(gcPinningCorrelations),
                new ArrayList<>(recommendations)
        );
    }

    /**
     * Clears all recorded data.
     */
    public void clear() {
        gcCpuCorrelations.clear();
        gcPinningCorrelations.clear();
        recommendations.clear();
        recentGCEvents.clear();
        recentCPUSpikes.clear();
        recentPinningEvents.clear();
    }

    private void checkGCCPUCorrelation(Instant gcTimestamp, String gcName, double pauseTimeMs) {
        // Look for CPU spikes within 1 second of GC event
        for (CPUSpikeTimestamp spike : recentCPUSpikes) {
            Duration diff = Duration.between(spike.timestamp, gcTimestamp).abs();
            if (diff.compareTo(CORRELATION_WINDOW) <= 0) {
                CorrelatedEvent correlation = new CorrelatedEvent(
                        gcTimestamp,
                        "GC_PAUSE",
                        "CPU_SPIKE",
                        String.format("GC '%s' (%.1fms pause) occurred with CPU spike (%.1f%%)",
                                gcName, pauseTimeMs, spike.cpuLoad * 100)
                );
                gcCpuCorrelations.add(correlation);
                trimList(gcCpuCorrelations);
            }
        }
    }

    private void checkGCPinningCorrelation(Instant gcTimestamp, String gcName, double pauseTimeMs) {
        // Look for pinning events within 1 second of GC event
        for (PinningTimestamp pinning : recentPinningEvents) {
            Duration diff = Duration.between(pinning.timestamp, gcTimestamp).abs();
            if (diff.compareTo(CORRELATION_WINDOW) <= 0) {
                CorrelatedEvent correlation = new CorrelatedEvent(
                        gcTimestamp,
                        "GC_PAUSE",
                        "PINNING",
                        String.format("GC '%s' occurred with pinned thread '%s'",
                                gcName, pinning.threadName)
                );
                gcPinningCorrelations.add(correlation);
                trimList(gcPinningCorrelations);
            }
        }
    }

    private void cleanOldEvents() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(10));
        recentGCEvents.removeIf(e -> e.timestamp.isBefore(cutoff));
        recentCPUSpikes.removeIf(e -> e.timestamp.isBefore(cutoff));
        recentPinningEvents.removeIf(e -> e.timestamp.isBefore(cutoff));
    }

    private void trimList(List<?> list) {
        while (list.size() > MAX_CORRELATIONS) {
            list.removeFirst();
        }
    }

    // Internal timestamp tracking records
    private record GCTimestamp(Instant timestamp, String gcName, double pauseTimeMs) {}
    private record CPUSpikeTimestamp(Instant timestamp, double cpuLoad) {}
    private record PinningTimestamp(Instant timestamp, String threadName) {}

    /**
     * A correlated event between two event types.
     */
    public record CorrelatedEvent(
            Instant timestamp,
            String primaryEvent,
            String correlatedEvent,
            String description
    ) {
    }

    /**
     * Types of recommendations.
     */
    public enum RecommendationType {
        GC_OVERHEAD_HIGH,
        MEMORY_LEAK_SUSPECTED,
        CONTENTION_HOTSPOT,
        ALLOCATION_RATE_HIGH,
        METASPACE_GROWTH
    }

    /**
     * Severity levels for recommendations.
     */
    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    /**
     * A recommendation based on detected patterns.
     */
    public record Recommendation(
            RecommendationType type,
            String title,
            String description,
            Severity severity
    ) {
    }

    /**
     * Result of correlation analysis.
     */
    public record CorrelationResult(
            List<CorrelatedEvent> gcCpuCorrelations,
            List<CorrelatedEvent> gcPinningCorrelations,
            List<Recommendation> recommendations
    ) {
    }
}
