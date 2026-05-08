package io.argus.cli.cluster;

import java.util.List;

/**
 * Aggregates health metrics across multiple JVM instances.
 */
public final class ClusterHealthAggregator {

    /** Known Prometheus metric name candidates for each dimension. */
    private static final String[] HEAP_METRICS = {
        "argus_heap_used_percent",
        "jvm_memory_used_bytes",
        "heap_used_percent"
    };
    private static final String[] GC_METRICS = {
        "argus_gc_overhead_percent",
        "jvm_gc_overhead_percent",
        "gc_overhead_percent"
    };
    private static final String[] CPU_METRICS = {
        "argus_cpu_process_percent",
        "jvm_cpu_usage",
        "process_cpu_usage"
    };
    private static final String[] VT_METRICS = {
        "argus_virtual_threads_active",
        "jvm_virtual_threads_active",
        "virtual_threads_active"
    };
    private static final String[] LEAK_METRICS = {
        "argus_memory_leak_suspected",
        "memory_leak_suspected"
    };

    private ClusterHealthAggregator() {}


    /**
     * Extracts per-instance metrics from the raw Prometheus map.
     */
    public static InstanceMetrics extract(String target, java.util.Map<String, Double> raw) {
        double heap = pick(raw, HEAP_METRICS, -1.0);
        double gc   = pick(raw, GC_METRICS,   -1.0);
        double cpu  = pick(raw, CPU_METRICS,  -1.0);
        double vt   = pick(raw, VT_METRICS,   0.0);
        double leak = pick(raw, LEAK_METRICS, 0.0);
        return new InstanceMetrics(target, heap, gc, cpu, leak > 0.5, (long) vt, true);
    }

    /**
     * Computes aggregate statistics from a list of reachable instance metrics.
     */
    public static AggregateStats aggregate(List<InstanceMetrics> instances) {
        List<InstanceMetrics> up = instances.stream().filter(InstanceMetrics::reachable)
                .collect(java.util.stream.Collectors.toList());
        if (up.isEmpty()) {
            return new AggregateStats(-1,-1,-1,-1,-1,-1,-1,-1,-1,0,0,null,"no reachable instances");
        }

        double heapMin = Double.MAX_VALUE, heapMax = -1, heapSum = 0;
        double gcMin   = Double.MAX_VALUE, gcMax   = -1, gcSum   = 0;
        double cpuMin  = Double.MAX_VALUE, cpuMax  = -1, cpuSum  = 0;
        long vtTotal = 0;
        int leakCount = 0;
        int heapCnt = 0, gcCnt = 0, cpuCnt = 0;

        for (InstanceMetrics m : up) {
            if (m.heapPercent() >= 0) {
                heapMin = Math.min(heapMin, m.heapPercent());
                heapMax = Math.max(heapMax, m.heapPercent());
                heapSum += m.heapPercent();
                heapCnt++;
            }
            if (m.gcOverhead() >= 0) {
                gcMin = Math.min(gcMin, m.gcOverhead());
                gcMax = Math.max(gcMax, m.gcOverhead());
                gcSum += m.gcOverhead();
                gcCnt++;
            }
            if (m.cpuPercent() >= 0) {
                cpuMin = Math.min(cpuMin, m.cpuPercent());
                cpuMax = Math.max(cpuMax, m.cpuPercent());
                cpuSum += m.cpuPercent();
                cpuCnt++;
            }
            vtTotal += m.activeVThreads();
            if (m.leakSuspected()) leakCount++;
        }

        // Worst instance: highest GC overhead, then heap
        InstanceMetrics worst = up.stream()
            .filter(m -> m.gcOverhead() >= 0)
            .max(java.util.Comparator.comparingDouble(InstanceMetrics::gcOverhead))
            .or(() -> up.stream()
                .filter(m -> m.heapPercent() >= 0)
                .max(java.util.Comparator.comparingDouble(InstanceMetrics::heapPercent)))
            .orElse(up.get(0));

        StringBuilder reason = new StringBuilder();
        if (worst.gcOverhead() >= 0) {
            reason.append(String.format("GC overhead %.1f%%", worst.gcOverhead()));
        }
        if (worst.leakSuspected()) {
            if (reason.length() > 0) reason.append(", ");
            reason.append("memory leak suspected");
        }

        return new AggregateStats(
            heapCnt > 0 ? heapMin : -1, heapCnt > 0 ? heapMax : -1, heapCnt > 0 ? heapSum / heapCnt : -1,
            gcCnt   > 0 ? gcMin   : -1, gcCnt   > 0 ? gcMax   : -1, gcCnt   > 0 ? gcSum   / gcCnt   : -1,
            cpuCnt  > 0 ? cpuMin  : -1, cpuCnt  > 0 ? cpuMax  : -1, cpuCnt  > 0 ? cpuSum  / cpuCnt  : -1,
            vtTotal, leakCount, worst.target(), reason.toString()
        );
    }

    private static double pick(java.util.Map<String, Double> map, String[] keys, double def) {
        for (String key : keys) {
            Double v = map.get(key);
            if (v != null) return v;
        }
        return def;
    }

    public static final class InstanceMetrics {
        private final String target;
        private final double heapPercent;
        private final double gcOverhead;
        private final double cpuPercent;
        private final boolean leakSuspected;
        private final long activeVThreads;
        private final boolean reachable;

