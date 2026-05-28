package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.List;

/**
 * Detects G1 evacuation failure ("To-space exhausted") in the GC log.
 *
 * <p>G1's evacuation phase copies live objects from the collection set to
 * survivor / old regions. When the JVM can't reserve enough free regions to
 * complete the evacuation, it falls back to in-place compaction — extremely
 * expensive and a strong signal of imminent OOM.
 *
 * <p>Fires CRITICAL when {@link io.argus.diagnostics.gclog.G1Stats#evacuationFailures()}
 * is &gt; 0. Requires the target JVM to have {@code -Xlog:gc:file=<path>}
 * configured so {@link JvmSnapshotCollector} can parse the log; otherwise
 * the rule is silent.
 * Skipped entirely for non-G1 collectors.
 */
public final class G1EvacuationFailureRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        if (!s.gcAlgorithm().contains("G1")) return List.of();

        int failures = s.g1Stats().evacuationFailures();
        if (failures <= 0) return List.of();

        var b = Finding.builder(Severity.CRITICAL, "GC",
                "G1 evacuation failure ('To-space exhausted') observed "
                        + failures + " time" + (failures == 1 ? "" : "s"));

        b.detail("Evacuation fell back to in-place compaction — extremely expensive and "
                + "a strong signal of imminent OOM. Heap is too small or allocation rate "
                + "exceeded G1's evacuation budget.");

        b.recommend("Raise -Xmx, raise -XX:G1ReservePercent toward 15 (default 10) for "
                + "evacuation headroom, and profile allocation rate with argus profile "
                + "<PID> --event=alloc.");

        b.flag("-XX:G1ReservePercent=15");
        b.flag("-Xmx<raise>");

        return List.of(b.build());
    }
}
