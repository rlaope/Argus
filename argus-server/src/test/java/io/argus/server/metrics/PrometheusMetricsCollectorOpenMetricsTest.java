package io.argus.server.metrics;

import io.argus.core.config.AgentConfig;
import io.argus.core.event.GCEvent;
import io.argus.server.analysis.CPUAnalyzer;
import io.argus.server.analysis.CarrierThreadAnalyzer;
import io.argus.server.analysis.GCAnalyzer;
import io.argus.server.analysis.PinningAnalyzer;
import io.argus.server.state.ActiveThreadsRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the OpenMetrics / histogram additions to the Prometheus exporter:
 * the GC pause histogram structure, the {@code # EOF} marker in OpenMetrics
 * mode (and its absence in classic mode), and exemplar plumbing.
 *
 * <p>Also dumps a sample exposition to {@code build/openmetrics-sample.txt} so
 * it can be validated externally with {@code promtool check metrics}.
 */
class PrometheusMetricsCollectorOpenMetricsTest {

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
        for (double s : new double[]{0.008, 0.04, 0.2, 1.5}) {
            gc.recordGCEvent(GCEvent.pause(Instant.now(),
                    (long) (s * 1_000_000_000L), "G1 Young Generation", "G1 Evacuation Pause"));
        }
        return gc;
    }

    @Test
    void openmetrics_has_eof_marker_classic_does_not() {
        PrometheusMetricsCollector c = collector(gcWithPauses());
        String om = c.collectMetrics(true);
        String classic = c.collectMetrics(false);
        assertTrue(om.endsWith("# EOF\n"), "OpenMetrics output must end with # EOF");
        assertFalse(classic.contains("# EOF"), "classic 0.0.4 must not contain # EOF");
    }

    @Test
    void histogram_emitted_with_buckets_sum_count() {
        String out = collector(gcWithPauses()).collectMetrics(false);
        assertTrue(out.contains("# TYPE argus_gc_pause_seconds histogram"));
        assertTrue(out.contains("argus_gc_pause_seconds_bucket{le=\"+Inf\"}"),
                "must emit the +Inf bucket");
        assertTrue(out.contains("argus_gc_pause_seconds_sum"));
        assertTrue(out.contains("argus_gc_pause_seconds_count"));
    }

    @Test
    void histogram_buckets_are_cumulative_non_decreasing() {
        String out = collector(gcWithPauses()).collectMetrics(false);
        long prev = -1;
        for (String line : out.split("\n")) {
            if (!line.startsWith("argus_gc_pause_seconds_bucket")) continue;
            long v = Long.parseLong(line.substring(line.lastIndexOf(' ') + 1).trim());
            assertTrue(v >= prev, "bucket counts must be non-decreasing: " + line);
            prev = v;
        }
        assertTrue(prev > 0, "expected at least one observation in the +Inf bucket");
    }

    @Test
    void exemplar_present_only_when_trace_id_supplied_and_openmetrics() {
        PrometheusMetricsCollector c = collector(gcWithPauses());
        // no supplier → no exemplar even in OpenMetrics mode
        assertFalse(c.collectMetrics(true).contains("# {trace_id="),
                "no exemplar without a trace id");
        // supplier present → exemplar on +Inf bucket in OpenMetrics mode only
        c.setTraceIdSupplier(() -> "abc123def456");
        assertTrue(c.collectMetrics(true).contains("# {trace_id=\"abc123def456\"}"),
                "exemplar must appear in OpenMetrics mode when trace id is present");
        assertFalse(c.collectMetrics(false).contains("# {trace_id="),
                "exemplars must not appear in classic 0.0.4 output");
    }

    @Test
    void dumps_sample_for_promtool() throws Exception {
        String om = collector(gcWithPauses()).collectMetrics(true);
        Path out = Path.of("build", "openmetrics-sample.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, om);
        assertTrue(Files.size(out) > 0);
    }
}
