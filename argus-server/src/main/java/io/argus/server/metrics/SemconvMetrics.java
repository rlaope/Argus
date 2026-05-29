package io.argus.server.metrics;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Single source of truth mapping Argus JVM metrics to OpenTelemetry semantic
 * conventions for JVM runtime metrics
 * (<a href="https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/">semconv</a>).
 *
 * <p>OTel metric names use dots ({@code jvm.gc.duration}); in Prometheus
 * exposition the dots become underscores and the unit is appended as a suffix
 * ({@code jvm_gc_duration_seconds}). This table records both forms so the
 * Prometheus collector and the OTLP exporter draw their names from one place
 * instead of hard-coding strings independently.
 *
 * <p>Argus-unique metrics that have no semconv equivalent (leak confidence,
 * carrier-thread skew, profiling samples, …) are intentionally NOT listed here:
 * they keep the {@code argus.*} namespace and are emitted unconditionally as
 * Argus differentiators, not "legacy" duplicates.
 */
public final class SemconvMetrics {

    /** OTel instrument kind, mirrored to the Prometheus {@code # TYPE} line. */
    public enum Type {
        GAUGE("gauge"),
        SUM("counter"),
        HISTOGRAM("histogram");

        private final String prometheusType;

        Type(String prometheusType) {
            this.prometheusType = prometheusType;
        }

        /** The Prometheus {@code # TYPE} token for this instrument. */
        public String prometheusType() {
            return prometheusType;
        }
    }

    /** Standard semconv attribute keys carried by JVM metrics. */
    public static final String ATTR_GC_NAME = "jvm.gc.name";
    public static final String ATTR_GC_ACTION = "jvm.gc.action";
    public static final String ATTR_MEMORY_POOL_NAME = "jvm.memory.pool.name";

    /**
     * One row of the semconv table.
     *
     * @param otelName      dotted OTel metric name (e.g. {@code jvm.gc.duration})
     * @param prometheusName Prometheus exposition name (dots→underscores, unit suffix)
     * @param unit          UCUM unit string ({@code s}, {@code By}, {@code 1}, …)
     * @param type          instrument kind
     * @param description   metric HELP text
     * @param attributes    standard attribute keys this metric carries
     */
    public record Metric(String otelName, String prometheusName, String unit,
                         Type type, String description, List<String> attributes) {
    }

    // --- Standard JVM runtime metrics (semconv) ---------------------------------

    /**
     * The semconv spec declares {@code jvm.gc.name}/{@code jvm.gc.action} for this
     * histogram, but Argus's GC analyzer only maintains a single aggregate pause
     * histogram — bucket distributions are not tracked per collector/cause. We
     * therefore do NOT declare those attributes here, because neither the
     * Prometheus nor the OTLP path can populate a per-collector histogram without
     * fabricating bucket data. The per-collector/per-cause breakdown is exposed
     * instead as the Argus-unique {@code argus_gc_pause_breakdown_seconds_total} /
     * {@code argus_gc_events_breakdown_total} series (cumulative pause time and
     * event counts, not a histogram).
     */
    public static final Metric GC_DURATION = new Metric(
            "jvm.gc.duration", "jvm_gc_duration_seconds", "s", Type.HISTOGRAM,
            "Duration of JVM garbage collection actions",
            List.of());

    public static final Metric MEMORY_USED = new Metric(
            "jvm.memory.used", "jvm_memory_used_bytes", "By", Type.GAUGE,
            "Measure of memory used",
            List.of(ATTR_MEMORY_POOL_NAME));

    public static final Metric MEMORY_COMMITTED = new Metric(
            "jvm.memory.committed", "jvm_memory_committed_bytes", "By", Type.GAUGE,
            "Measure of memory committed",
            List.of(ATTR_MEMORY_POOL_NAME));

    public static final Metric MEMORY_USED_AFTER_LAST_GC = new Metric(
            "jvm.memory.used_after_last_gc", "jvm_memory_used_after_last_gc_bytes", "By", Type.GAUGE,
            "Measure of memory used after the most recent garbage collection",
            List.of(ATTR_MEMORY_POOL_NAME));

    public static final Metric THREAD_COUNT = new Metric(
            "jvm.thread.count", "jvm_thread_count", "{thread}", Type.GAUGE,
            "Number of executing platform or virtual threads",
            List.of());

    public static final Metric CLASS_COUNT = new Metric(
            "jvm.class.count", "jvm_class_count", "{class}", Type.GAUGE,
            "Number of classes currently loaded",
            List.of());

    public static final Metric CPU_TIME = new Metric(
            "jvm.cpu.time", "jvm_cpu_time_seconds_total", "s", Type.SUM,
            "CPU time used by the process as reported by the JVM",
            List.of());

    public static final Metric CPU_RECENT_UTILIZATION = new Metric(
            "jvm.cpu.recent_utilization", "jvm_cpu_recent_utilization_ratio", "1", Type.GAUGE,
            "Recent CPU utilization for the process as reported by the JVM",
            List.of());

    /** The full semconv table, in stable declaration order. */
    public static final List<Metric> ALL = List.of(
            GC_DURATION,
            MEMORY_USED,
            MEMORY_COMMITTED,
            MEMORY_USED_AFTER_LAST_GC,
            THREAD_COUNT,
            CLASS_COUNT,
            CPU_TIME,
            CPU_RECENT_UTILIZATION);

    private static final Map<String, Metric> BY_OTEL_NAME = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(Metric::otelName, m -> m));

    private SemconvMetrics() {
    }

    /** Looks up a metric by its dotted OTel name, or {@code null} if unmapped. */
    public static Metric byOtelName(String otelName) {
        return BY_OTEL_NAME.get(otelName);
    }
}
