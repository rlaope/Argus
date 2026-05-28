package io.argus.server.metrics;

import io.argus.core.config.AgentConfig;
import io.argus.core.event.GCEvent;
import io.argus.server.analysis.CPUAnalyzer;
import io.argus.server.analysis.CarrierThreadAnalyzer;
import io.argus.server.analysis.GCAnalyzer;
import io.argus.server.analysis.PinningAnalyzer;
import io.argus.server.state.ActiveThreadsRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the W4 wiring of the Phase-1 exemplar plumbing: the GC pause
 * histogram's {@code +Inf} bucket carries a {@code trace_id} exemplar in
 * OpenMetrics mode exactly when the supplier yields an id, and never in classic
 * 0.0.4 output.
 */
class PrometheusExemplarWiringTest {

    private PrometheusMetricsCollector collector(GCAnalyzer gc) {
        return new PrometheusMetricsCollector(
                AgentConfig.defaults(),
                new ServerMetrics(),
                new ActiveThreadsRegistry(),
                new PinningAnalyzer(),
                new CarrierThreadAnalyzer(),
                gc,
                new CPUAnalyzer(),
                null, null, null, null);
    }

    private GCAnalyzer gcWithPauses() {
        GCAnalyzer gc = new GCAnalyzer();
        gc.recordGCEvent(GCEvent.pause(Instant.now(),
                120_000_000L, "G1 Young Generation", "G1 Evacuation Pause"));
        return gc;
    }

    @Test
    void exemplar_populates_when_stub_supplier_returns_id() {
        PrometheusMetricsCollector c = collector(gcWithPauses());
        c.setTraceIdSupplier(() -> "0af7651916cd43dd8448eb211c80319c");
        String om = c.collectMetrics(true);
        assertTrue(om.contains("# {trace_id=\"0af7651916cd43dd8448eb211c80319c\"}"),
                "exemplar must populate when the supplier yields a trace id");
    }

    @Test
    void no_exemplar_when_supplier_returns_null() {
        PrometheusMetricsCollector c = collector(gcWithPauses());
        c.setTraceIdSupplier(() -> null);
        assertFalse(c.collectMetrics(true).contains("# {trace_id="),
                "no exemplar when the supplier yields null (OTel absent)");
    }

    @Test
    void null_supplier_resets_to_noop() {
        PrometheusMetricsCollector c = collector(gcWithPauses());
        c.setTraceIdSupplier(() -> "0af7651916cd43dd8448eb211c80319c");
        c.setTraceIdSupplier(null); // must reset to the no-op default
        assertFalse(c.collectMetrics(true).contains("# {trace_id="),
                "a null supplier must reset to the no-op default");
    }

    @Test
    void supplier_throwing_does_not_break_collection() {
        PrometheusMetricsCollector c = collector(gcWithPauses());
        c.setTraceIdSupplier(() -> { throw new RuntimeException("boom"); });
        assertDoesNotThrow(() -> c.collectMetrics(true));
        assertFalse(c.collectMetrics(true).contains("# {trace_id="),
                "a throwing supplier must degrade to no exemplar");
    }
}
