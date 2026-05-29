package io.argus.server.metrics;

import io.argus.core.config.AgentConfig;
import io.argus.server.analysis.*;
import io.argus.server.state.ActiveThreadsRegistry;

/**
 * Builds OTLP JSON Protobuf encoding for metrics export.
 *
 * <p>Hand-coded OTLP JSON format following the OpenTelemetry specification,
 * maintaining the project's zero-dependency philosophy.
 */
public final class OtlpJsonBuilder {

    private final AgentConfig config;
    private final ServerMetrics metrics;
    private final ActiveThreadsRegistry activeThreads;
    private final PinningAnalyzer pinningAnalyzer;
    private final CarrierThreadAnalyzer carrierAnalyzer;
    private final GCAnalyzer gcAnalyzer;
    private final CPUAnalyzer cpuAnalyzer;
    private final AllocationAnalyzer allocationAnalyzer;
    private final MetaspaceAnalyzer metaspaceAnalyzer;
    private final MethodProfilingAnalyzer methodProfilingAnalyzer;
    private final ContentionAnalyzer contentionAnalyzer;

    public OtlpJsonBuilder(AgentConfig config, ServerMetrics metrics,
                           ActiveThreadsRegistry activeThreads,
                           PinningAnalyzer pinningAnalyzer,
                           CarrierThreadAnalyzer carrierAnalyzer,
                           GCAnalyzer gcAnalyzer, CPUAnalyzer cpuAnalyzer,
                           AllocationAnalyzer allocationAnalyzer,
                           MetaspaceAnalyzer metaspaceAnalyzer,
                           MethodProfilingAnalyzer methodProfilingAnalyzer,
                           ContentionAnalyzer contentionAnalyzer) {
        this.config = config;
        this.metrics = metrics;
        this.activeThreads = activeThreads;
        this.pinningAnalyzer = pinningAnalyzer;
        this.carrierAnalyzer = carrierAnalyzer;
        this.gcAnalyzer = gcAnalyzer;
        this.cpuAnalyzer = cpuAnalyzer;
        this.allocationAnalyzer = allocationAnalyzer;
        this.metaspaceAnalyzer = metaspaceAnalyzer;
        this.methodProfilingAnalyzer = methodProfilingAnalyzer;
        this.contentionAnalyzer = contentionAnalyzer;
    }

    /**
     * Builds the complete OTLP JSON metrics payload.
     *
     * @return OTLP JSON string
     */
    public String build() {
        long nowNano = System.currentTimeMillis() * 1_000_000L;
        StringBuilder sb = new StringBuilder(4096);

        sb.append("{\"resourceMetrics\":[{");
        appendResource(sb);
        sb.append(",\"scopeMetrics\":[{");
        sb.append("\"scope\":{\"name\":\"io.argus.metrics\",\"version\":\"")
                .append(getVersion()).append("\"},");
        sb.append("\"metrics\":[");

        boolean first = true;
        first = appendVirtualThreadMetrics(sb, nowNano, first);
        first = appendGcMetrics(sb, nowNano, first);
        first = appendCpuMetrics(sb, nowNano, first);
        first = appendAllocationMetrics(sb, nowNano, first);
        first = appendMetaspaceMetrics(sb, nowNano, first);
        first = appendProfilingMetrics(sb, nowNano, first);
        appendContentionMetrics(sb, nowNano, first);

        sb.append("]}]}]}");
        return sb.toString();
    }

    private void appendResource(StringBuilder sb) {
        sb.append("\"resource\":{\"attributes\":[");
        appendStringAttribute(sb, "service.name", config.getOtlpServiceName());
        sb.append(',');
        appendStringAttribute(sb, "service.namespace", serviceNamespace());
        sb.append(',');
        appendStringAttribute(sb, "service.instance.id", serviceInstanceId());
        sb.append(',');
        appendStringAttribute(sb, "service.version", getVersion());
        sb.append(',');
        appendStringAttribute(sb, "telemetry.sdk.name", "argus");
        sb.append(',');
        appendStringAttribute(sb, "telemetry.sdk.language", "java");
        sb.append(',');
        appendStringAttribute(sb, "telemetry.sdk.version", getVersion());
        sb.append("]}");
    }

    /**
     * OTel {@code service.namespace}: the Kubernetes namespace when running in a
     * pod (via the Downward API), otherwise empty.
     */
    private String serviceNamespace() {
        String ns = KubernetesLabels.getLabels().get("namespace");
        return ns != null ? ns : "";
    }

