package io.argus.micrometer;

import io.argus.core.config.AgentConfig;
import io.argus.server.analysis.AllocationAnalyzer;
import io.argus.server.analysis.CarrierThreadAnalyzer;
import io.argus.server.analysis.ContentionAnalyzer;
import io.argus.server.analysis.CPUAnalyzer;
import io.argus.server.analysis.GCAnalyzer;
import io.argus.server.analysis.MetaspaceAnalyzer;
import io.argus.server.analysis.MethodProfilingAnalyzer;
import io.argus.server.analysis.PinningAnalyzer;
import io.argus.server.metrics.ServerMetrics;
import io.argus.server.state.ActiveThreadsRegistry;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Micrometer {@link MeterBinder} that exposes Argus JVM diagnostic metrics.
 *
 * <p>This binder reads from Argus analyzers (zero additional overhead) and registers
 * standard Micrometer meters. It is framework-agnostic and works with any
 * {@link MeterRegistry} — Spring Boot, Quarkus, Micronaut, or standalone.
 *
 * <p>Metrics cover virtual threads, GC, CPU, heap, metaspace, allocation,
 * lock contention, and method profiling — the full Argus observability surface.
 *
 * @see ArgusMetricsConfig
 */
public final class ArgusMeterBinder implements MeterBinder {

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
    private final AgentConfig agentConfig;
    private final ArgusMetricsConfig metricsConfig;

    public ArgusMeterBinder(ServerMetrics serverMetrics,
                            ActiveThreadsRegistry activeThreads,
                            PinningAnalyzer pinningAnalyzer,
                            CarrierThreadAnalyzer carrierAnalyzer,
                            GCAnalyzer gcAnalyzer,
                            CPUAnalyzer cpuAnalyzer,
                            AllocationAnalyzer allocationAnalyzer,
                            MetaspaceAnalyzer metaspaceAnalyzer,
                            MethodProfilingAnalyzer methodProfilingAnalyzer,
                            ContentionAnalyzer contentionAnalyzer,
                            AgentConfig agentConfig,
                            ArgusMetricsConfig metricsConfig) {
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
        this.agentConfig = agentConfig;
        this.metricsConfig = metricsConfig != null ? metricsConfig : ArgusMetricsConfig.defaults();
    }

    public ArgusMeterBinder(ServerMetrics serverMetrics,
                            ActiveThreadsRegistry activeThreads,
                            PinningAnalyzer pinningAnalyzer,
                            CarrierThreadAnalyzer carrierAnalyzer,
                            GCAnalyzer gcAnalyzer,
                            CPUAnalyzer cpuAnalyzer,
                            AllocationAnalyzer allocationAnalyzer,
                            MetaspaceAnalyzer metaspaceAnalyzer,
                            MethodProfilingAnalyzer methodProfilingAnalyzer,
                            ContentionAnalyzer contentionAnalyzer,
                            AgentConfig agentConfig) {
        this(serverMetrics, activeThreads, pinningAnalyzer, carrierAnalyzer,
                gcAnalyzer, cpuAnalyzer, allocationAnalyzer, metaspaceAnalyzer,
                methodProfilingAnalyzer, contentionAnalyzer, agentConfig, null);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (metricsConfig.isVirtualThreadMetrics()) {
            bindVirtualThreadMetrics(registry);
        }
        if (metricsConfig.isGcMetrics() && agentConfig.isGcEnabled()) {
            bindGCMetrics(registry);
        }
        if (metricsConfig.isCpuMetrics() && agentConfig.isCpuEnabled()) {
            bindCPUMetrics(registry);
        }
        if (metricsConfig.isMetaspaceMetrics() && agentConfig.isMetaspaceEnabled() && metaspaceAnalyzer != null) {
            bindMetaspaceMetrics(registry);
        }
        if (metricsConfig.isContentionMetrics() && agentConfig.isContentionEnabled() && contentionAnalyzer != null) {
            bindContentionMetrics(registry);
        }
        if (metricsConfig.isAllocationMetrics() && agentConfig.isAllocationEnabled() && allocationAnalyzer != null) {
            bindAllocationMetrics(registry);
        }
        if (metricsConfig.isProfilingMetrics() && agentConfig.isProfilingEnabled() && methodProfilingAnalyzer != null) {
            bindProfilingMetrics(registry);
        }
    }

