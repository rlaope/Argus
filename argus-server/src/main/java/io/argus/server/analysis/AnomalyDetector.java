package io.argus.server.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watches live CPU load and allocation rate and records an {@link AnomalyEvent}
 * when either signal stays over a threshold for a sustained window. The threshold
 * can be a {@link AnomalyMode#FIXED fixed} static value (default, back-compat) or
 * a {@link AnomalyMode#LEARNED learned} per-signal baseline.
 *
 * <p>The detector is the W5 "anomaly-triggered profiling" seam: when continuous
 * profiling is enabled it records a "profile capture recommended" flag plus the
 * triggering reason, so the profile store / operator can act on it without the
 * detector itself driving the agent-side capture loop.
 *
 * <p>Thresholds, the sustained window, and the learned-baseline knobs are read
 * once from system properties:
 * <ul>
 *   <li>{@code argus.anomaly.mode} — {@code fixed} | {@code learned} | {@code both} (default {@code fixed})</li>
 *   <li>{@code argus.anomaly.cpu.threshold} — JVM CPU fraction 0.0-1.0 (default 0.85)</li>
 *   <li>{@code argus.anomaly.alloc.threshold.kbps} — allocation rate in KB/s (default 500000)</li>
 *   <li>{@code argus.anomaly.sustained.samples} — consecutive over-threshold samples
 *       required before firing (default 3)</li>
 *   <li>{@code argus.anomaly.ring.size} — bounded recent-anomaly ring size (default 50)</li>
 *   <li>{@code argus.anomaly.learned.k} — sigma multiplier for the z-score fence (default 3.0)</li>
 *   <li>{@code argus.anomaly.learned.window} — rolling IQR window size, bounds memory (default 200)</li>
 *   <li>{@code argus.anomaly.learned.warmup} — samples required before a learned baseline can fire (default 30)</li>
 * </ul>
 *
 * <p>Learned mode keeps a bounded per-signal {@link SignalEstimator}: a Welford
 * running mean/variance, an EWMA, and a fixed-size rolling window for the IQR. A
 * sample is "over threshold" when it exceeds {@code mean + k*sigma} OR the IQR
 * upper fence {@code Q3 + 1.5*IQR}. The same sustained-streak counters, ring
 * buffer and {@link CaptureRecommendation} machinery as fixed mode are reused.
 *
 * <p>Sample feeds ({@code recordCpuSample} / {@code recordAllocSample}) are called
 * from the same drain path that feeds {@code CPUAnalyzer} / {@code AllocationAnalyzer}.
 */
public final class AnomalyDetector {

    /** Type of signal that triggered an anomaly. */
    public enum AnomalyType { CPU, ALLOC }

    /** Thresholding strategy. */
    public enum AnomalyMode {
        /** Static thresholds only (default, byte-for-byte legacy behaviour). */
        FIXED,
        /** Learned per-signal baseline only (z-score / IQR fence). */
        LEARNED,
        /** Fire when either the fixed threshold or the learned baseline trips. */
        BOTH;

        static AnomalyMode fromProperty(String raw, AnomalyMode def) {
            if (raw == null) {
                return def;
            }
            switch (raw.trim().toLowerCase()) {
                case "fixed": return FIXED;
                case "learned": return LEARNED;
                case "both": return BOTH;
                default: return def;
            }
        }
    }

    private static final double DEFAULT_CPU_THRESHOLD = 0.85;
    private static final double DEFAULT_ALLOC_THRESHOLD_KBPS = 500_000.0;
    private static final int DEFAULT_SUSTAINED_SAMPLES = 3;
    private static final int DEFAULT_RING_SIZE = 50;
    private static final AnomalyMode DEFAULT_MODE = AnomalyMode.FIXED;
    private static final double DEFAULT_LEARNED_K = 3.0;
    private static final int DEFAULT_LEARNED_WINDOW = 200;
    private static final int DEFAULT_LEARNED_WARMUP = 30;

    private final double cpuThreshold;
    private final double allocThresholdKbps;
    private final int sustainedSamples;
    private final int ringSize;
    private final boolean captureRecommendationEnabled;

    private final AnomalyMode mode;
    private final double learnedK;
    private final SignalEstimator cpuEstimator;
    private final SignalEstimator allocEstimator;

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
                captureRecommendationEnabled,
                AnomalyMode.fromProperty(System.getProperty("argus.anomaly.mode"), DEFAULT_MODE),
                doubleProp("argus.anomaly.learned.k", DEFAULT_LEARNED_K),
                intProp("argus.anomaly.learned.window", DEFAULT_LEARNED_WINDOW),
                intProp("argus.anomaly.learned.warmup", DEFAULT_LEARNED_WARMUP));
    }

    /**
     * Creates a fixed-mode detector with explicit thresholds (back-compat test seam).
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
        this(cpuThreshold, allocThresholdKbps, sustainedSamples, ringSize,
                captureRecommendationEnabled, AnomalyMode.FIXED,
                DEFAULT_LEARNED_K, DEFAULT_LEARNED_WINDOW, DEFAULT_LEARNED_WARMUP);
    }

    /**
     * Creates a detector with explicit thresholds and learned-baseline config
     * (used by tests of learned/both modes).
     *
     * @param cpuThreshold                 JVM CPU fraction (0.0-1.0) for the fixed fence
     * @param allocThresholdKbps           allocation rate in KB/s for the fixed fence
     * @param sustainedSamples             consecutive over-threshold samples required before firing
     * @param ringSize                     bounded recent-anomaly ring size
     * @param captureRecommendationEnabled whether to surface capture recommendations
     * @param mode                         thresholding strategy (fixed/learned/both)
     * @param learnedK                     sigma multiplier for the z-score fence
     * @param learnedWindow                rolling IQR window size (bounds memory)
     * @param learnedWarmup                samples required before a learned baseline can fire
     */
    public AnomalyDetector(double cpuThreshold, double allocThresholdKbps,
                           int sustainedSamples, int ringSize,
                           boolean captureRecommendationEnabled,
                           AnomalyMode mode, double learnedK,
                           int learnedWindow, int learnedWarmup) {
        this.cpuThreshold = cpuThreshold;
        this.allocThresholdKbps = allocThresholdKbps;
        this.sustainedSamples = Math.max(1, sustainedSamples);
        this.ringSize = Math.max(1, ringSize);
        this.captureRecommendationEnabled = captureRecommendationEnabled;
        this.mode = mode != null ? mode : AnomalyMode.FIXED;
        this.learnedK = learnedK;
        int window = Math.max(2, learnedWindow);
        int warmup = Math.max(2, learnedWarmup);
        this.cpuEstimator = new SignalEstimator(window, warmup);
        this.allocEstimator = new SignalEstimator(window, warmup);
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
        // In learned/both mode the baseline is updated only from non-anomalous
        // samples; the decision below reads the pre-update fences so the current
        // sample is judged against the baseline that excludes it.
        Decision decision = decide(jvmCpuFraction, cpuThreshold, cpuEstimator);
        if (decision.over) {
            cpuOverStreak++;
            if (cpuOverStreak >= sustainedSamples) {
                // Reset the streak after firing so a continuing over-threshold run
                // re-arms and can fire again once the window is satisfied again.
                cpuOverStreak = 0;
                String reason = decision.reason("JVM CPU",
                        String.format("%.0f%%", jvmCpuFraction * 100),
                        String.format("%.0f%%", cpuThreshold * 100), sustainedSamples);
                return fire(new AnomalyEvent(at(timestamp), AnomalyType.CPU,
                        jvmCpuFraction, decision.effectiveThreshold, reason));
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
        Decision decision = decide(allocRateKbps, allocThresholdKbps, allocEstimator);
        if (decision.over) {
            allocOverStreak++;
            if (allocOverStreak >= sustainedSamples) {
                // Reset the streak after firing so a continuing over-threshold run
                // re-arms and can fire again once the window is satisfied again.
                allocOverStreak = 0;
                String reason = decision.reason("Allocation rate",
                        String.format("%.0f KB/s", allocRateKbps),
                        String.format("%.0f KB/s", allocThresholdKbps), sustainedSamples);
                return fire(new AnomalyEvent(at(timestamp), AnomalyType.ALLOC,
                        allocRateKbps, decision.effectiveThreshold, reason));
            }
        } else {
            allocOverStreak = 0;
        }
        return null;
    }

    /**
     * Decides whether {@code value} is over threshold for the configured mode and,
     * for learned/both modes, conditionally folds the sample into the per-signal
     * baseline.
     *
     * <p>The current sample is judged against the baseline computed from prior
     * samples (the fences are read before the sample is added), so a regime step is
     * not "explained away" by the very sample that caused it. A sample judged
     * anomalous by a learned fence is deliberately <em>not</em> folded into the
     * estimator, so a sustained regime shift keeps firing instead of being absorbed
     * into the baseline; only non-anomalous samples update the adaptive baseline.
     *
     * <p>Both learned arms share one bounded rolling-window baseline: the z-score
     * fence uses the windowed mean/standard deviation and the IQR fence uses the
     * same window. The IQR fence is computed lazily — only when the z-score arm did
     * not already decide the sample is over — to avoid the sort on the common path.
     */
    private Decision decide(double value, double fixedThreshold, SignalEstimator est) {
        boolean fixedOver = value > fixedThreshold;

        if (mode == AnomalyMode.FIXED) {
            return Decision.fixed(fixedOver, fixedThreshold);
        }

        boolean warmedUp = est.ready();
        SignalEstimator.WindowStats stats = est.windowStats();   // one pass over the window

        boolean learnedOver = false;
        String learnedKind = null;
        double learnedThreshold = Double.NaN;
        if (warmedUp) {
            double sigmaFence = stats.zScoreFence(learnedK);   // windowed mean + k*sigma
            if (!Double.isNaN(sigmaFence) && value > sigmaFence) {
                learnedOver = true;
                learnedKind = "z-score";
                learnedThreshold = sigmaFence;
            } else {
                double iqrFence = stats.iqrUpperFence();        // Q3 + 1.5*IQR (lazy)
                if (!Double.isNaN(iqrFence) && value > iqrFence) {
                    learnedOver = true;
                    learnedKind = "IQR";
                    learnedThreshold = iqrFence;
                }
            }
        }

        // Do not poison the baseline with an anomalous sample: a sustained regime
        // shift would otherwise be absorbed and the detector would go quiet after
        // firing once. Non-anomalous samples still track normal variation.
        if (!learnedOver) {
            est.add(value);
        }

        if (mode == AnomalyMode.LEARNED) {
            return Decision.learned(learnedOver, learnedThreshold, learnedKind);
        }
        // BOTH: fixed takes precedence in the surfaced threshold/reason.
        if (fixedOver) {
            return Decision.fixed(true, fixedThreshold);
        }
        return Decision.learned(learnedOver, learnedThreshold, learnedKind);
    }

    /** Outcome of an over-threshold decision for a single sample. */
    private static final class Decision {
        final boolean over;
        final double effectiveThreshold;
        final boolean learned;
        final String learnedKind;

        private Decision(boolean over, double effectiveThreshold, boolean learned, String learnedKind) {
            this.over = over;
            this.effectiveThreshold = effectiveThreshold;
            this.learned = learned;
            this.learnedKind = learnedKind;
        }

        static Decision fixed(boolean over, double threshold) {
            return new Decision(over, threshold, false, null);
        }

        static Decision learned(boolean over, double threshold, String kind) {
            return new Decision(over, threshold, true, kind);
        }

        /** Builds the firing reason; FIXED reproduces the legacy string byte-for-byte. */
        String reason(String label, String valueStr, String fixedThreshStr, int sustainedSamples) {
            if (!learned) {
                return String.format("%s %s over threshold %s for %d consecutive samples",
                        label, valueStr, fixedThreshStr, sustainedSamples);
            }
            String threshStr = label.startsWith("JVM CPU")
                    ? String.format("%.0f%%", effectiveThreshold * 100)
                    : String.format("%.0f KB/s", effectiveThreshold);
            return String.format("%s %s over learned %s baseline %s for %d consecutive samples",
                    label, valueStr, learnedKind, threshStr, sustainedSamples);
        }
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
        cpuEstimator.reset();
        allocEstimator.reset();
        recommendation.set(null);
    }

    /**
     * Returns the configured thresholding mode.
     *
     * @return the anomaly mode (fixed/learned/both)
     */
    public AnomalyMode mode() {
        return mode;
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

    /**
     * Bounded online estimator for one signal: a fixed-size rolling window that
     * backs a single adaptive baseline, plus an EWMA smoothed level. Both learned
     * fences (windowed z-score and IQR) are derived from the same rolling window, so
     * the two arms never disagree about "baseline" and both adapt to drift. Memory
     * is O(window) regardless of how many samples are fed, so a long run never grows
     * unbounded.
     *
     * <p>Not thread-safe on its own; the enclosing detector guards all access with
     * its {@code synchronized} sample methods.
     */
    static final class SignalEstimator {
        private static final double EWMA_ALPHA = 0.1;

        private final int window;
        private final int warmup;
        private final double[] ring;   // rolling window of recent samples
        private int ringCount = 0;     // valid entries in the ring (<= window)
        private int ringHead = 0;      // next write position

        // Total samples folded into the baseline so far (drives warm-up only;
        // capped at warmup so a long run never overflows).
        private long count = 0;

        // Exponentially weighted moving average (seeded on first sample).
        private double ewma = 0.0;
        private boolean ewmaSeeded = false;

        SignalEstimator(int window, int warmup) {
            this.window = Math.max(2, window);
            this.warmup = Math.max(2, warmup);
            this.ring = new double[this.window];
        }

        /** Folds a sample into the rolling window and EWMA. Bounded: overwrites the oldest ring slot. */
        void add(double value) {
            if (count < warmup) {
                count++;
            }

            if (!ewmaSeeded) {
                ewma = value;
                ewmaSeeded = true;
            } else {
                ewma = EWMA_ALPHA * value + (1 - EWMA_ALPHA) * ewma;
            }

            ring[ringHead] = value;
            ringHead = (ringHead + 1) % window;
            if (ringCount < window) {
                ringCount++;
            }
        }

        /** True once enough samples have been folded in to trust the baseline. */
        boolean ready() {
            return count >= warmup;
        }

        /** EWMA of the signal (smoothed level); 0.0 before the first sample. */
        double ewma() {
            return ewma;
        }

        /**
         * Snapshots the rolling window into a {@link WindowStats}, computing the
         * windowed mean and variance in a single pass. The IQR fence is derived
         * lazily from the same snapshot only when requested, so the common path
         * (z-score decides) never sorts the window.
         */
        WindowStats windowStats() {
            if (ringCount < 2) {
                return WindowStats.EMPTY;
            }
            double[] snapshot = new double[ringCount];
            System.arraycopy(ring, 0, snapshot, 0, ringCount);
            double sum = 0.0;
            for (double v : snapshot) {
                sum += v;
            }
            double mean = sum / ringCount;
            double m2 = 0.0;
            for (double v : snapshot) {
                double d = v - mean;
                m2 += d * d;
            }
            double variance = m2 / (ringCount - 1); // sample variance
            return new WindowStats(snapshot, mean, Math.sqrt(variance));
        }

        /** Linear-interpolation percentile over a pre-sorted array. */
        private static double percentile(double[] sorted, double p) {
            if (sorted.length == 1) {
                return sorted[0];
            }
            double idx = p * (sorted.length - 1);
            int lo = (int) Math.floor(idx);
            int hi = (int) Math.ceil(idx);
            if (lo == hi) {
                return sorted[lo];
            }
            double frac = idx - lo;
            return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
        }

        /** Current rolling-window occupancy; never exceeds the configured window. */
        int windowOccupancy() {
            return ringCount;
        }

        /** Configured rolling-window capacity. */
        int windowCapacity() {
            return window;
        }

        void reset() {
            ringCount = 0;
            ringHead = 0;
            count = 0;
            ewma = 0.0;
            ewmaSeeded = false;
        }

        /**
         * Immutable snapshot of the rolling window plus its windowed mean and
         * standard deviation. Both learned fences read from this single snapshot so
         * they share one adaptive baseline; the IQR fence sorts the snapshot lazily,
         * only when requested.
         */
        static final class WindowStats {
            static final WindowStats EMPTY = new WindowStats(null, Double.NaN, Double.NaN);

            private final double[] samples; // window snapshot (unsorted); null when empty
            private final double mean;
            private final double stdDev;
            private boolean sorted = false;

            WindowStats(double[] samples, double mean, double stdDev) {
                this.samples = samples;
                this.mean = mean;
                this.stdDev = stdDev;
            }

            /** Windowed upper z-score fence: mean + k*sigma. NaN before two samples. */
            double zScoreFence(double k) {
                if (samples == null || Double.isNaN(stdDev)) {
                    return Double.NaN;
                }
                return mean + k * stdDev;
            }

            /**
             * Windowed upper IQR fence: Q3 + 1.5*IQR. NaN before two samples. Sorts
             * the snapshot in place on first call; subsequent calls reuse the sort.
             */
            double iqrUpperFence() {
                if (samples == null) {
                    return Double.NaN;
                }
                if (!sorted) {
                    Arrays.sort(samples);
                    sorted = true;
                }
                double q1 = percentile(samples, 0.25);
                double q3 = percentile(samples, 0.75);
                double iqr = q3 - q1;
                return q3 + 1.5 * iqr;
            }
        }
    }

    /** Test accessor: rolling-window occupancy for the CPU signal. */
    synchronized int cpuWindowOccupancy() {
        return cpuEstimator.windowOccupancy();
    }

    /** Test accessor: rolling-window capacity for the CPU signal. */
    synchronized int cpuWindowCapacity() {
        return cpuEstimator.windowCapacity();
    }

    /** Test accessor: rolling-window occupancy for the alloc signal. */
    synchronized int allocWindowOccupancy() {
        return allocEstimator.windowOccupancy();
    }
}
