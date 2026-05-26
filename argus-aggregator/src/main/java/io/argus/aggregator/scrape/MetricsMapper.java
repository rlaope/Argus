package io.argus.aggregator.scrape;

import io.argus.aggregator.model.TileMetrics;

import java.util.Map;

/**
 * Maps a parsed Prometheus metric map (from argus-agent's {@code /prometheus})
 * into a {@link TileMetrics} record.
 *
 * <p>Tries multiple known metric names per dimension to be robust against
 * argus-agent / Micrometer / Spring-style metric naming.
 */
public final class MetricsMapper {

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

    private MetricsMapper() {}

    public static TileMetrics map(Map<String, Double> metrics) {
        Double heap = pick(metrics, HEAP_METRICS);
        Double gc   = pick(metrics, GC_METRICS);
        Double cpu  = pick(metrics, CPU_METRICS);
        double vt   = orDefault(pick(metrics, VT_METRICS), 0.0);
        double leak = orDefault(pick(metrics, LEAK_METRICS), 0.0);
        return new TileMetrics(heap, gc, cpu, (long) vt, leak > 0.5);
    }

    private static Double pick(Map<String, Double> map, String[] keys) {
        for (String k : keys) {
            Double v = map.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static double orDefault(Double v, double def) {
        return v == null ? def : v;
    }
}