    private void bindVirtualThreadMetrics(MeterRegistry registry) {
        FunctionCounter.builder("argus.virtual.threads.started", serverMetrics,
                        m -> (double) m.getStartEvents())
                .description("Total number of virtual threads started")
                .register(registry);

        FunctionCounter.builder("argus.virtual.threads.ended", serverMetrics,
                        m -> (double) m.getEndEvents())
                .description("Total number of virtual threads ended")
                .register(registry);

        Gauge.builder("argus.virtual.threads.active", activeThreads, ActiveThreadsRegistry::size)
                .description("Number of currently active virtual threads")
                .register(registry);

        FunctionCounter.builder("argus.virtual.threads.submit.failed", serverMetrics,
                        m -> (double) m.getSubmitFailedEvents())
                .description("Total number of virtual thread submit failures")
                .register(registry);

        // Pinning metrics (always available with virtual threads)
        FunctionCounter.builder("argus.virtual.threads.pinned", pinningAnalyzer,
                        a -> (double) a.getAnalysis().totalPinnedEvents())
                .description("Total number of virtual thread pinning events")
                .register(registry);

        Gauge.builder("argus.virtual.threads.pinned.unique.stacks", pinningAnalyzer,
                        a -> a.getAnalysis().uniqueStackTraces())
                .description("Number of unique pinning stack traces")
                .register(registry);

        // Carrier thread metrics (always available with virtual threads)
        Gauge.builder("argus.carrier.threads.total", carrierAnalyzer,
                        a -> a.getAnalysis().totalCarriers())
                .description("Total number of carrier threads")
                .register(registry);

        FunctionCounter.builder("argus.carrier.threads.virtual.handled", carrierAnalyzer,
                        a -> (double) a.getAnalysis().totalVirtualThreadsHandled())
                .description("Total virtual threads handled by carrier threads")
                .register(registry);

        Gauge.builder("argus.carrier.threads.avg.per.carrier", carrierAnalyzer,
                        a -> a.getAnalysis().avgVirtualThreadsPerCarrier())
                .description("Average virtual threads per carrier thread")
                .register(registry);
    }

    private void bindGCMetrics(MeterRegistry registry) {
        FunctionCounter.builder("argus.gc.events", gcAnalyzer,
                        a -> (double) a.getAnalysis().totalGCEvents())
                .description("Total number of GC events")
                .register(registry);

        Gauge.builder("argus.gc.pause.time.seconds", gcAnalyzer,
                        a -> a.getAnalysis().totalPauseTimeMs() / 1000.0)
                .description("Total GC pause time in seconds")
                .register(registry);

        Gauge.builder("argus.gc.pause.time.max.seconds", gcAnalyzer,
                        a -> a.getAnalysis().maxPauseTimeMs() / 1000.0)
                .description("Maximum GC pause time in seconds")
                .register(registry);

        Gauge.builder("argus.gc.overhead.ratio", gcAnalyzer,
                        a -> a.getAnalysis().gcOverheadPercent() / 100.0)
                .description("GC overhead as a ratio of total time")
                .register(registry);

        Gauge.builder("argus.heap.used.bytes", gcAnalyzer,
                        a -> a.getAnalysis().currentHeapUsed())
                .description("Current heap memory used in bytes")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("argus.heap.committed.bytes", gcAnalyzer,
                        a -> a.getAnalysis().currentHeapCommitted())
                .description("Current heap memory committed in bytes")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("argus.gc.pause.time.avg.seconds", gcAnalyzer,
                        a -> a.getAnalysis().avgPauseTimeMs() / 1000.0)
                .description("Average GC pause time in seconds")
                .register(registry);

        Gauge.builder("argus.gc.overhead.warning", gcAnalyzer,
                        a -> a.getAnalysis().isOverheadWarning() ? 1.0 : 0.0)
                .description("GC overhead warning flag (1 = overhead > 10%)")
                .register(registry);

        Gauge.builder("argus.gc.allocation.rate.kbps", gcAnalyzer,
                        a -> a.getAnalysis().allocationRateKBPerSec())
                .description("Allocation rate in KB/s computed from recent GC events")
                .register(registry);

        Gauge.builder("argus.gc.promotion.rate.kbps", gcAnalyzer,
                        a -> a.getAnalysis().promotionRateKBPerSec())
                .description("Promotion rate (young -> old gen) in KB/s")
                .register(registry);

        Gauge.builder("argus.gc.leak.suspected", gcAnalyzer,
                        a -> a.getAnalysis().leakSuspected() ? 1.0 : 0.0)
                .description("Memory leak suspected flag (1 = leak detected via linear regression)")
                .register(registry);

        Gauge.builder("argus.gc.leak.confidence", gcAnalyzer,
                        a -> a.getAnalysis().leakConfidencePercent() / 100.0)
                .description("Memory leak detection confidence (R² value, 0-1)")
                .register(registry);
    }

