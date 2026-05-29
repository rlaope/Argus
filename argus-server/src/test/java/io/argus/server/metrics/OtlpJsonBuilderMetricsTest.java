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
    void otlp_dual_emits_legacy_argus_series_by_default() {
        // legacyNames defaults to true → both the semconv and the legacy series ship.
        String json = builder(AgentConfig.defaults()).build();

        assertTrue(json.contains("\"name\":\"jvm.memory.used\""), "semconv jvm.memory.used must be present");
        assertTrue(json.contains("\"name\":\"argus_heap_used_bytes\""),
                "legacy argus_heap_used_bytes must ship when legacyNames=true (back-compat)");
        assertTrue(json.contains("\"name\":\"argus_metaspace_used_bytes\""),
                "legacy argus_metaspace_used_bytes must ship when legacyNames=true");
        assertTrue(json.contains("\"name\":\"argus_cpu_jvm_user_ratio\""),
                "legacy argus_cpu_jvm_user_ratio must ship when legacyNames=true");
    }

    @Test
    void otlp_drops_legacy_argus_duplicates_when_legacy_names_disabled() {
        // legacyNames=false → the OTLP stream must NOT double every series with the
        // argus_* duplicate, matching the Prometheus collector's gating.
        AgentConfig noLegacy = AgentConfig.builder().legacyMetricNames(false).build();
        String json = builder(noLegacy).build();

        // Standard semconv series remain.
        assertTrue(json.contains("\"name\":\"jvm.memory.used\""),
                "semconv jvm.memory.used must remain when legacyNames=false");
        // Legacy duplicates of semconv series must be gone.
        assertFalse(json.contains("\"name\":\"argus_heap_used_bytes\""),
                "argus_heap_used_bytes must be gated off when legacyNames=false");
        assertFalse(json.contains("\"name\":\"argus_heap_committed_bytes\""),
                "argus_heap_committed_bytes must be gated off when legacyNames=false");
        assertFalse(json.contains("\"name\":\"argus_metaspace_used_bytes\""),
                "argus_metaspace_used_bytes must be gated off when legacyNames=false");
        assertFalse(json.contains("\"name\":\"argus_cpu_jvm_user_ratio\""),
                "argus_cpu_jvm_user_ratio must be gated off when legacyNames=false");
        assertFalse(json.contains("\"name\":\"argus_virtual_threads_active\""),
                "argus_virtual_threads_active must be gated off when legacyNames=false");

        // Argus-unique series (no semconv equivalent) are NOT renamed/dropped.
        assertTrue(json.contains("\"name\":\"argus_virtual_threads_started_total\""),
                "argus-unique series must always ship regardless of legacyNames");
        assertTrue(json.contains("\"name\":\"argus_gc_events_total\""),
                "argus-unique GC counter must always ship regardless of legacyNames");
    }

    @Test
    void otlp_emits_argus_unique_diagnostics_regardless_of_legacy_flag() {
        // These series have no semconv equivalent, so they must ship on the OTLP path
        // in parity with the Prometheus collector — and must NOT be gated by legacyNames.
        AgentConfig noLegacy = AgentConfig.builder().legacyMetricNames(false).build();
        String json = builder(noLegacy).build();

        for (String name : new String[]{
                "argus_gc_pause_time_seconds_avg",
                "argus_gc_overhead_warning",
                "argus_gc_allocation_rate_kbps",
                "argus_gc_promotion_rate_kbps",
                "argus_gc_leak_suspected",
                "argus_gc_leak_confidence",
                "argus_heap_usage_ratio",
                "argus_metaspace_reserved_bytes",
                "argus_carrier_threads_virtual_handled_total"}) {
            assertTrue(json.contains("\"name\":\"" + name + "\""),
                    "argus-unique series " + name + " must ship over OTLP even when legacyNames=false");
        }
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
