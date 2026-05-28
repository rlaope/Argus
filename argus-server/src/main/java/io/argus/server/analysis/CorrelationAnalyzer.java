package io.argus.server.analysis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * Default significant-pause threshold (ms). GC pauses at/above this are
     * "long enough to plausibly stall in-flight work" and are retained for the
     * timing-overlap join and exported as OTel spans. Overridable via
     * {@code argus.correlation.pause.threshold.ms}.
     */
    private static final double DEFAULT_SIGNIFICANT_PAUSE_MS = 50.0;

    /** How long recorded pause windows are kept for overlap queries. */
    private static final Duration PAUSE_WINDOW_RETENTION = Duration.ofMinutes(5);
    private static final int MAX_PAUSE_WINDOWS = 500;

    private final double significantPauseMs;

    private final List<CorrelatedEvent> gcCpuCorrelations = new CopyOnWriteArrayList<>();
    private final List<CorrelatedEvent> gcPinningCorrelations = new CopyOnWriteArrayList<>();
    private final List<Recommendation> recommendations = new CopyOnWriteArrayList<>();

    // Tracking for correlation detection
    private final List<GCTimestamp> recentGCEvents = new CopyOnWriteArrayList<>();
    private final List<CPUSpikeTimestamp> recentCPUSpikes = new CopyOnWriteArrayList<>();
    private final List<PinningTimestamp> recentPinningEvents = new CopyOnWriteArrayList<>();

    /** Recent significant GC pause windows, for the timing-overlap trace join. */
    private final List<PauseWindow> recentPauseWindows = new CopyOnWriteArrayList<>();

    /** Uses the default significant-pause threshold (50ms, overridable by system property). */
    public CorrelationAnalyzer() {
        double threshold = DEFAULT_SIGNIFICANT_PAUSE_MS;
        try {
            String prop = System.getProperty("argus.correlation.pause.threshold.ms");
            if (prop != null && !prop.isBlank()) {
                threshold = Double.parseDouble(prop.trim());
            }
        } catch (NumberFormatException ignored) {
            // keep default
        }
        this.significantPauseMs = threshold;
    }

    /** Explicit-threshold constructor, primarily for tests. */
    public CorrelationAnalyzer(double significantPauseMs) {
        this.significantPauseMs = significantPauseMs;
    }

    /** The significant-pause threshold in milliseconds. */
    public double significantPauseThresholdMs() {
        return significantPauseMs;
    }

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
     * Records a significant GC pause window for the timing-overlap trace join.
     *
     * <p>Pauses shorter than {@link #significantPauseThresholdMs()} are ignored:
     * they are too short to plausibly stall in-flight work. The {@code traceId}
     * is the W3C trace id captured on the recording thread at pause time, or
     * {@code null} when no OTel context was active (timing-only mode).
     *
     * @param pauseStart    wall-clock start of the pause
     * @param pauseEnd      wall-clock end of the pause
     * @param gcName        collector name
     * @param gcCause       GC cause
     * @param pauseMs       pause duration in milliseconds
     * @param reclaimedBytes heap bytes reclaimed (may be 0)
     * @param traceId       active trace id at pause time, or {@code null}
     * @return {@code true} if the pause was significant and recorded
     */
    public boolean recordPauseWindow(Instant pauseStart, Instant pauseEnd, String gcName,
                                     String gcCause, double pauseMs, long reclaimedBytes,
                                     String traceId) {
        if (pauseMs < significantPauseMs) {
            return false;
        }
        recentPauseWindows.add(new PauseWindow(pauseStart, pauseEnd, gcName, gcCause,
                pauseMs, reclaimedBytes, traceId));
        cleanOldPauseWindows();
        return true;
    }

    /**
     * Returns the significant GC pauses whose window overlaps {@code [from, to]}.
     * A pause overlaps when its end is not before {@code from} and its start is
     * not after {@code to}. These are the pauses long enough to plausibly stall
     * any work in flight during the queried window; each carries its trace id
     * when an OTel context was active at pause time.
     *
     * @param from window start (inclusive)
     * @param to   window end (inclusive)
     * @return overlapping pause windows, oldest first
     */
    public List<PauseWindow> pausesOverlapping(Instant from, Instant to) {
        List<PauseWindow> out = new ArrayList<>();
        for (PauseWindow w : recentPauseWindows) {
            if (!w.pauseEnd().isBefore(from) && !w.pauseStart().isAfter(to)) {
                out.add(w);
            }
        }
        out.sort(Comparator.comparing(PauseWindow::pauseStart));
        return out;
    }

    /** Returns recent significant pause windows (most recent last), capped for the surface. */
    public List<PauseWindow> getRecentPauseWindows() {
        return new ArrayList<>(recentPauseWindows);
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
                new ArrayList<>(recommendations),
                new ArrayList<>(recentPauseWindows),
                significantPauseMs
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
        recentPauseWindows.clear();
    }

    private void cleanOldPauseWindows() {
        Instant cutoff = Instant.now().minus(PAUSE_WINDOW_RETENTION);
        recentPauseWindows.removeIf(w -> w.pauseEnd().isBefore(cutoff));
        while (recentPauseWindows.size() > MAX_PAUSE_WINDOWS) {
            recentPauseWindows.remove(0);
        }
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
            list.remove(0);
        }
    }

    // Internal timestamp tracking classes
    private static final class GCTimestamp {
        final Instant timestamp;
        final String gcName;
        final double pauseTimeMs;
        GCTimestamp(Instant timestamp, String gcName, double pauseTimeMs) {
            this.timestamp = timestamp;
            this.gcName = gcName;
            this.pauseTimeMs = pauseTimeMs;
        }
    }

    private static final class CPUSpikeTimestamp {
        final Instant timestamp;
        final double cpuLoad;
        CPUSpikeTimestamp(Instant timestamp, double cpuLoad) {
            this.timestamp = timestamp;
            this.cpuLoad = cpuLoad;
        }
    }

    private static final class PinningTimestamp {
        final Instant timestamp;
        final String threadName;
        PinningTimestamp(Instant timestamp, String threadName) {
            this.timestamp = timestamp;
            this.threadName = threadName;
        }
    }

    /**
     * A correlated event between two event types.
     */
    public static final class CorrelatedEvent {
        private final Instant timestamp;
        private final String primaryEvent;
        private final String correlatedEvent;
        private final String description;

        public CorrelatedEvent(Instant timestamp, String primaryEvent, String correlatedEvent, String description) {
            this.timestamp = timestamp;
            this.primaryEvent = primaryEvent;
            this.correlatedEvent = correlatedEvent;
            this.description = description;
        }

        public Instant timestamp() { return timestamp; }
        public String primaryEvent() { return primaryEvent; }
        public String correlatedEvent() { return correlatedEvent; }
        public String description() { return description; }
    }

    /**
     * A significant GC pause window retained for the timing-overlap trace join.
     *
     * @param pauseStart     wall-clock start of the pause
     * @param pauseEnd       wall-clock end of the pause
     * @param gcName         collector name
     * @param gcCause        GC cause
     * @param pauseMs        pause duration in milliseconds
     * @param reclaimedBytes heap bytes reclaimed (may be 0)
     * @param traceId        active W3C trace id at pause time, or {@code null}
     */
    public record PauseWindow(Instant pauseStart, Instant pauseEnd, String gcName,
                              String gcCause, double pauseMs, long reclaimedBytes,
                              String traceId) {
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
    public static final class Recommendation {
        private final RecommendationType type;
        private final String title;
        private final String description;
        private final Severity severity;

        public Recommendation(RecommendationType type, String title, String description, Severity severity) {
            this.type = type;
            this.title = title;
            this.description = description;
            this.severity = severity;
        }

        public RecommendationType type() { return type; }
        public String title() { return title; }
        public String description() { return description; }
        public Severity severity() { return severity; }
    }

    /**
     * Result of correlation analysis.
     */
    public static final class CorrelationResult {
        private final List<CorrelatedEvent> gcCpuCorrelations;
        private final List<CorrelatedEvent> gcPinningCorrelations;
        private final List<Recommendation> recommendations;
        private final List<PauseWindow> tracePauses;
        private final double significantPauseThresholdMs;

        public CorrelationResult(List<CorrelatedEvent> gcCpuCorrelations,
                                 List<CorrelatedEvent> gcPinningCorrelations,
                                 List<Recommendation> recommendations,
                                 List<PauseWindow> tracePauses,
                                 double significantPauseThresholdMs) {
            this.gcCpuCorrelations = gcCpuCorrelations;
            this.gcPinningCorrelations = gcPinningCorrelations;
            this.recommendations = recommendations;
            this.tracePauses = tracePauses;
            this.significantPauseThresholdMs = significantPauseThresholdMs;
        }

        public List<CorrelatedEvent> gcCpuCorrelations() { return gcCpuCorrelations; }
        public List<CorrelatedEvent> gcPinningCorrelations() { return gcPinningCorrelations; }
        public List<Recommendation> recommendations() { return recommendations; }

        /** Recent significant GC pauses with optional trace ids (timing-overlap join). */
        public List<PauseWindow> tracePauses() { return tracePauses; }

        /** The significant-pause threshold (ms) used to gate {@link #tracePauses()}. */
        public double significantPauseThresholdMs() { return significantPauseThresholdMs; }
    }
}
