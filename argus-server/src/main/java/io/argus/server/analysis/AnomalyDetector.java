package io.argus.server.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watches live CPU load and allocation rate and records an {@link AnomalyEvent}
 * when either signal stays over a configured threshold for a sustained window.
 *
 * <p>The detector is the W5 "anomaly-triggered profiling" seam: when continuous
 * profiling is enabled it records a "profile capture recommended" flag plus the
 * triggering reason, so the profile store / operator can act on it without the
 * detector itself driving the agent-side capture loop.
 *
 * <p>Thresholds and the sustained window are read once from system properties:
 * <ul>
 *   <li>{@code argus.anomaly.cpu.threshold} — JVM CPU fraction 0.0-1.0 (default 0.85)</li>
 *   <li>{@code argus.anomaly.alloc.threshold.kbps} — allocation rate in KB/s (default 500000)</li>
 *   <li>{@code argus.anomaly.sustained.samples} — consecutive over-threshold samples
 *       required before firing (default 3)</li>
 *   <li>{@code argus.anomaly.ring.size} — bounded recent-anomaly ring size (default 50)</li>
 * </ul>
 *
 * <p>Sample feeds ({@code recordCpuSample} / {@code recordAllocSample}) are called
 * from the same drain path that feeds {@code CPUAnalyzer} / {@code AllocationAnalyzer}.
 */
public final class AnomalyDetector {

    /** Type of signal that triggered an anomaly. */
    public enum AnomalyType { CPU, ALLOC }

    private static final double DEFAULT_CPU_THRESHOLD = 0.85;
    private static final double DEFAULT_ALLOC_THRESHOLD_KBPS = 500_000.0;
    private static final int DEFAULT_SUSTAINED_SAMPLES = 3;
    private static final int DEFAULT_RING_SIZE = 50;

    private final double cpuThreshold;
    private final double allocThresholdKbps;
    private final int sustainedSamples;
    private final int ringSize;
    private final boolean captureRecommendationEnabled;

    // Sustained-window counters: consecutive over-threshold samples per signal.
    private int cpuOverStreak = 0;
    private int allocOverStreak = 0;

    // Bounded ring of recent anomaly events (oldest first).
    private final ArrayList<AnomalyEvent> recent = new ArrayList<>();

    // Latest "profile capture recommended" recommendation; null when none active.
    private final AtomicReference<CaptureRecommendation> recommendation = new AtomicReference<>();

    /**
     * Creates a detector reading thresholds from system properties. When
     * {@code captureRecommendationEnabled} is true (continuous profiling is on),
     * a firing anomaly also records a capture recommendation with the reason.
     *
     * @param captureRecommendationEnabled whether to surface capture recommendations
     */
    public AnomalyDetector(boolean captureRecommendationEnabled) {
        this(
                doubleProp("argus.anomaly.cpu.threshold", DEFAULT_CPU_THRESHOLD),
                doubleProp("argus.anomaly.alloc.threshold.kbps", DEFAULT_ALLOC_THRESHOLD_KBPS),
                intProp("argus.anomaly.sustained.samples", DEFAULT_SUSTAINED_SAMPLES),
                intProp("argus.anomaly.ring.size", DEFAULT_RING_SIZE),
                captureRecommendationEnabled);
    }

    /**
     * Creates a detector with explicit thresholds (used by tests).
     *
     * @param cpuThreshold                 JVM CPU fraction (0.0-1.0) that, when sustained, fires
     * @param allocThresholdKbps           allocation rate in KB/s that, when sustained, fires
     * @param sustainedSamples             consecutive over-threshold samples required before firing
     * @param ringSize                     bounded recent-anomaly ring size
     * @param captureRecommendationEnabled whether to surface capture recommendations
     */
    public AnomalyDetector(double cpuThreshold, double allocThresholdKbps,
                           int sustainedSamples, int ringSize,
                           boolean captureRecommendationEnabled) {
        this.cpuThreshold = cpuThreshold;
        this.allocThresholdKbps = allocThresholdKbps;
        this.sustainedSamples = Math.max(1, sustainedSamples);
        this.ringSize = Math.max(1, ringSize);
        this.captureRecommendationEnabled = captureRecommendationEnabled;
    }

    /**
     * Records a CPU load sample. Fires a CPU anomaly only once the value has
     * stayed over the threshold for {@code sustainedSamples} consecutive samples;
     * a single under-threshold sample resets the streak.
     *
     * @param jvmCpuFraction JVM CPU load as a fraction (0.0-1.0)
     * @param timestamp      sample timestamp (null uses now)
     * @return the fired anomaly, or {@code null} if none fired on this sample
     */
    public synchronized AnomalyEvent recordCpuSample(double jvmCpuFraction, Instant timestamp) {
        if (jvmCpuFraction > cpuThreshold) {
            cpuOverStreak++;
            if (cpuOverStreak >= sustainedSamples) {
                // Reset the streak after firing so a continuing over-threshold run
                // re-arms and can fire again once the window is satisfied again.
                cpuOverStreak = 0;
                String reason = String.format(
                        "JVM CPU %.0f%% over threshold %.0f%% for %d consecutive samples",
                        jvmCpuFraction * 100, cpuThreshold * 100, sustainedSamples);
                return fire(new AnomalyEvent(at(timestamp), AnomalyType.CPU,
                        jvmCpuFraction, cpuThreshold, reason));
            }
        } else {
            cpuOverStreak = 0;
        }
        return null;
    }

