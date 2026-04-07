package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes GC algorithm choice and suggests improvements based on workload signals.
 */
public final class GcAlgorithmRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        String gc = s.gcAlgorithm().toLowerCase();
        List<Finding> findings = new ArrayList<>();

        // Serial GC on large heap
        if (gc.contains("serial") && s.heapMax() > 512L * 1024 * 1024) {
            findings.add(Finding.builder(Severity.WARNING, "GC",
                            "Serial GC on " + (s.heapMax() / (1024 * 1024)) + "MB heap")
                    .detail("Serial GC stops all threads during collection. "
                            + "On heaps > 512MB this causes long pauses.")
                    .recommend("Switch to G1GC (default since Java 9) for balanced throughput/latency")
                    .flag("-XX:+UseG1GC")
                    .build());
        }

        // Parallel GC with latency-sensitive signals
        if (gc.contains("parallel") && s.threadCount() > 200) {
            findings.add(Finding.builder(Severity.INFO, "GC",
                            "Parallel GC with " + s.threadCount() + " threads")
                    .detail("Parallel GC optimizes for throughput but may cause long pauses. "
                            + "If this is a web/API server, consider G1GC or ZGC for lower latency.")
                    .recommend("For latency-sensitive workloads: -XX:+UseG1GC or -XX:+UseZGC")
                    .flag("-XX:+UseG1GC")
                    .build());
        }

        // Check for Full GC events (any GC algorithm)
        for (var collector : s.collectors()) {
            String name = collector.name().toLowerCase();
            if ((name.contains("full") || name.contains("old")) && collector.count() > 0) {
                long avgMs = collector.count() > 0 ? collector.timeMs() / collector.count() : 0;
                if (avgMs > 500) {
                    findings.add(Finding.builder(Severity.WARNING, "GC",
                                    String.format("Full GC detected: %d events, avg %dms",
                                            collector.count(), avgMs))
                            .detail("Full GC pauses stop the entire application. "
                                    + "Frequent full GCs indicate old gen exhaustion or promotion failure.")
                            .recommend("Increase -Xmx to reduce full GC frequency")
                            .recommend("Monitor heap trend: argus watch <pid>")
                            .build());
                }
            }
        }

        return findings;
    }
}