    /**
     * OTel {@code service.instance.id}: the pod name in Kubernetes, otherwise the
     * hostname, falling back to the service name when neither is available.
     */
    private String serviceInstanceId() {
        String pod = KubernetesLabels.getLabels().get("pod");
        if (pod != null) {
            return pod;
        }
        String host = System.getenv("HOSTNAME");
        return (host != null && !host.isBlank()) ? host : config.getOtlpServiceName();
    }

    // --- Virtual Thread Metrics (always enabled) ---

    private boolean appendVirtualThreadMetrics(StringBuilder sb, long nowNano, boolean first) {
        // Standard OTel semconv name, emitted alongside the legacy argus_* series.
        first = appendGauge(sb, first, SemconvMetrics.THREAD_COUNT.otelName(),
                SemconvMetrics.THREAD_COUNT.description(), nowNano, activeThreads.size());
        // Legacy duplicate of jvm.thread.count — gated like the Prometheus path so
        // legacyNames=false does not double OTLP series.
        if (config.isLegacyMetricNames()) {
            first = appendGauge(sb, first, "argus_virtual_threads_active",
                    "Currently active virtual threads", nowNano, activeThreads.size());
        }
        first = appendSum(sb, first, "argus_virtual_threads_started_total",
                "Total virtual threads started", nowNano, metrics.getStartEvents());
        first = appendSum(sb, first, "argus_virtual_threads_ended_total",
                "Total virtual threads ended", nowNano, metrics.getEndEvents());
        first = appendSum(sb, first, "argus_virtual_threads_pinned_total",
                "Total pinning events", nowNano, metrics.getPinnedEvents());
        first = appendSum(sb, first, "argus_virtual_threads_submit_failed_total",
                "Total submit failures", nowNano, metrics.getSubmitFailedEvents());

        var pinAnalysis = pinningAnalyzer.getAnalysis();
        first = appendGauge(sb, first, "argus_virtual_threads_pinned_unique_stacks",
                "Unique pinning stack traces", nowNano, pinAnalysis.uniqueStackTraces());

        var carrierAnalysis = carrierAnalyzer.getAnalysis();
        first = appendGauge(sb, first, "argus_carrier_threads",
                "Total carrier threads", nowNano, carrierAnalysis.totalCarriers());
        first = appendSum(sb, first, "argus_carrier_threads_virtual_handled_total",
                "Total virtual threads handled by carrier threads", nowNano,
                carrierAnalysis.totalVirtualThreadsHandled());
        first = appendGaugeDouble(sb, first, "argus_carrier_threads_avg_per_carrier",
                "Average virtual threads per carrier", nowNano, carrierAnalysis.avgVirtualThreadsPerCarrier());
        return first;
    }

    // --- GC Metrics ---

