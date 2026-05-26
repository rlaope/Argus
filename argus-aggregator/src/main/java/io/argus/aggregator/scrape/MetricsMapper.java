package io.argus.aggregator.scrape;

import io.argus.aggregator.model.TileMetrics;

import java.util.Map;

/**
 * Maps a parsed Prometheus metric map (from argus-agent's {@code /prometheus})
 * into a {@link TileMetrics} record.
 *
 * <p>All percent-shaped fields ({@code heapPercent}, {@code gcOverheadPercent},
 * {@code cpuPercent}) must be 0–100. Any candidate series whose natural unit
 * is bytes or a 0–1 ratio is excluded from the percent list; mixing them in
 * causes a "tile always RED" bug because billions of bytes are interpreted
 * as a percent.
 *
 * <p>If only bytes-valued data is available for heap, leave {@code heap=null}
 * and surface "unavailable" rather than fabricating a number from the wrong
 * unit.
 */
public final class MetricsMapper {

    /** Percent-shaped heap metric names (0–100). Bytes-valued names are deliberately excluded. */
    private static final String[] HEAP_METRICS = {
            "argus_heap_used_percent",
            "heap_used_percent"
    };
    /** Percent-shaped GC overhead names (0–100). */
    private static final String[] GC_METRICS = {
            "argus_gc_overhead_percent",
            "jvm_gc_overhead_percent",
            "gc_overhead_percent"
    };
    /** Percent-shaped CPU names (0–100). */
    private static final String[] CPU_METRICS = {
            "argus_cpu_process_percent"
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

    /** Ratio-shaped (0–1) CPU fallbacks — multiplied by 100 to produce a percent. */
    private static final String[] CPU_RATIO_METRICS = {
            "jvm_cpu_usage",
            "process_cpu_usage"
    };

    private MetricsMapper() {}

    public static TileMetrics map(Map<String, Double> metrics) {
        Double heap = pick(metrics, HEAP_METRICS);
        Double gc   = pick(metrics, GC_METRICS);
        Double cpu  = pick(metrics, CPU_METRICS);
        if (cpu == null) {
            Double ratio = pick(metrics, CPU_RATIO_METRICS);
            if (ratio != null) {
                cpu = ratio * 100.0;
            }
        }
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