        public InstanceMetrics(String target, double heapPercent, double gcOverhead,
                               double cpuPercent, boolean leakSuspected,
                               long activeVThreads, boolean reachable) {
            this.target = target;
            this.heapPercent = heapPercent;
            this.gcOverhead = gcOverhead;
            this.cpuPercent = cpuPercent;
            this.leakSuspected = leakSuspected;
            this.activeVThreads = activeVThreads;
            this.reachable = reachable;
        }

        public String target() { return target; }
        public double heapPercent() { return heapPercent; }
        public double gcOverhead() { return gcOverhead; }
        public double cpuPercent() { return cpuPercent; }
        public boolean leakSuspected() { return leakSuspected; }
        public long activeVThreads() { return activeVThreads; }
        public boolean reachable() { return reachable; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InstanceMetrics)) return false;
            InstanceMetrics that = (InstanceMetrics) o;
            return Double.compare(that.heapPercent, heapPercent) == 0
                    && Double.compare(that.gcOverhead, gcOverhead) == 0
                    && Double.compare(that.cpuPercent, cpuPercent) == 0
                    && leakSuspected == that.leakSuspected
                    && activeVThreads == that.activeVThreads
                    && reachable == that.reachable
                    && java.util.Objects.equals(target, that.target);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(target, heapPercent, gcOverhead, cpuPercent,
                    leakSuspected, activeVThreads, reachable);
        }

        @Override
        public String toString() {
            return "InstanceMetrics[target=" + target + ", heapPercent=" + heapPercent
                    + ", gcOverhead=" + gcOverhead + ", cpuPercent=" + cpuPercent
                    + ", leakSuspected=" + leakSuspected + ", activeVThreads=" + activeVThreads
                    + ", reachable=" + reachable + "]";
        }
    }

    public static final class AggregateStats {
        private final double heapMin, heapMax, heapAvg;
        private final double gcMin, gcMax, gcAvg;
        private final double cpuMin, cpuMax, cpuAvg;
        private final long vtTotal;
        private final int leakCount;
        private final String worstTarget;
        private final String worstReason;

        public AggregateStats(double heapMin, double heapMax, double heapAvg,
                              double gcMin, double gcMax, double gcAvg,
                              double cpuMin, double cpuMax, double cpuAvg,
                              long vtTotal, int leakCount,
                              String worstTarget, String worstReason) {
            this.heapMin = heapMin;
            this.heapMax = heapMax;
            this.heapAvg = heapAvg;
            this.gcMin = gcMin;
            this.gcMax = gcMax;
            this.gcAvg = gcAvg;
            this.cpuMin = cpuMin;
            this.cpuMax = cpuMax;
            this.cpuAvg = cpuAvg;
            this.vtTotal = vtTotal;
            this.leakCount = leakCount;
            this.worstTarget = worstTarget;
            this.worstReason = worstReason;
        }

        public double heapMin() { return heapMin; }
        public double heapMax() { return heapMax; }
        public double heapAvg() { return heapAvg; }
        public double gcMin() { return gcMin; }
        public double gcMax() { return gcMax; }
        public double gcAvg() { return gcAvg; }
        public double cpuMin() { return cpuMin; }
        public double cpuMax() { return cpuMax; }
        public double cpuAvg() { return cpuAvg; }
        public long vtTotal() { return vtTotal; }
        public int leakCount() { return leakCount; }
        public String worstTarget() { return worstTarget; }
        public String worstReason() { return worstReason; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AggregateStats)) return false;
            AggregateStats that = (AggregateStats) o;
            return Double.compare(that.heapMin, heapMin) == 0
                    && Double.compare(that.heapMax, heapMax) == 0
                    && Double.compare(that.heapAvg, heapAvg) == 0
                    && Double.compare(that.gcMin, gcMin) == 0
                    && Double.compare(that.gcMax, gcMax) == 0
                    && Double.compare(that.gcAvg, gcAvg) == 0
                    && Double.compare(that.cpuMin, cpuMin) == 0
                    && Double.compare(that.cpuMax, cpuMax) == 0
                    && Double.compare(that.cpuAvg, cpuAvg) == 0
                    && vtTotal == that.vtTotal
                    && leakCount == that.leakCount
                    && java.util.Objects.equals(worstTarget, that.worstTarget)
                    && java.util.Objects.equals(worstReason, that.worstReason);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(heapMin, heapMax, heapAvg, gcMin, gcMax, gcAvg,
                    cpuMin, cpuMax, cpuAvg, vtTotal, leakCount, worstTarget, worstReason);
        }

        @Override
        public String toString() {
            return "AggregateStats[heapMin=" + heapMin + ", heapMax=" + heapMax + ", heapAvg=" + heapAvg
                    + ", gcMin=" + gcMin + ", gcMax=" + gcMax + ", gcAvg=" + gcAvg
                    + ", cpuMin=" + cpuMin + ", cpuMax=" + cpuMax + ", cpuAvg=" + cpuAvg
                    + ", vtTotal=" + vtTotal + ", leakCount=" + leakCount
                    + ", worstTarget=" + worstTarget + ", worstReason=" + worstReason + "]";
        }
    }
}
