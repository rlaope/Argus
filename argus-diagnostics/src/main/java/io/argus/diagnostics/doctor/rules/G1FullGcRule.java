package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.List;

/**
 * Detects Full GC pauses under G1.
 *
 * <p>G1 is designed to never trigger a Full GC during steady-state operation.
 * Mixed cycles and concurrent marking should reclaim old-gen space before
 * pressure forces a Full GC. A non-zero {@code G1 Old Generation} collector
 * count therefore indicates one of: extreme allocation burst, evacuation
 * failure, sustained humongous pressure, or memory leak.
 *
 * <p>Fires CRITICAL when the {@code G1 Old Generation} collector has any
 * recorded GC count.
 * Skipped entirely for non-G1 collectors.
 */
public final class G1FullGcRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        if (!s.gcAlgorithm().contains("G1")) return List.of();

        for (JvmSnapshot.GcInfo info : s.collectors()) {
            if (!info.name().contains("G1 Old Generation")) continue;
            if (info.count() <= 0) continue;

            var b = Finding.builder(Severity.CRITICAL, "GC",
                    "G1 Full GC observed (" + info.count() + " occurrence"
                            + (info.count() == 1 ? "" : "s") + ")");

            b.detail("G1 Old Generation collector recorded " + info.count()
                    + " GC events totalling " + info.timeMs()
                    + "ms — G1 should not Full GC in steady state");

            b.recommend("Inspect with argus heap / argus diff to find the allocator that overwhelmed G1; "
                    + "raise -Xmx, lower -XX:InitiatingHeapOccupancyPercent (default 45), "
                    + "or run argus g1 <PID> for JFR-level evacuation detail.");

            b.flag("-Xmx<raise>");
            b.flag("-XX:InitiatingHeapOccupancyPercent=<lower>");

            return List.of(b.build());
        }
        return List.of();
    }
}
