package io.argus.cli.doctor;

import io.argus.cli.doctor.rules.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The doctor engine: collects JVM snapshot, runs all health rules,
 * and produces a sorted, deduplicated list of findings.
 *
 * <p>Rules are executed in isolation against the same snapshot for consistency.
 * Findings are sorted by severity (CRITICAL first) then by category.
 */
public final class DoctorEngine {

    private static final List<HealthRule> RULES = List.of(
            new GcOverheadRule(),
            new HeapPressureRule(),
            new MetaspaceRule(),
            new DirectBufferRule(),
            new CpuUsageRule(),
            new ThreadContentionRule(),
            new FinalizerQueueRule(),
            new GcAlgorithmRule()
    );

    /**
     * Run all health checks against the given snapshot.
     *
     * @param snapshot JVM metrics snapshot
     * @return sorted list of findings (CRITICAL first)
     */
    public static List<Finding> diagnose(JvmSnapshot snapshot) {
        List<Finding> allFindings = new ArrayList<>();

        for (HealthRule rule : RULES) {
            try {
                allFindings.addAll(rule.evaluate(snapshot));
            } catch (Exception e) {
                // A failing rule should not break the entire diagnosis
                allFindings.add(Finding.builder(Severity.INFO, "Internal",
                                "Rule failed: " + rule.getClass().getSimpleName())
                        .detail(e.getMessage())
                        .build());
            }
        }

        // Sort: CRITICAL → WARNING → INFO, then by category
        allFindings.sort(Comparator
                .comparing(Finding::severity)
                .thenComparing(Finding::category));

        return List.copyOf(allFindings);
    }

    /**
     * Collect all unique suggested JVM flags from findings.
     */
    public static List<String> collectSuggestedFlags(List<Finding> findings) {
        return findings.stream()
                .flatMap(f -> f.suggestedFlags().stream())
                .distinct()
                .toList();
    }

    /**
     * Determine overall health status from findings.
     * Returns exit code: 0=healthy, 1=warnings only, 2=has critical.
     */
    public static int exitCode(List<Finding> findings) {
        boolean hasCritical = findings.stream().anyMatch(f -> f.severity() == Severity.CRITICAL);
        boolean hasWarning = findings.stream().anyMatch(f -> f.severity() == Severity.WARNING);
        if (hasCritical) return 2;
        if (hasWarning) return 1;
        return 0;
    }
}
