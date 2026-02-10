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
        appendStringAttribute(sb, "service.version", getVersion());
        sb.append(',');
        appendStringAttribute(sb, "telemetry.sdk.name", "argus");
        sb.append(',');
        appendStringAttribute(sb, "telemetry.sdk.language", "java");
        sb.append("]}");
    }

    // --- Virtual Thread Metrics (always enabled) ---

    private boolean appendVirtualThreadMetrics(StringBuilder sb, long nowNano, boolean first) {
        first = appendGauge(sb, first, "argus_virtual_threads_active",
                "Currently active virtual threads", nowNano, activeThreads.size());
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
        first = appendGauge(sb, first, "argus_carrier_threads_total",
                "Total carrier threads", nowNano, carrierAnalysis.totalCarriers());
        first = appendGaugeDouble(sb, first, "argus_carrier_threads_avg_per_carrier",
                "Average virtual threads per carrier", nowNano, carrierAnalysis.avgVirtualThreadsPerCarrier());
        return first;
    }

    // --- GC Metrics ---

    private boolean appendGcMetrics(StringBuilder sb, long nowNano, boolean first) {
        if (!config.isGcEnabled()) return first;

        var analysis = gcAnalyzer.getAnalysis();
        first = appendSum(sb, first, "argus_gc_events_total",
                "Total GC events", nowNano, analysis.totalGCEvents());
        first = appendSumDouble(sb, first, "argus_gc_pause_time_seconds_total",
                "Total GC pause time in seconds", nowNano, analysis.totalPauseTimeMs() / 1000.0);
        first = appendGaugeDouble(sb, first, "argus_gc_pause_time_seconds_max",
                "Maximum GC pause time", nowNano, analysis.maxPauseTimeMs() / 1000.0);
        first = appendGaugeDouble(sb, first, "argus_gc_overhead_ratio",
                "GC overhead percentage", nowNano, analysis.gcOverheadPercent());
        first = appendGauge(sb, first, "argus_heap_used_bytes",
                "Current heap used", nowNano, analysis.currentHeapUsed());
        first = appendGauge(sb, first, "argus_heap_committed_bytes",
                "Current heap committed", nowNano, analysis.currentHeapCommitted());
        return first;
    }

    // --- CPU Metrics ---

    private boolean appendCpuMetrics(StringBuilder sb, long nowNano, boolean first) {
        if (!config.isCpuEnabled()) return first;

        var analysis = cpuAnalyzer.getAnalysis();
        first = appendGaugeDouble(sb, first, "argus_cpu_jvm_user_ratio",
                "JVM user CPU ratio", nowNano, analysis.currentJvmUser());
        first = appendGaugeDouble(sb, first, "argus_cpu_jvm_system_ratio",
                "JVM system CPU ratio", nowNano, analysis.currentJvmSystem());
        first = appendGaugeDouble(sb, first, "argus_cpu_machine_total_ratio",
                "Machine total CPU ratio", nowNano, analysis.currentMachineTotal());
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
        first = appendGauge(sb, first, "argus_metaspace_used_bytes",
                "Metaspace used", nowNano, analysis.currentUsed());
        first = appendGauge(sb, first, "argus_metaspace_committed_bytes",
                "Metaspace committed", nowNano, analysis.currentCommitted());
        first = appendGauge(sb, first, "argus_metaspace_classes_loaded",
                "Loaded classes", nowNano, analysis.currentClassCount());
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
        sb.append("{\"key\":\"").append(key).append("\",\"value\":{\"stringValue\":\"").append(value).append("\"}}");
    }

    private String getVersion() {
        return "0.4.0";
    }
}
