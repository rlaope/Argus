package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.List;

/**
 * Detects G1 mixed-GC starvation: concurrent mark cycles complete but no
 * Mixed pause follows within the observed log window.
 *
 * <p>Under normal G1 operation, a concurrent mark cycle is followed by 8
 * (or {@code -XX:G1MixedGCCountTarget}) Mixed pauses that incrementally clean
 * up old regions. If concurrent cycles fire but Mixed pauses don't, old gen
 * keeps growing until a Full GC is forced — the worst possible outcome under G1.
 *
 * <p>Fires WARNING when {@link io.argus.diagnostics.gclog.G1Stats#mixedStarvationSuspected()}
 * returns {@code true} AND at least 2 concurrent cycles have completed (to
 * avoid false positives on very short log windows).
 * Skipped entirely for non-G1 collectors.
 */
public final class G1MixedStarvationRule implements HealthRule {

    static final int MIN_CONCURRENT_CYCLES = 2;

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        if (!s.gcAlgorithm().contains("G1")) return List.of();

        var stats = s.g1Stats();
        if (!stats.mixedStarvationSuspected()) return List.of();
        if (stats.concurrentCycleMarkers() < MIN_CONCURRENT_CYCLES) return List.of();

        var b = Finding.builder(Severity.WARNING, "GC",
                "G1 mixed-GC starvation suspected (" + stats.concurrentCycleMarkers()
                        + " concurrent cycles, 0 Mixed pauses)");

        b.detail("Concurrent marking is finishing but G1 is not running Mixed pauses to "
                + "reclaim old regions. Old gen will keep growing until a Full GC is forced.");

        b.recommend("Lower -XX:InitiatingHeapOccupancyPercent (try 35–40) so concurrent "
                + "cycles complete earlier, and verify -XX:G1MixedGCCountTarget (default 8) "
                + "and -XX:G1OldCSetRegionThresholdPercent (default 10).");

        b.flag("-XX:InitiatingHeapOccupancyPercent=40");
        b.flag("-XX:G1MixedGCCountTarget=8");

        return List.of(b.build());
    }
}
