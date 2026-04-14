package io.argus.server.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes a JVM health score from available analyzer data.
 *
 * <p>Produces a 0-100 score and a list of findings with recommendations.
 */
public final class HealthScoreComputer {

    private HealthScoreComputer() {}

    /**
     * Computes a health report from the given analyzers.
     *
     * @param gc   the GC analyzer (required)
     * @param cpu  the CPU analyzer (may be null)
     * @param meta the metaspace analyzer (may be null)
     * @return the health report
     */
    public static HealthReport compute(GCAnalyzer gc, CPUAnalyzer cpu, MetaspaceAnalyzer meta) {
        List<Finding> findings = new ArrayList<>();
        int score = 100;

        // --- GC overhead ---
        var gcAnalysis = gc.getAnalysis();
        double gcOverhead = gcAnalysis.gcOverheadPercent();
        if (gcOverhead > 10.0) {
            findings.add(new Finding("CRITICAL",
                    String.format("GC overhead %.1f%% (threshold: 10%%)", gcOverhead),
                    "-Xmx<size> to increase heap"));
            score -= 30;
        } else if (gcOverhead > 5.0) {
            findings.add(new Finding("WARNING",
                    String.format("GC overhead %.1f%% (threshold: 5%%)", gcOverhead),
                    "Monitor GC frequency"));
            score -= 15;
        }

        // --- Heap pressure ---
        long heapUsed = gcAnalysis.currentHeapUsed();
        long heapCommitted = gcAnalysis.currentHeapCommitted();
        if (heapCommitted > 0) {
            double heapPct = (double) heapUsed / heapCommitted;
            if (heapPct > 0.90) {
                findings.add(new Finding("CRITICAL",
                        String.format("Heap usage at %.0f%% of committed", heapPct * 100),
                        "-Xmx<size> to increase max heap"));
                score -= 20;
            } else if (heapPct > 0.75) {
                findings.add(new Finding("WARNING",
                        String.format("Heap usage at %.0f%% of committed", heapPct * 100),
                        "Review object retention"));
                score -= 10;
            }
        }

        // --- Memory leak ---
        if (gcAnalysis.leakSuspected()) {
            double confidence = gcAnalysis.leakConfidencePercent();
            findings.add(new Finding("CRITICAL",
                    String.format("Memory leak suspected (confidence %.0f%%)", confidence),
                    "-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap.hprof"));
            score -= 25;
        }

        // --- CPU ---
        if (cpu != null) {
            var cpuAnalysis = cpu.getAnalysis();
            double jvmCpuPct = cpuAnalysis.currentJvmPercent();
            if (jvmCpuPct > 90.0) {
                findings.add(new Finding("CRITICAL",
                        String.format("JVM CPU at %.1f%%", jvmCpuPct),
                        "Profile with async-profiler or JFR"));
                score -= 20;
            } else if (jvmCpuPct > 70.0) {
                findings.add(new Finding("WARNING",
                        String.format("JVM CPU at %.1f%%", jvmCpuPct),
                        "Review hot methods in flame graph"));
                score -= 10;
            }
        }

        // --- Metaspace ---
        if (meta != null) {
            var metaAnalysis = meta.getAnalysis();
            long metaUsed = metaAnalysis.currentUsed();
            long metaCommitted = metaAnalysis.currentCommitted();
            if (metaCommitted > 0) {
                double metaPct = (double) metaUsed / metaCommitted;
                if (metaPct > 0.90) {
                    findings.add(new Finding("WARNING",
                            String.format("Metaspace at %.0f%% of committed", metaPct * 100),
                            "-XX:MaxMetaspaceSize=<size>"));
                    score -= 10;
                }
            }
            double growthRateMBPerMin = metaAnalysis.growthRatePerMin() / (1024.0 * 1024.0);
            if (growthRateMBPerMin > 5.0) {
                findings.add(new Finding("WARNING",
                        String.format("Metaspace growing at %.1f MB/min", growthRateMBPerMin),
                        "Check for classloader leaks"));
                score -= 10;
            }
        }

        // Clamp score
        score = Math.max(0, score);

        // Add INFO finding when score is perfect
        if (findings.isEmpty()) {
            findings.add(new Finding("OK", "No issues detected", ""));
        }

        return new HealthReport(score, findings);
    }

    /** Immutable health report. */
    public record HealthReport(int score, List<Finding> findings) {}

    /** A single diagnostic finding. */
    public record Finding(String severity, String message, String recommendation) {}
}