    private boolean appendGcMetrics(StringBuilder sb, long nowNano, boolean first) {
        if (!config.isGcEnabled()) return first;

        var analysis = gcAnalyzer.getAnalysis();
        // Standard OTel semconv names (heap pool), emitted alongside legacy argus_* series.
        // Each data point carries jvm.memory.pool.name="heap" so the OTLP series match
        // the Prometheus {jvm_memory_pool_name="heap"} identity.
        first = appendGaugeWithPool(sb, first, SemconvMetrics.MEMORY_USED.otelName(),
                SemconvMetrics.MEMORY_USED.description(), nowNano, analysis.currentHeapUsed(), "heap");
        first = appendGaugeWithPool(sb, first, SemconvMetrics.MEMORY_COMMITTED.otelName(),
                SemconvMetrics.MEMORY_COMMITTED.description(), nowNano, analysis.currentHeapCommitted(), "heap");
        first = appendGaugeWithPool(sb, first, SemconvMetrics.MEMORY_USED_AFTER_LAST_GC.otelName(),
                SemconvMetrics.MEMORY_USED_AFTER_LAST_GC.description(), nowNano, analysis.currentHeapUsed(), "heap");
        // Standard OTel semconv GC pause histogram (aggregate; see SemconvMetrics.GC_DURATION
        // for why it is not split per collector/cause).
        first = appendGcDurationHistogram(sb, first, nowNano);
        first = appendSum(sb, first, "argus_gc_events_total",
                "Total GC events", nowNano, analysis.totalGCEvents());
        first = appendSumDouble(sb, first, "argus_gc_pause_time_seconds_total",
                "Total GC pause time in seconds", nowNano, analysis.totalPauseTimeMs() / 1000.0);
        first = appendGaugeDouble(sb, first, "argus_gc_pause_time_seconds_max",
                "Maximum GC pause time", nowNano, analysis.maxPauseTimeMs() / 1000.0);
        first = appendGaugeDouble(sb, first, "argus_gc_overhead_ratio",
                "GC overhead percentage", nowNano, analysis.gcOverheadPercent());
        // Argus-unique GC diagnostics (no semconv equivalent) — always emitted, in
        // parity with the Prometheus collector. Values match the Prometheus path.
        first = appendGaugeDouble(sb, first, "argus_gc_pause_time_seconds_avg",
                "Average GC pause time in seconds", nowNano, analysis.avgPauseTimeMs() / 1000.0);
        first = appendGauge(sb, first, "argus_gc_overhead_warning",
                "GC overhead warning (1 = overhead > 10%)", nowNano, analysis.isOverheadWarning() ? 1 : 0);
        first = appendGaugeDouble(sb, first, "argus_gc_allocation_rate_kbps",
                "Allocation rate in KB/s from recent GC events", nowNano, analysis.allocationRateKBPerSec());
        first = appendGaugeDouble(sb, first, "argus_gc_promotion_rate_kbps",
                "Promotion rate (young to old gen) in KB/s", nowNano, analysis.promotionRateKBPerSec());
        first = appendGauge(sb, first, "argus_gc_leak_suspected",
                "Memory leak suspected (1 = leak detected)", nowNano, analysis.leakSuspected() ? 1 : 0);
        first = appendGaugeDouble(sb, first, "argus_gc_leak_confidence",
                "Memory leak detection confidence (R-squared 0-1)", nowNano, analysis.leakConfidencePercent() / 100.0);
        first = appendGaugeDouble(sb, first, "argus_heap_usage_ratio",
                "Heap usage ratio (used/committed)", nowNano,
                analysis.currentHeapCommitted() > 0
                        ? (double) analysis.currentHeapUsed() / analysis.currentHeapCommitted() : 0);
        // Legacy duplicates of jvm.memory.used / jvm.memory.committed (pool=heap) —
        // gated so legacyNames=false does not double OTLP series.
        if (config.isLegacyMetricNames()) {
            first = appendGauge(sb, first, "argus_heap_used_bytes",
                    "Current heap used", nowNano, analysis.currentHeapUsed());
            first = appendGauge(sb, first, "argus_heap_committed_bytes",
                    "Current heap committed", nowNano, analysis.currentHeapCommitted());
        }
        return first;
    }

    // --- CPU Metrics ---

    private boolean appendCpuMetrics(StringBuilder sb, long nowNano, boolean first) {
        if (!config.isCpuEnabled()) return first;

        var analysis = cpuAnalyzer.getAnalysis();
        // Standard OTel semconv name, emitted alongside the legacy argus_* ratios.
        first = appendGaugeDouble(sb, first, SemconvMetrics.CPU_RECENT_UTILIZATION.otelName(),
                SemconvMetrics.CPU_RECENT_UTILIZATION.description(), nowNano, analysis.currentJvmTotal());
        // Legacy ratios replaced by jvm.cpu.recent_utilization — gated as a block,
        // matching the Prometheus collector's appendCPUMetrics early-return.
        if (config.isLegacyMetricNames()) {
            first = appendGaugeDouble(sb, first, "argus_cpu_jvm_user_ratio",
                    "JVM user CPU ratio", nowNano, analysis.currentJvmUser());
            first = appendGaugeDouble(sb, first, "argus_cpu_jvm_system_ratio",
                    "JVM system CPU ratio", nowNano, analysis.currentJvmSystem());
            first = appendGaugeDouble(sb, first, "argus_cpu_machine_total_ratio",
                    "Machine total CPU ratio", nowNano, analysis.currentMachineTotal());
        }
        return first;
    }

    // --- Allocation Metrics ---

    private boolean appendAllocationMetrics(StringBuilder sb, long nowNano, boolean first) {
        if (allocationAnalyzer == null) return first;

        var analysis = allocationAnalyzer.getAnalysis();
        first = appendSum(sb, first, "argus_allocation_total",
                "Total allocations", nowNano, analysis.totalAllocations());
        first = appendSum(sb, first, "argus_allocation_bytes_total",
                "Total bytes allocated", nowNano, analysis.totalBytesAllocated());
        first = appendGaugeDouble(sb, first, "argus_allocation_rate_bytes_per_second",
                "Allocation rate", nowNano, analysis.allocationRateBytesPerSec());
        return first;
    }

