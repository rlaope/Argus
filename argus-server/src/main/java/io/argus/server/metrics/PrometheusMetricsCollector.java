package io.argus.server.metrics;

import io.argus.core.config.AgentConfig;
import io.argus.server.analysis.AllocationAnalyzer;
import io.argus.server.analysis.CarrierThreadAnalyzer;
import io.argus.server.analysis.ContentionAnalyzer;
import io.argus.server.analysis.CPUAnalyzer;
import io.argus.server.analysis.GCAnalyzer;
import io.argus.server.analysis.MetaspaceAnalyzer;
import io.argus.server.analysis.MethodProfilingAnalyzer;
import io.argus.server.analysis.PinningAnalyzer;
import io.argus.server.state.ActiveThreadsRegistry;

/**
 * Collects metrics from all analyzers and formats them in Prometheus exposition text format.
 *
 * <p>Metrics are tiered based on existing feature flags in {@link AgentConfig}.
 * If a JFR feature is enabled (and its overhead is already paid), its metrics
 * are automatically exposed via this collector.
 *
 * <p>Endpoint: {@code /prometheus}
 * <p>Content-Type: {@code text/plain; version=0.0.4; charset=utf-8}
 */
public final class PrometheusMetricsCollector {

    private final AgentConfig config;
    private final ServerMetrics serverMetrics;
    private final ActiveThreadsRegistry activeThreads;
    private final PinningAnalyzer pinningAnalyzer;
    private final CarrierThreadAnalyzer carrierAnalyzer;
    private final GCAnalyzer gcAnalyzer;
    private final CPUAnalyzer cpuAnalyzer;
    private final AllocationAnalyzer allocationAnalyzer;
    private final MetaspaceAnalyzer metaspaceAnalyzer;
    private final MethodProfilingAnalyzer methodProfilingAnalyzer;
    private final ContentionAnalyzer contentionAnalyzer;

