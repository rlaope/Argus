package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects when ZGC concurrent cycle duration approaches the inter-cycle interval,
 * indicating that GC cannot keep up with the allocation rate.
 *
 * <p>ZGC is designed to run concurrently with the application. When average cycle
 * duration exceeds ~80% of the average inter-cycle interval, cycles start overlapping.
 * Overlapping cycles cause GC to fall behind, leading to allocation stalls and
 * eventually OutOfMemoryError.
 *
 * <p>Fires CRITICAL when {@code avgDurationMs > intervalMs * 0.8} for any ZGC
 * collector with more than 5 cycles (for a stable signal).
 * Skipped entirely for non-ZGC collectors.
 */
public final class ZgcCycleOverlapRule implements HealthRule {

    /** Minimum cycle count for a stable signal. */
    static final long MIN_CYCLE_COUNT = 5L;

    /** Overlap ratio threshold: avg duration / avg interval > this triggers CRITICAL. */
    static final double OVERLAP_THRESHOLD = 0.8;

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        if (!s.gcAlgorithm().contains("ZGC")) return List.of();
        if (s.uptimeMs() <= 0) return List.of();

        List<Finding> findings = new ArrayList<>();

        for (JvmSnapshot.GcInfo info : s.collectors()) {
            if (!info.name().contains("ZGC")) continue;
            if (info.count() <= MIN_CYCLE_COUNT) continue;

            long avgDurationMs = info.timeMs() / info.count();
            long intervalMs = s.uptimeMs() / info.count();

            if (intervalMs <= 0) continue;

            if (avgDurationMs > intervalMs * OVERLAP_THRESHOLD) {
                var b = Finding.builder(Severity.CRITICAL, "GC",
                        "ZGC cycle duration approaches inter-cycle interval (overlap risk)");

                b.detail(String.format(
                        "avg cycle %dms vs avg interval %dms over %d cycles "
                                + "— GC cannot keep up with allocation rate",
                        avgDurationMs, intervalMs, info.count()));

                b.recommend("Reduce allocation rate (profile with argus profile <PID> --event=alloc), "
                        + "or raise -Xmx, or increase -XX:ConcGCThreads.");

                findings.add(b.build());
            }
        }

        return List.copyOf(findings);
    }
}