    // --- Metaspace Metrics ---

    private boolean appendMetaspaceMetrics(StringBuilder sb, long nowNano, boolean first) {
        if (metaspaceAnalyzer == null) return first;

        var analysis = metaspaceAnalyzer.getAnalysis();
        // Standard OTel semconv name, emitted alongside the legacy argus_* series.
        first = appendGauge(sb, first, SemconvMetrics.CLASS_COUNT.otelName(),
                SemconvMetrics.CLASS_COUNT.description(), nowNano, analysis.currentClassCount());
        // Standard OTel semconv memory series for the Metaspace pool, matching the
        // Prometheus path's {jvm_memory_pool_name="Metaspace"} identity. Gated by the
        // same metaspace-enabled + non-null guard the Prometheus collector uses.
        if (config.isMetaspaceEnabled()) {
            first = appendGaugeWithPool(sb, first, SemconvMetrics.MEMORY_USED.otelName(),
                    SemconvMetrics.MEMORY_USED.description(), nowNano, analysis.currentUsed(), "Metaspace");
            first = appendGaugeWithPool(sb, first, SemconvMetrics.MEMORY_COMMITTED.otelName(),
                    SemconvMetrics.MEMORY_COMMITTED.description(), nowNano, analysis.currentCommitted(), "Metaspace");
        }
        // Argus-unique: no semconv reserved-memory metric — always emitted, in parity
        // with the Prometheus collector.
        first = appendGauge(sb, first, "argus_metaspace_reserved_bytes",
                "Metaspace memory reserved in bytes", nowNano, analysis.currentReserved());
        // Legacy duplicates of jvm.memory.used / jvm.memory.committed (pool=Metaspace)
        // and jvm.class.count — gated so legacyNames=false does not double OTLP series.
        if (config.isLegacyMetricNames()) {
            first = appendGauge(sb, first, "argus_metaspace_used_bytes",
                    "Metaspace used", nowNano, analysis.currentUsed());
            first = appendGauge(sb, first, "argus_metaspace_committed_bytes",
                    "Metaspace committed", nowNano, analysis.currentCommitted());
            first = appendGauge(sb, first, "argus_metaspace_classes_loaded",
                    "Loaded classes", nowNano, analysis.currentClassCount());
        }
        return first;
    }

    // --- Profiling Metrics ---

    private boolean appendProfilingMetrics(StringBuilder sb, long nowNano, boolean first) {
        if (methodProfilingAnalyzer == null) return first;

        var analysis = methodProfilingAnalyzer.getAnalysis();
        first = appendSum(sb, first, "argus_profiling_samples_total",
                "Total profiling samples", nowNano, analysis.totalSamples());
        return first;
    }

    // --- Contention Metrics ---

    private boolean appendContentionMetrics(StringBuilder sb, long nowNano, boolean first) {
        if (contentionAnalyzer == null) return first;

        var analysis = contentionAnalyzer.getAnalysis();
        first = appendSum(sb, first, "argus_contention_events_total",
                "Total contention events", nowNano, analysis.totalContentionEvents());
        appendSumDouble(sb, first, "argus_contention_time_seconds_total",
                "Total contention time", nowNano, analysis.totalContentionTimeMs() / 1000.0);
        return first;
    }

    // --- Helper methods for OTLP JSON metric types ---

    private boolean appendGauge(StringBuilder sb, boolean first, String name,
                                String description, long nowNano, long value) {
        if (!first) sb.append(',');
        sb.append("{\"name\":\"").append(name).append("\",");
        sb.append("\"description\":\"").append(description).append("\",");
        sb.append("\"gauge\":{\"dataPoints\":[{");
        sb.append("\"timeUnixNano\":\"").append(nowNano).append("\",");
        sb.append("\"asInt\":\"").append(value).append("\"}]}}");
        return false;
    }

    private boolean appendGaugeDouble(StringBuilder sb, boolean first, String name,
                                      String description, long nowNano, double value) {
        if (!first) sb.append(',');
        sb.append("{\"name\":\"").append(name).append("\",");
        sb.append("\"description\":\"").append(description).append("\",");
        sb.append("\"gauge\":{\"dataPoints\":[{");
        sb.append("\"timeUnixNano\":\"").append(nowNano).append("\",");
        sb.append("\"asDouble\":").append(value).append("}]}}");
        return false;
    }

