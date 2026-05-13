package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.List;

/**
 * Detects JIT code-cache pressure. When the cache approaches its reserved
 * size, HotSpot starts deoptimizing nmethods to free room, which manifests
 * as sudden CPU spikes and lost throughput. Without this rule the symptom
 * is easy to misdiagnose as application-level CPU contention.
 */
public final class CodeCacheRule implements HealthRule {

    static final int WARN_PERCENT = 80;
    static final int CRITICAL_PERCENT = 95;

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        long sizeKb = s.codeCacheSizeKb();
        long usedKb = s.codeCacheUsedKb();
        if (sizeKb <= 0) return List.of();

        double pct = (double) usedKb / sizeKb * 100;
        if (pct < WARN_PERCENT) return List.of();

        Severity sev = pct >= CRITICAL_PERCENT ? Severity.CRITICAL : Severity.WARNING;
        long suggestedMb = Math.max(sizeKb / 1024 * 2, 256);

        var b = Finding.builder(sev, "Memory",
                        String.format("CodeCache pressure: %.0f%% used (%dMB / %dMB)",
                                pct, usedKb / 1024, sizeKb / 1024))
                .detail("Code cache near exhaustion causes HotSpot to deoptimize compiled "
                        + "methods, producing CPU spikes that look like application hot loops. "
                        + "Increase the reserved size; ensure flushing is on so the JVM can "
                        + "evict cold nmethods.")
                .recommend("Run: argus compiler <pid> to track used / max / free over time")
                .flag("-XX:ReservedCodeCacheSize=" + suggestedMb + "m");

        boolean flushingDisabled = s.vmFlags().stream()
                .anyMatch(f -> f.contains("-UseCodeCacheFlushing"));
        if (flushingDisabled) {
            b.flag("-XX:+UseCodeCacheFlushing");
        }

        return List.of(b.build());
    }
}
