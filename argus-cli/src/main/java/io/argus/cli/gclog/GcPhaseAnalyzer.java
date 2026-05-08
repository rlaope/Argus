package io.argus.cli.gclog;

import java.util.*;

/**
 * Aggregates GC phase events across multiple GC cycles and produces per-phase statistics.
 * Groups by GC ID first, then aggregates across all GCs to produce avg/max duration and
 * percentage of total pause time per phase.
 */
public final class GcPhaseAnalyzer {

    public static PhaseAnalysis analyze(List<GcPhaseEvent> phases) {
        if (phases.isEmpty()) {
            return new PhaseAnalysis(List.of(), 0);
        }

        // Aggregate per-phase: sum, max, count across all GC IDs
        Map<String, double[]> accumulator = new LinkedHashMap<>();
        // value: [sum, max, count]
        for (GcPhaseEvent e : phases) {
            double[] acc = accumulator.computeIfAbsent(e.phase(), k -> new double[3]);
            acc[0] += e.durationMs();
            if (e.durationMs() > acc[1]) acc[1] = e.durationMs();
            acc[2] += 1;
        }

        // Count distinct GC IDs to report "avg of N GCs"
        Set<Integer> gcIds = new HashSet<>();
        for (GcPhaseEvent e : phases) gcIds.add(e.gcId());
        int gcCount = gcIds.size();

        // Total average pause across all phases for a single GC cycle
        // = sum of per-phase averages
        double totalAvgMs = 0;
        for (double[] acc : accumulator.values()) {
            totalAvgMs += acc[0] / acc[2];  // avg per GC for this phase
        }

        List<PhaseStat> stats = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : accumulator.entrySet()) {
            double[] acc = entry.getValue();
            double avg = acc[0] / acc[2];
            double max = acc[1];
            double pct = totalAvgMs > 0 ? avg / totalAvgMs * 100.0 : 0.0;
            stats.add(new PhaseStat(entry.getKey(), avg, max, pct));
        }

        return new PhaseAnalysis(Collections.unmodifiableList(stats), gcCount);
    }

    public static final class PhaseAnalysis {
        private final List<PhaseStat> phases;
        private final int gcCount;

        public PhaseAnalysis(List<PhaseStat> phases, int gcCount) {
            this.phases = phases;
            this.gcCount = gcCount;
        }

        public List<PhaseStat> phases() { return phases; }
        public int gcCount() { return gcCount; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PhaseAnalysis)) return false;
            PhaseAnalysis that = (PhaseAnalysis) o;
            return gcCount == that.gcCount
                    && java.util.Objects.equals(phases, that.phases);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(phases, gcCount);
        }

        @Override
        public String toString() {
            return "PhaseAnalysis[phases=" + phases + ", gcCount=" + gcCount + "]";
        }
    }

    public static final class PhaseStat {
        private final String phase;
        private final double avgMs;
        private final double maxMs;
        private final double percentOfTotal;

        public PhaseStat(String phase, double avgMs, double maxMs, double percentOfTotal) {
            this.phase = phase;
            this.avgMs = avgMs;
            this.maxMs = maxMs;
            this.percentOfTotal = percentOfTotal;
        }

        public String phase() { return phase; }
        public double avgMs() { return avgMs; }
        public double maxMs() { return maxMs; }
        public double percentOfTotal() { return percentOfTotal; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PhaseStat)) return false;
            PhaseStat that = (PhaseStat) o;
            return Double.compare(that.avgMs, avgMs) == 0
                    && Double.compare(that.maxMs, maxMs) == 0
                    && Double.compare(that.percentOfTotal, percentOfTotal) == 0
                    && java.util.Objects.equals(phase, that.phase);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(phase, avgMs, maxMs, percentOfTotal);
        }

        @Override
        public String toString() {
            return "PhaseStat[phase=" + phase + ", avgMs=" + avgMs + ", maxMs=" + maxMs
                    + ", percentOfTotal=" + percentOfTotal + "]";
        }
    }
}
