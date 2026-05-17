package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.List;

/**
 * Detects sustained high young-GC frequency that aggregate-overhead checks miss.
 *
 * <p>{@link GcOverheadRule} only fires above 5% cumulative overhead. A workload
 * doing thousands of fast young collections (e.g. allocation churn at <5%
 * overhead but 3000+ GCs/min) is still pathological — the JVM is constantly
 * pausing, throughput is degraded, and tail latency spikes — but the aggregate
 * percentage stays low because each pause is short.
 *
 * <p>This rule looks at <em>frequency</em> rather than time-share:
 * <ul>
 *   <li>WARNING: young GCs per minute &gt; 200 with non-trivial average pause
 *   <li>CRITICAL: young GCs per minute &gt; 500 (sustained churn)
 * </ul>
 *
 * <p>Skipped during JVM warmup (uptime &lt; 60s) where rates are unreliable.
 */
public final class GcPressureRule implements HealthRule {

    /** Young GCs per minute that triggers a warning. */
    static final double WARN_PER_MIN = 200.0;

    /** Young GCs per minute that triggers critical. */
    static final double CRITICAL_PER_MIN = 500.0;

    /** Minimum average young pause to consider; filters JIT-only blips. */
    static final double MIN_AVG_PAUSE_MS = 0.5;

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        if (s.uptimeMs() < 60_000) return List.of();
        if (s.collectors().isEmpty()) return List.of();

        long youngCount = 0L;
        long youngTimeMs = 0L;
        for (JvmSnapshot.GcInfo gc : s.collectors()) {
            String n = gc.name().toLowerCase();
            // Match Young / Eden / Copy collectors. Old/Full are excluded —
            // they are covered by GcAlgorithmRule.
            if (n.contains("young") || n.contains("eden") || n.contains("copy")
                    || n.contains("scavenge") || n.contains("parnew")) {
                youngCount += gc.count();
                youngTimeMs += gc.timeMs();
            }
        }

        if (youngCount <= 0) return List.of();

        double minutes = s.uptimeMs() / 60_000.0;
        double perMinute = youngCount / minutes;
        double avgPauseMs = (double) youngTimeMs / youngCount;

        if (perMinute < WARN_PER_MIN || avgPauseMs < MIN_AVG_PAUSE_MS) return List.of();

        boolean critical = perMinute >= CRITICAL_PER_MIN;
        Severity sev = critical ? Severity.CRITICAL : Severity.WARNING;

        var b = Finding.builder(sev, "GC",
                String.format("High GC pressure: %d young GCs in %ds (%.0f/min)",
                        youngCount, s.uptimeMs() / 1000, perMinute));

        b.detail(String.format(
                "Young-generation collector is firing every %.0fms on average "
                        + "(avg pause %.1fms). Cumulative overhead is %.1f%% but "
                        + "the constant churn degrades throughput and inflates p99 latency.",
                60_000.0 / Math.max(perMinute, 1), avgPauseMs, s.gcOverheadPercent()));

        if (s.heapUsagePercent() < 60) {
            b.recommend("Profile allocation hotspots: argus profile <pid> --type alloc");
            b.recommend("Reduce short-lived object allocation in the hot path");
        } else {
            b.recommend("Increase -Xmx so young gen can absorb more allocation between GCs");
        }
        b.recommend("Enlarge young gen explicitly: -Xmn<size> or -XX:NewRatio=2");
        b.flag("-Xmn" + suggestNewSize(s.heapMax()));

        if (critical) {
            b.recommend("Consider ZGC for sub-ms pauses if low latency is required: -XX:+UseZGC");
        }

        return List.of(b.build());
    }

    private static String suggestNewSize(long heapMax) {
        // Suggest young = heap/3 as a starting point.
        long mb = (heapMax / 3) / (1024 * 1024);
        if (mb <= 0) return "256m";
        return mb >= 1024 ? (mb / 1024) + "g" : mb + "m";
    }
}
