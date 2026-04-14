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

    public record InstanceMetrics(
        String target,
        double heapPercent,
        double gcOverhead,
        double cpuPercent,
        boolean leakSuspected,
        long activeVThreads,
        boolean reachable
    ) {}

    public record AggregateStats(
        double heapMin, double heapMax, double heapAvg,
        double gcMin,   double gcMax,   double gcAvg,
        double cpuMin,  double cpuMax,  double cpuAvg,
        long   vtTotal,
        int    leakCount,
        String worstTarget,
        String worstReason
    ) {}

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
        List<InstanceMetrics> up = instances.stream().filter(InstanceMetrics::reachable).toList();
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
            if (!reason.isEmpty()) reason.append(", ");
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
}
