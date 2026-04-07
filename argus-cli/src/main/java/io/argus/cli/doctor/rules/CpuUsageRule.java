package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.List;

/**
 * Detects high CPU usage at the JVM process level.
 */
public final class CpuUsageRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        double cpuPct = s.processCpuLoad() * 100;
        if (cpuPct < 0 || cpuPct < 70) return List.of();

        Severity sev = cpuPct >= 90 ? Severity.CRITICAL : Severity.WARNING;
        return List.of(Finding.builder(sev, "CPU",
                        String.format("JVM CPU usage %.1f%% (system: %.1f%%, %d processors)",
                                cpuPct, s.systemCpuLoad() * 100, s.availableProcessors()))
                .detail(cpuPct >= 90
                        ? "JVM is consuming nearly all available CPU. This may cause request timeouts and thread starvation."
                        : "JVM CPU usage is elevated. Monitor for sustained high usage.")
                .recommend("Run: argus flame <pid> to identify hot code paths")
                .recommend("Run: argus profile <pid> --type cpu --duration 10 for CPU profiling")
                .build());
    }
}
