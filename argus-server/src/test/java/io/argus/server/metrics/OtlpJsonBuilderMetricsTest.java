package io.argus.server.metrics;

import io.argus.core.config.AgentConfig;
import io.argus.core.event.EventType;
import io.argus.core.event.GCEvent;
import io.argus.server.analysis.CPUAnalyzer;
import io.argus.server.analysis.CarrierThreadAnalyzer;
import io.argus.server.analysis.GCAnalyzer;
import io.argus.server.analysis.MetaspaceAnalyzer;
import io.argus.server.analysis.PinningAnalyzer;
import io.argus.server.state.ActiveThreadsRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that the OTLP JSON exporter (workstream W1) emits the standard OTel
 * JVM semantic-convention metrics with the attributes {@link SemconvMetrics}
 * declares: memory data points carry the {@code jvm.memory.pool.name} attribute,
 * the Metaspace pool memory series is present, and {@code jvm.gc.duration} is
 * emitted as an OTLP Histogram data point from the aggregate pause histogram.
 */
class OtlpJsonBuilderMetricsTest {

    private OtlpJsonBuilder builder(AgentConfig config) {
        GCAnalyzer gc = new GCAnalyzer();
        for (double s : new double[]{0.008, 0.04, 0.2, 1.5}) {
            gc.recordGCEvent(new GCEvent(
                    EventType.GC_PAUSE, Instant.now(),
                    (long) (s * 1_000_000_000L), "G1 Young Generation", "G1 Evacuation Pause",
                    200L * 1024 * 1024, 80L * 1024 * 1024, 512L * 1024 * 1024));
        }
        MetaspaceAnalyzer metaspace = new MetaspaceAnalyzer();
        return new OtlpJsonBuilder(
                config,
                new ServerMetrics(),
                new ActiveThreadsRegistry(),
                new PinningAnalyzer(),
                new CarrierThreadAnalyzer(),
                gc,
                new CPUAnalyzer(),
                null,
                metaspace,
                null, null);
    }

    @Test
    void otlp_heap_memory_data_points_carry_pool_attribute() {
        String json = builder(AgentConfig.defaults()).build();

        // jvm.memory.used is present and its data point carries jvm.memory.pool.name.
        assertTrue(json.contains("\"name\":\"jvm.memory.used\""), "jvm.memory.used missing from OTLP");
        assertTrue(json.contains("\"jvm.memory.pool.name\""),
                "OTLP memory data point missing jvm.memory.pool.name attribute");
        assertTrue(json.contains("\"stringValue\":\"heap\""),
                "OTLP heap memory data point missing pool=heap attribute");
        assertTrue(json.contains("\"name\":\"jvm.memory.committed\""), "jvm.memory.committed missing");
        assertTrue(json.contains("\"name\":\"jvm.memory.used_after_last_gc\""),
                "jvm.memory.used_after_last_gc missing");
    }

    @Test
    void otlp_emits_metaspace_memory_pool_series() {
        String json = builder(AgentConfig.defaults()).build();

        assertTrue(json.contains("\"stringValue\":\"Metaspace\""),
                "OTLP missing jvm.memory.pool.name=Metaspace series");
    }

    @Test
    void otlp_omits_metaspace_pool_when_disabled() {
        AgentConfig noMeta = AgentConfig.builder().metaspaceEnabled(false).build();
        String json = builder(noMeta).build();

        assertFalse(json.contains("\"stringValue\":\"Metaspace\""),
                "OTLP Metaspace pool series must be gated by metaspace-enabled");
    }

    @Test
    void otlp_emits_gc_duration_histogram() {
        String json = builder(AgentConfig.defaults()).build();

        assertTrue(json.contains("\"name\":\"jvm.gc.duration\""), "jvm.gc.duration missing from OTLP");
        assertTrue(json.contains("\"histogram\":{"), "jvm.gc.duration must be an OTLP Histogram");
        assertTrue(json.contains("\"explicitBounds\":["), "OTLP histogram missing explicitBounds");
        assertTrue(json.contains("\"bucketCounts\":["), "OTLP histogram missing bucketCounts");
        // 4 recorded pauses → count must be 4.
        assertTrue(json.contains("\"count\":\"4\""), "OTLP GC histogram count should be 4");
    }

    @Test
    void otlp_gc_duration_skipped_when_no_pauses() {
        GCAnalyzer gc = new GCAnalyzer(); // no events recorded
        OtlpJsonBuilder b = new OtlpJsonBuilder(
                AgentConfig.defaults(), new ServerMetrics(), new ActiveThreadsRegistry(),
                new PinningAnalyzer(), new CarrierThreadAnalyzer(), gc, new CPUAnalyzer(),
                null, new MetaspaceAnalyzer(), null, null);
        String json = b.build();
        assertFalse(json.contains("\"name\":\"jvm.gc.duration\""),
                "jvm.gc.duration must be skipped when there is no pause data (no fabricated buckets)");
    }
}