    /**
     * Records an allocation-rate sample. Fires an ALLOC anomaly only once the
     * rate has stayed over the threshold for {@code sustainedSamples} consecutive
     * samples; a single under-threshold sample resets the streak.
     *
     * @param allocRateKbps allocation rate in KB/s
     * @param timestamp     sample timestamp (null uses now)
     * @return the fired anomaly, or {@code null} if none fired on this sample
     */
    public synchronized AnomalyEvent recordAllocSample(double allocRateKbps, Instant timestamp) {
        if (allocRateKbps > allocThresholdKbps) {
            allocOverStreak++;
            if (allocOverStreak >= sustainedSamples) {
                // Reset the streak after firing so a continuing over-threshold run
                // re-arms and can fire again once the window is satisfied again.
                allocOverStreak = 0;
                String reason = String.format(
                        "Allocation rate %.0f KB/s over threshold %.0f KB/s for %d consecutive samples",
                        allocRateKbps, allocThresholdKbps, sustainedSamples);
                return fire(new AnomalyEvent(at(timestamp), AnomalyType.ALLOC,
                        allocRateKbps, allocThresholdKbps, reason));
            }
        } else {
            allocOverStreak = 0;
        }
        return null;
    }

    private AnomalyEvent fire(AnomalyEvent event) {
        recent.add(event);
        while (recent.size() > ringSize) {
            recent.remove(0);
        }
        if (captureRecommendationEnabled) {
            recommendation.set(new CaptureRecommendation(event.timestamp(), event.type(), event.reason()));
        }
        return event;
    }

    private static Instant at(Instant timestamp) {
        return timestamp != null ? timestamp : Instant.now();
    }

    /**
     * Returns a snapshot of recent anomaly events (oldest first), bounded to the
     * configured ring size.
     *
     * @return list of recent anomaly events
     */
    public synchronized List<AnomalyEvent> recentAnomalies() {
        return new ArrayList<>(recent);
    }

    /**
     * Returns the most recent anomaly event, or {@code null} if none recorded.
     *
     * @return the latest anomaly, or null
     */
    public synchronized AnomalyEvent latest() {
        return recent.isEmpty() ? null : recent.get(recent.size() - 1);
    }

    /**
     * Returns whether a short profile capture is currently recommended (set when
     * an anomaly fires and continuous profiling is enabled). Returns {@code false}
     * when recommendations are disabled or none has fired.
     *
     * @return true if a capture is recommended
     */
    public boolean isCaptureRecommended() {
        return recommendation.get() != null;
    }

    /**
     * Returns the active capture recommendation (with its triggering reason), or
     * {@code null} if none is active.
     *
     * @return the capture recommendation, or null
     */
    public CaptureRecommendation captureRecommendation() {
        return recommendation.get();
    }

    /**
     * Clears the active capture recommendation once acted upon (e.g. after the
     * profile store / operator captures a profile).
     */
    public void clearCaptureRecommendation() {
        recommendation.set(null);
    }

    /**
     * Clears all recorded anomalies and resets the sustained-window streaks.
     */
    public synchronized void clear() {
        recent.clear();
        cpuOverStreak = 0;
        allocOverStreak = 0;
        recommendation.set(null);
    }

    private static double doubleProp(String key, double def) {
        try {
            String v = System.getProperty(key);
            return v != null ? Double.parseDouble(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int intProp(String key, int def) {
        return Integer.getInteger(key, def);
    }

    /**
     * A recorded anomaly: the moment a watched signal crossed its threshold for a
     * sustained window.
     */
    public static final class AnomalyEvent {
        private final Instant timestamp;
        private final AnomalyType type;
        private final double value;
        private final double threshold;
        private final String reason;

        public AnomalyEvent(Instant timestamp, AnomalyType type, double value,
                            double threshold, String reason) {
            this.timestamp = timestamp;
            this.type = type;
            this.value = value;
            this.threshold = threshold;
            this.reason = reason;
        }

        public Instant timestamp() { return timestamp; }
        public AnomalyType type() { return type; }
        public double value() { return value; }
        public double threshold() { return threshold; }
        public String reason() { return reason; }
    }

    /**
     * A recommendation that a short profile capture should be taken, attributed
     * to the anomaly that triggered it.
     */
    public static final class CaptureRecommendation {
        private final Instant timestamp;
        private final AnomalyType triggerType;
        private final String reason;

        public CaptureRecommendation(Instant timestamp, AnomalyType triggerType, String reason) {
            this.timestamp = timestamp;
            this.triggerType = triggerType;
            this.reason = reason;
        }

        public Instant timestamp() { return timestamp; }
        public AnomalyType triggerType() { return triggerType; }
        public String reason() { return reason; }
    }
}