    /**
     * Emits an integer gauge whose single data point carries a
     * {@code jvm.memory.pool.name} attribute, so OTLP memory series match the
     * Prometheus {@code {jvm_memory_pool_name="..."}} identity.
     */
    private boolean appendGaugeWithPool(StringBuilder sb, boolean first, String name,
                                        String description, long nowNano, long value, String pool) {
        if (!first) sb.append(',');
        sb.append("{\"name\":\"").append(name).append("\",");
        sb.append("\"description\":\"").append(description).append("\",");
        sb.append("\"gauge\":{\"dataPoints\":[{");
        sb.append("\"timeUnixNano\":\"").append(nowNano).append("\",");
        sb.append("\"asInt\":\"").append(value).append("\",");
        sb.append("\"attributes\":[{\"key\":\"").append(SemconvMetrics.ATTR_MEMORY_POOL_NAME)
                .append("\",\"value\":{\"stringValue\":\"").append(escape(pool)).append("\"}}]");
        sb.append("}]}}");
        return false;
    }

    /**
     * Emits the standard OTel semconv {@code jvm.gc.duration} as an OTLP Histogram
     * data point, drawn from the same aggregate pause histogram the Prometheus path
     * uses (explicit bucket bounds, cumulative counts converted to per-bucket counts,
     * sum, and total count). No GC pause data → the series is skipped. The histogram
     * is not split per collector/cause (see {@link SemconvMetrics#GC_DURATION}).
     */
    private boolean appendGcDurationHistogram(StringBuilder sb, boolean first, long nowNano) {
        var hist = gcAnalyzer.getPauseHistogram();
        if (hist.count() == 0) return first;

        double[] bounds = hist.upperBounds();
        long[] cumulative = hist.cumulativeCounts(); // length = bounds.length + 1 (last = +Inf)

        if (!first) sb.append(',');
        sb.append("{\"name\":\"").append(SemconvMetrics.GC_DURATION.otelName()).append("\",");
        sb.append("\"description\":\"").append(SemconvMetrics.GC_DURATION.description()).append("\",");
        sb.append("\"unit\":\"").append(SemconvMetrics.GC_DURATION.unit()).append("\",");
        sb.append("\"histogram\":{\"aggregationTemporality\":2,\"dataPoints\":[{");
        sb.append("\"timeUnixNano\":\"").append(nowNano).append("\",");
        sb.append("\"count\":\"").append(hist.count()).append("\",");
        sb.append("\"sum\":").append(hist.sumSeconds()).append(',');

        // explicitBounds: the finite upper bounds (OTLP omits the implicit +Inf bound).
        sb.append("\"explicitBounds\":[");
        for (int i = 0; i < bounds.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(bounds[i]);
        }
        sb.append("],");

        // bucketCounts: per-bucket (non-cumulative) counts; length = bounds.length + 1.
        sb.append("\"bucketCounts\":[");
        long prev = 0;
        for (int i = 0; i < cumulative.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(cumulative[i] - prev).append('"');
            prev = cumulative[i];
        }
        sb.append(']');
        sb.append("}]}}");
        return false;
    }

    private boolean appendSum(StringBuilder sb, boolean first, String name,
                              String description, long nowNano, long value) {
        if (!first) sb.append(',');
        sb.append("{\"name\":\"").append(name).append("\",");
        sb.append("\"description\":\"").append(description).append("\",");
        sb.append("\"sum\":{\"dataPoints\":[{");
        sb.append("\"timeUnixNano\":\"").append(nowNano).append("\",");
        sb.append("\"asInt\":\"").append(value).append("\"}],");
        sb.append("\"aggregationTemporality\":2,\"isMonotonic\":true}}");
        return false;
    }

    private boolean appendSumDouble(StringBuilder sb, boolean first, String name,
                                    String description, long nowNano, double value) {
        if (!first) sb.append(',');
        sb.append("{\"name\":\"").append(name).append("\",");
        sb.append("\"description\":\"").append(description).append("\",");
        sb.append("\"sum\":{\"dataPoints\":[{");
        sb.append("\"timeUnixNano\":\"").append(nowNano).append("\",");
        sb.append("\"asDouble\":").append(value).append("}],");
        sb.append("\"aggregationTemporality\":2,\"isMonotonic\":true}}");
        return false;
    }

    private void appendStringAttribute(StringBuilder sb, String key, String value) {
        sb.append("{\"key\":\"").append(key).append("\",\"value\":{\"stringValue\":\"").append(escape(value)).append("\"}}");
    }

    /** Minimal JSON string escaping for attribute values. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private String getVersion() {
        return "0.4.0";
    }
}
