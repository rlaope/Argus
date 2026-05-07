package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes GC algorithm choice and suggests improvements based on workload signals.
 */
public final class GcAlgorithmRule implements HealthRule {

    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)");

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

        // Suggest Generational ZGC on JDK 21-23 when plain ZGC is in use
        if ("ZGC".equals(s.gcAlgorithm())) {
            int major = parseJavaMajorVersion(s.vmVersion());
            if (major >= 21 && major < 24) {
                findings.add(Finding.builder(Severity.INFO, "GC",
                                "Consider Generational ZGC")
                        .detail("JDK 21-23 supports Generational ZGC behind a flag. "
                                + "It improves throughput and reduces allocation stalls "
                                + "under allocation-heavy workloads.")
                        .recommend("-XX:+ZGenerational  (note: becomes default in JDK 24)")
                        .flag("-XX:+ZGenerational")
                        .build());
            }
            // JDK 24+: generational is default — emit nothing
        }

        return findings;
    }

    /**
     * Parse the JVM major version from a version string such as "21.0.2+13" or
     * "17.0.8" or "OpenJDK ... build 11.0.1+13". Returns -1 on parse failure.
     */
    static int parseJavaMajorVersion(String vmVersion) {
        if (vmVersion == null || vmVersion.isBlank()) return -1;
        try {
            // Find first run of digits possibly preceded by non-digit context
            // e.g. "build 21.0.2+13" or "21.0.2" or "11.0.1"
            Matcher m = JAVA_VERSION_PATTERN.matcher(vmVersion);
            if (m.find()) {
                int first = Integer.parseInt(m.group(1));
                // JDK 1.x era: "1.8.0_292" → major is 8
                if (first == 1) {
                    return Integer.parseInt(m.group(2));
                }
                return first;
            }
            // Fallback: bare integer string
            return Integer.parseInt(vmVersion.trim().split("[^\\d]")[0]);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