    private void bindCPUMetrics(MeterRegistry registry) {
        Gauge.builder("argus.cpu.jvm.user.ratio", cpuAnalyzer,
                        a -> a.getAnalysis().currentJvmUser())
                .description("JVM user CPU usage ratio")
                .register(registry);

        Gauge.builder("argus.cpu.jvm.system.ratio", cpuAnalyzer,
                        a -> a.getAnalysis().currentJvmSystem())
                .description("JVM system CPU usage ratio")
                .register(registry);

        Gauge.builder("argus.cpu.machine.total.ratio", cpuAnalyzer,
                        a -> a.getAnalysis().currentMachineTotal())
                .description("Machine total CPU usage ratio")
                .register(registry);
    }

    private void bindMetaspaceMetrics(MeterRegistry registry) {
        Gauge.builder("argus.metaspace.used.bytes", metaspaceAnalyzer,
                        a -> a.getAnalysis().currentUsed())
                .description("Current metaspace used in bytes")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("argus.metaspace.committed.bytes", metaspaceAnalyzer,
                        a -> a.getAnalysis().currentCommitted())
                .description("Current metaspace committed in bytes")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("argus.metaspace.reserved.bytes", metaspaceAnalyzer,
                        a -> a.getAnalysis().currentReserved())
                .description("Current metaspace reserved in bytes")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("argus.metaspace.classes.loaded", metaspaceAnalyzer,
                        a -> a.getAnalysis().currentClassCount())
                .description("Number of classes loaded in metaspace")
                .register(registry);
    }

    private void bindContentionMetrics(MeterRegistry registry) {
        FunctionCounter.builder("argus.contention.events", contentionAnalyzer,
                        a -> (double) a.getAnalysis().totalContentionEvents())
                .description("Total number of lock contention events")
                .register(registry);

        Gauge.builder("argus.contention.time.seconds", contentionAnalyzer,
                        a -> a.getAnalysis().totalContentionTimeMs() / 1000.0)
                .description("Total lock contention time in seconds")
                .register(registry);
    }

    private void bindAllocationMetrics(MeterRegistry registry) {
        FunctionCounter.builder("argus.allocation.total", allocationAnalyzer,
                        a -> (double) a.getAnalysis().totalAllocations())
                .description("Total number of object allocations")
                .register(registry);

        FunctionCounter.builder("argus.allocation.bytes", allocationAnalyzer,
                        a -> (double) a.getAnalysis().totalBytesAllocated())
                .description("Total bytes allocated")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("argus.allocation.rate.bytes.per.second", allocationAnalyzer,
                        a -> a.getAnalysis().allocationRateBytesPerSec())
                .description("Current allocation rate in bytes per second")
                .baseUnit("bytes")
                .register(registry);
    }

    private void bindProfilingMetrics(MeterRegistry registry) {
        FunctionCounter.builder("argus.profiling.samples", methodProfilingAnalyzer,
                        a -> (double) a.getAnalysis().totalSamples())
                .description("Total number of CPU profiling samples")
                .register(registry);
    }
}
