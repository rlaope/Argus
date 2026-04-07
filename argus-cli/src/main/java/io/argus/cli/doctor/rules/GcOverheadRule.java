package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.List;

/**
 * Detects excessive GC overhead — the single most common cause of JVM performance issues.
 *
 * <p>GC overhead is the percentage of wall-clock time spent in GC pauses.
 * Healthy: < 5%. Warning: 5-15%. Critical: > 15%.
 *
 * <p>Cross-correlates with heap usage to provide root cause:
 * - High overhead + high heap → heap too small
 * - High overhead + low heap → allocation rate too high (churn)
 */
public final class GcOverheadRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        double overhead = s.gcOverheadPercent();

        if (overhead < 5) return List.of();

        boolean critical = overhead >= 15;
        Severity sev = critical ? Severity.CRITICAL : Severity.WARNING;
        double heapPct = s.heapUsagePercent();

        var b = Finding.builder(sev, "GC", String.format("GC overhead %.1f%% (%.0fms / %ds uptime)",
                overhead, (double) s.totalGcTimeMs(), s.uptimeMs() / 1000));

        if (heapPct > 80) {
            b.detail("High GC overhead combined with high heap usage (" + String.format("%.0f%%", heapPct)
                    + ") indicates the heap is too small for the workload.");
            b.recommend("Increase -Xmx to give GC more headroom");
            b.flag("-Xmx" + suggestHeapSize(s.heapMax()));
        } else {
            b.detail("GC overhead is high but heap usage is moderate (" + String.format("%.0f%%", heapPct)
                    + "). This suggests high object allocation churn — many short-lived objects.");
            b.recommend("Profile allocation hotspots: argus profile <pid> --type alloc");
            b.recommend("Consider object pooling for frequently allocated objects");
        }

        if (overhead >= 15) {
            b.recommend("If using G1GC, tune -XX:MaxGCPauseMillis for your latency target");
            b.flag("-XX:MaxGCPauseMillis=200");
        }

        return List.of(b.build());
    }

    private static String suggestHeapSize(long currentMax) {
        long suggested = (long) (currentMax * 1.5);
        long mb = suggested / (1024 * 1024);
        return mb >= 1024 ? (mb / 1024) + "g" : mb + "m";
    }
}
