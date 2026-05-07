package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.List;

/**
 * Detects individual STW pause outliers that exceed a configurable threshold.
 *
 * <p>GC overhead % can mask a single long pause lost in 24h of aggregate time.
 * This rule fires explicitly when any recorded pause exceeds the warning threshold.
 *
 * <p>Defaults: WARNING >= 500ms, CRITICAL >= 2000ms.
 * Thresholds are tunable via the constructor (e.g., from --pause-threshold-ms).
 *
 * <p>Data source:
 * <ul>
 *   <li><b>Local</b>: {@code com.sun.management.GarbageCollectorMXBean.getLastGcInfo().getDuration()}
 *       — exact duration of the most recent pause per collector; we take the max.
 *   <li><b>Remote</b>: single-pause data is not available via jcmd/jstat. We use the
 *       largest per-collector average (totalTime/count) as a heuristic. This is
 *       explicitly noted in the finding detail.
 * </ul>
 */
public final class MaxPauseRule implements HealthRule {

    /** Default warning threshold: 500ms. */
    public static final long DEFAULT_WARN_MS = 500L;

    /** Default critical threshold: 2000ms. */
    public static final long DEFAULT_CRITICAL_MS = 2000L;

    private final long warnMs;
    private final long criticalMs;

    /** Construct with default thresholds. */
    public MaxPauseRule() {
        this(DEFAULT_WARN_MS, DEFAULT_CRITICAL_MS);
    }

    /**
     * Construct with custom thresholds.
     *
     * @param warnMs     warning threshold in milliseconds
     * @param criticalMs critical threshold in milliseconds
     */
    public MaxPauseRule(long warnMs, long criticalMs) {
        this.warnMs = warnMs;
        this.criticalMs = criticalMs;
    }

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        long pauseMs = s.maxRecentPauseMs();

        // 0 means no GC has run yet or data was unavailable — skip silently.
        if (pauseMs <= 0) return List.of();

        if (pauseMs < warnMs) return List.of();

        boolean critical = pauseMs >= criticalMs;
        Severity sev = critical ? Severity.CRITICAL : Severity.WARNING;

        var b = Finding.builder(sev, "GC",
                String.format("Max STW pause %dms exceeds %s threshold (%dms)",
                        pauseMs, critical ? "critical" : "warning", critical ? criticalMs : warnMs));

        if (s.collectors().isEmpty()) {
            // Remote heuristic path: single-pause data is unavailable
            b.detail(String.format(
                    "Pause of %dms estimated from per-collector average (totalTime/count). "
                    + "Single-pause data is not available via jcmd/jstat on remote JVMs — "
                    + "the actual worst-case pause may differ. "
                    + "For exact pause histograms enable GC logging: -Xlog:gc*.",
                    pauseMs));
        } else {
            b.detail(String.format(
                    "A single stop-the-world pause of %dms was recorded. "
                    + "Individual pauses of this length cause visible application latency spikes "
                    + "regardless of aggregate GC overhead percentage.",
                    pauseMs));
        }

        b.recommend("Switch to ZGC for sub-ms pauses on large heaps (Java 15+): -XX:+UseZGC");
        b.recommend("Reduce -Xmx so the young gen stays small and minor GCs stay short");
        b.recommend("Cap G1GC pause target: -XX:MaxGCPauseMillis=200");
        b.flag("-XX:MaxGCPauseMillis=200");

        if (critical) {
            b.recommend("Enable GC logging to capture pause histograms: -Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=20m");
        }

        return List.of(b.build());
    }
}