    /**
     * Creates a new Prometheus metrics collector.
     *
     * @param config                 the agent configuration
     * @param serverMetrics          the server metrics tracker
     * @param activeThreads          the active threads registry
     * @param pinningAnalyzer        the pinning analyzer
     * @param carrierAnalyzer        the carrier thread analyzer
     * @param gcAnalyzer             the GC analyzer
     * @param cpuAnalyzer            the CPU analyzer
     * @param allocationAnalyzer     the allocation analyzer (null if disabled)
     * @param metaspaceAnalyzer      the metaspace analyzer (null if disabled)
     * @param methodProfilingAnalyzer the method profiling analyzer (null if disabled)
     * @param contentionAnalyzer     the contention analyzer (null if disabled)
     */
    public PrometheusMetricsCollector(
            AgentConfig config,
            ServerMetrics serverMetrics,
            ActiveThreadsRegistry activeThreads,
            PinningAnalyzer pinningAnalyzer,
            CarrierThreadAnalyzer carrierAnalyzer,
            GCAnalyzer gcAnalyzer,
            CPUAnalyzer cpuAnalyzer,
            AllocationAnalyzer allocationAnalyzer,
            MetaspaceAnalyzer metaspaceAnalyzer,
            MethodProfilingAnalyzer methodProfilingAnalyzer,
            ContentionAnalyzer contentionAnalyzer) {
        this.config = config;
        this.serverMetrics = serverMetrics;
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
     * Collects all enabled metrics and formats them as Prometheus exposition text.
     *
     * @return Prometheus text format string
     */
    public String collectMetrics() {
        StringBuilder sb = new StringBuilder(4096);

        appendVirtualThreadMetrics(sb);
        appendPinningMetrics(sb);
        appendCarrierThreadMetrics(sb);

        if (config.isGcEnabled()) {
            appendGCMetrics(sb);
        }

        if (config.isCpuEnabled()) {
            appendCPUMetrics(sb);
        }

        if (config.isMetaspaceEnabled() && metaspaceAnalyzer != null) {
            appendMetaspaceMetrics(sb);
        }

        if (config.isContentionEnabled() && contentionAnalyzer != null) {
            appendContentionMetrics(sb);
        }

        if (config.isAllocationEnabled() && allocationAnalyzer != null) {
            appendAllocationMetrics(sb);
        }

        if (config.isProfilingEnabled() && methodProfilingAnalyzer != null) {
            appendProfilingMetrics(sb);
        }

        appendBuildInfo(sb);

        return sb.toString();
    }

    private void appendVirtualThreadMetrics(StringBuilder sb) {
        appendCounter(sb, "argus_virtual_threads_started_total",
                "Total number of virtual threads started",
                serverMetrics.getStartEvents());
        appendCounter(sb, "argus_virtual_threads_ended_total",
                "Total number of virtual threads ended",
                serverMetrics.getEndEvents());
        appendGauge(sb, "argus_virtual_threads_active",
                "Number of currently active virtual threads",
                activeThreads.size());
        appendCounter(sb, "argus_virtual_threads_submit_failed_total",
                "Total number of virtual thread submit failures",
                serverMetrics.getSubmitFailedEvents());
    }

    private void appendPinningMetrics(StringBuilder sb) {
        var analysis = pinningAnalyzer.getAnalysis();
        appendCounter(sb, "argus_virtual_threads_pinned_total",
                "Total number of virtual thread pinning events",
                analysis.totalPinnedEvents());
        appendGauge(sb, "argus_virtual_threads_pinned_unique_stacks",
                "Number of unique pinning stack traces",
                analysis.uniqueStackTraces());
    }

    private void appendCarrierThreadMetrics(StringBuilder sb) {
        var analysis = carrierAnalyzer.getAnalysis();
        appendGauge(sb, "argus_carrier_threads_total",
                "Total number of carrier threads",
                analysis.totalCarriers());
        appendCounter(sb, "argus_carrier_threads_virtual_handled_total",
                "Total virtual threads handled by carrier threads",
                analysis.totalVirtualThreadsHandled());
        appendGauge(sb, "argus_carrier_threads_avg_per_carrier",
                "Average virtual threads per carrier thread",
                analysis.avgVirtualThreadsPerCarrier());
    }

    private void appendGCMetrics(StringBuilder sb) {
        var analysis = gcAnalyzer.getAnalysis();
        appendCounter(sb, "argus_gc_events_total",
                "Total number of GC events",
                analysis.totalGCEvents());
        appendCounter(sb, "argus_gc_pause_time_seconds_total",
                "Total GC pause time in seconds",
                analysis.totalPauseTimeMs() / 1000.0);
        appendGauge(sb, "argus_gc_pause_time_seconds_max",
                "Maximum GC pause time in seconds",
                analysis.maxPauseTimeMs() / 1000.0);
        appendGauge(sb, "argus_gc_overhead_ratio",
                "GC overhead as a ratio of total time",
                analysis.gcOverheadPercent() / 100.0);
        appendGauge(sb, "argus_heap_used_bytes",
                "Current heap memory used in bytes",
                analysis.currentHeapUsed());
        appendGauge(sb, "argus_heap_committed_bytes",
                "Current heap memory committed in bytes",
                analysis.currentHeapCommitted());
    }

    private void appendCPUMetrics(StringBuilder sb) {
        var analysis = cpuAnalyzer.getAnalysis();
        appendGauge(sb, "argus_cpu_jvm_user_ratio",
                "JVM user CPU usage ratio",
                analysis.currentJvmUser());
        appendGauge(sb, "argus_cpu_jvm_system_ratio",
                "JVM system CPU usage ratio",
                analysis.currentJvmSystem());
        appendGauge(sb, "argus_cpu_machine_total_ratio",
                "Machine total CPU usage ratio",
                analysis.currentMachineTotal());
    }

    private void appendMetaspaceMetrics(StringBuilder sb) {
        var analysis = metaspaceAnalyzer.getAnalysis();
        appendGauge(sb, "argus_metaspace_used_bytes",
                "Metaspace memory used in bytes",
                analysis.currentUsed());
        appendGauge(sb, "argus_metaspace_committed_bytes",
                "Metaspace memory committed in bytes",
                analysis.currentCommitted());
        appendGauge(sb, "argus_metaspace_reserved_bytes",
                "Metaspace memory reserved in bytes",
                analysis.currentReserved());
        appendGauge(sb, "argus_metaspace_classes_loaded",
                "Number of loaded classes in metaspace",
                analysis.currentClassCount());
    }

    private void appendContentionMetrics(StringBuilder sb) {
        var analysis = contentionAnalyzer.getAnalysis();
        appendCounter(sb, "argus_contention_events_total",
                "Total number of lock contention events",
                analysis.totalContentionEvents());
        appendCounter(sb, "argus_contention_time_seconds_total",
                "Total lock contention time in seconds",
                analysis.totalContentionTimeMs() / 1000.0);

        var hotspots = analysis.hotspots();
        if (!hotspots.isEmpty()) {
            sb.append("# HELP argus_contention_hotspot_events Contention events per monitor class\n");
            sb.append("# TYPE argus_contention_hotspot_events gauge\n");
            for (var hotspot : hotspots.stream().limit(10).toList()) {
                sb.append("argus_contention_hotspot_events{monitor=\"")
                        .append(escapeLabel(hotspot.monitorClass()))
                        .append("\"} ")
                        .append(hotspot.eventCount())
                        .append('\n');
            }
        }
    }

    private void appendAllocationMetrics(StringBuilder sb) {
        var analysis = allocationAnalyzer.getAnalysis();
        appendCounter(sb, "argus_allocation_total",
                "Total number of object allocations",
                analysis.totalAllocations());
        appendCounter(sb, "argus_allocation_bytes_total",
                "Total bytes allocated",
                analysis.totalBytesAllocated());
        appendGauge(sb, "argus_allocation_rate_bytes_per_second",
                "Current allocation rate in bytes per second",
                analysis.allocationRateBytesPerSec());

        var topClasses = analysis.topAllocatingClasses();
        if (!topClasses.isEmpty()) {
            sb.append("# HELP argus_allocation_class_bytes Bytes allocated by class\n");
            sb.append("# TYPE argus_allocation_class_bytes gauge\n");
            for (var classAlloc : topClasses.stream().limit(10).toList()) {
                sb.append("argus_allocation_class_bytes{class=\"")
                        .append(escapeLabel(classAlloc.className()))
                        .append("\"} ")
                        .append(classAlloc.totalBytes())
                        .append('\n');
            }
        }
    }

    private void appendProfilingMetrics(StringBuilder sb) {
        var analysis = methodProfilingAnalyzer.getAnalysis();
        appendCounter(sb, "argus_profiling_samples_total",
                "Total number of execution sample events",
                analysis.totalSamples());

        var topMethods = analysis.topMethods();
        if (!topMethods.isEmpty()) {
            sb.append("# HELP argus_profiling_method_samples Execution samples per method\n");
            sb.append("# TYPE argus_profiling_method_samples gauge\n");
            for (var method : topMethods.stream().limit(20).toList()) {
                sb.append("argus_profiling_method_samples{class=\"")
                        .append(escapeLabel(method.className()))
                        .append("\",method=\"")
                        .append(escapeLabel(method.methodName()))
                        .append("\"} ")
                        .append(method.sampleCount())
                        .append('\n');
            }
        }
    }

    private void appendBuildInfo(StringBuilder sb) {
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) {
            version = "dev";
        }
        String jdkVersion = System.getProperty("java.version", "unknown");

        sb.append("# HELP argus_build_info Argus build information\n");
        sb.append("# TYPE argus_build_info gauge\n");
        sb.append("argus_build_info{version=\"").append(escapeLabel(version))
                .append("\",jdk_version=\"").append(escapeLabel(jdkVersion))
                .append("\"} 1\n");
    }

    private void appendGauge(StringBuilder sb, String name, String help, double value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ');
        if (value == (long) value) {
            sb.append((long) value);
        } else {
            sb.append(value);
        }
        sb.append('\n');
    }

    private void appendCounter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private void appendCounter(StringBuilder sb, String name, String help, double value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private String escapeLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
