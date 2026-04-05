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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ArgusMeterBinder}.
 */
class ArgusMeterBinderTest {

    private MeterRegistry registry;
    private ServerMetrics serverMetrics;
    private ActiveThreadsRegistry activeThreads;
    private AgentConfig config;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        serverMetrics = new ServerMetrics();
        activeThreads = new ActiveThreadsRegistry();
        config = AgentConfig.builder()
                .gcEnabled(true)
                .cpuEnabled(true)
                .metaspaceEnabled(true)
                .allocationEnabled(true)
                .contentionEnabled(true)
                .profilingEnabled(true)
                .build();
    }

    @Test
    void bindsVirtualThreadMetrics() {
        ArgusMeterBinder binder = new ArgusMeterBinder(
                serverMetrics, activeThreads,
                new PinningAnalyzer(), new CarrierThreadAnalyzer(),
                new GCAnalyzer(), new CPUAnalyzer(),
                new AllocationAnalyzer(), new MetaspaceAnalyzer(),
                new MethodProfilingAnalyzer(), new ContentionAnalyzer(),
                config);

        binder.bindTo(registry);

        assertNotNull(registry.find("argus.virtual.threads.started").functionCounter());
        assertNotNull(registry.find("argus.virtual.threads.ended").functionCounter());
        assertNotNull(registry.find("argus.virtual.threads.active").gauge());
        assertNotNull(registry.find("argus.virtual.threads.submit.failed").functionCounter());
        assertNotNull(registry.find("argus.virtual.threads.pinned").functionCounter());
        assertNotNull(registry.find("argus.virtual.threads.pinned.unique.stacks").gauge());
        assertNotNull(registry.find("argus.carrier.threads.total").gauge());
    }

    @Test
    void bindsGCMetrics() {
        ArgusMeterBinder binder = new ArgusMeterBinder(
                serverMetrics, activeThreads,
                new PinningAnalyzer(), new CarrierThreadAnalyzer(),
                new GCAnalyzer(), new CPUAnalyzer(),
                null, null, null, null,
                config);

        binder.bindTo(registry);

        assertNotNull(registry.find("argus.gc.events").functionCounter());
        assertNotNull(registry.find("argus.gc.pause.time.seconds").gauge());
        assertNotNull(registry.find("argus.gc.pause.time.max.seconds").gauge());
        assertNotNull(registry.find("argus.gc.overhead.ratio").gauge());
        assertNotNull(registry.find("argus.heap.used.bytes").gauge());
        assertNotNull(registry.find("argus.heap.committed.bytes").gauge());
    }

    @Test
    void bindsCPUMetrics() {
        ArgusMeterBinder binder = new ArgusMeterBinder(
                serverMetrics, activeThreads,
                new PinningAnalyzer(), new CarrierThreadAnalyzer(),
                new GCAnalyzer(), new CPUAnalyzer(),
                null, null, null, null,
                config);

        binder.bindTo(registry);

        assertNotNull(registry.find("argus.cpu.jvm.user.ratio").gauge());
        assertNotNull(registry.find("argus.cpu.jvm.system.ratio").gauge());
        assertNotNull(registry.find("argus.cpu.machine.total.ratio").gauge());
    }

    @Test
    void bindsAllMetricsWhenAllEnabled() {
        ArgusMeterBinder binder = new ArgusMeterBinder(
                serverMetrics, activeThreads,
                new PinningAnalyzer(), new CarrierThreadAnalyzer(),
                new GCAnalyzer(), new CPUAnalyzer(),
                new AllocationAnalyzer(), new MetaspaceAnalyzer(),
                new MethodProfilingAnalyzer(), new ContentionAnalyzer(),
                config);

        binder.bindTo(registry);

        // Count all registered meters
        List<Meter> meters = registry.getMeters();
        assertTrue(meters.size() >= 25, "Expected at least 25 meters, got " + meters.size());
    }

    @Test
    void skipsDisabledCategories() {
        AgentConfig disabledConfig = AgentConfig.builder()
                .gcEnabled(false)
                .cpuEnabled(false)
                .metaspaceEnabled(false)
                .allocationEnabled(false)
                .contentionEnabled(false)
                .profilingEnabled(false)
                .build();

        ArgusMeterBinder binder = new ArgusMeterBinder(
                serverMetrics, activeThreads,
                new PinningAnalyzer(), new CarrierThreadAnalyzer(),
                new GCAnalyzer(), new CPUAnalyzer(),
                null, null, null, null,
                disabledConfig);

        binder.bindTo(registry);

        // Virtual thread metrics always present
        assertNotNull(registry.find("argus.virtual.threads.active").gauge());

        // GC/CPU/etc should not be present
        assertNull(registry.find("argus.gc.events").functionCounter());
        assertNull(registry.find("argus.cpu.jvm.user.ratio").gauge());
        assertNull(registry.find("argus.metaspace.used.bytes").gauge());
    }

    @Test
    void metricsReflectLiveData() {
        ArgusMeterBinder binder = new ArgusMeterBinder(
                serverMetrics, activeThreads,
                new PinningAnalyzer(), new CarrierThreadAnalyzer(),
                new GCAnalyzer(), new CPUAnalyzer(),
                null, null, null, null,
                config);

        binder.bindTo(registry);

        // Initial values should be 0
        Gauge activeGauge = registry.find("argus.virtual.threads.active").gauge();
        assertNotNull(activeGauge);
        assertEquals(0.0, activeGauge.value());

        // Increment server metrics
        serverMetrics.incrementStart();
        serverMetrics.incrementStart();
        serverMetrics.incrementStart();

        double startCount = registry.find("argus.virtual.threads.started").functionCounter().count();
        assertEquals(3.0, startCount);
    }

    @Test
    void respectsMetricsConfig() {
        ArgusMetricsConfig metricsConfig = ArgusMetricsConfig.builder()
                .gcMetrics(false)
                .cpuMetrics(true)
                .build();

        ArgusMeterBinder binder = new ArgusMeterBinder(
                serverMetrics, activeThreads,
                new PinningAnalyzer(), new CarrierThreadAnalyzer(),
                new GCAnalyzer(), new CPUAnalyzer(),
                null, null, null, null,
                config, metricsConfig);

        binder.bindTo(registry);

        // GC disabled via metricsConfig
        assertNull(registry.find("argus.gc.events").functionCounter());

        // CPU still enabled
        assertNotNull(registry.find("argus.cpu.jvm.user.ratio").gauge());
    }
}
