package io.argus.cli.cluster;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrometheusTextParserTest {

    private static final String SAMPLE = """
            # HELP argus_heap_used_percent Heap used percent
            # TYPE argus_heap_used_percent gauge
            argus_heap_used_percent 56.3
            # HELP argus_gc_overhead_percent GC overhead percent
            # TYPE argus_gc_overhead_percent gauge
            argus_gc_overhead_percent 2.1
            # HELP argus_cpu_process_percent Process CPU percent
            # TYPE argus_cpu_process_percent gauge
            argus_cpu_process_percent 12.0
            # HELP argus_virtual_threads_active Active virtual threads
            # TYPE argus_virtual_threads_active gauge
            argus_virtual_threads_active 1234.0
            # HELP argus_memory_leak_suspected Memory leak flag
            # TYPE argus_memory_leak_suspected gauge
            argus_memory_leak_suspected 0.0
            """;

    private static final String SAMPLE_WITH_LABELS = """
            # HELP jvm_memory_used_bytes Memory used
            # TYPE jvm_memory_used_bytes gauge
            jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 12345678.0
            jvm_gc_overhead_percent{gc="G1"} 8.3
            process_cpu_usage 0.67
            """;

    @Test
    void parsesBasicMetrics() {
        Map<String, Double> m = PrometheusTextParser.parse(SAMPLE);
        assertEquals(56.3, m.get("argus_heap_used_percent"), 0.001);
        assertEquals(2.1,  m.get("argus_gc_overhead_percent"), 0.001);
        assertEquals(12.0, m.get("argus_cpu_process_percent"), 0.001);
        assertEquals(1234.0, m.get("argus_virtual_threads_active"), 0.001);
        assertEquals(0.0, m.get("argus_memory_leak_suspected"), 0.001);
    }

    @Test
    void skipsCommentLines() {
        Map<String, Double> m = PrometheusTextParser.parse(SAMPLE);
        assertFalse(m.containsKey("# HELP argus_heap_used_percent Heap used percent"));
        assertFalse(m.containsKey("# TYPE argus_heap_used_percent gauge"));
    }

    @Test
    void parsesMetricsWithLabels() {
        Map<String, Double> m = PrometheusTextParser.parse(SAMPLE_WITH_LABELS);
        assertTrue(m.containsKey("jvm_memory_used_bytes"));
        assertTrue(m.containsKey("jvm_gc_overhead_percent"));
        assertEquals(8.3, m.get("jvm_gc_overhead_percent"), 0.001);
        assertEquals(0.67, m.get("process_cpu_usage"), 0.001);
    }

    @Test
    void returnsEmptyMapForNull() {
        Map<String, Double> m = PrometheusTextParser.parse(null);
        assertTrue(m.isEmpty());
    }

    @Test
    void returnsEmptyMapForEmptyString() {
        Map<String, Double> m = PrometheusTextParser.parse("");
        assertTrue(m.isEmpty());
    }

    @Test
    void handlesOnlyComments() {
        Map<String, Double> m = PrometheusTextParser.parse("# HELP foo bar\n# TYPE foo gauge\n");
        assertTrue(m.isEmpty());
    }

    @Test
    void extractsMappedToInstanceMetrics() {
        Map<String, Double> raw = PrometheusTextParser.parse(SAMPLE);
        ClusterHealthAggregator.InstanceMetrics metrics =
                ClusterHealthAggregator.extract("localhost:9202", raw);
        assertEquals("localhost:9202", metrics.target());
        assertEquals(56.3, metrics.heapPercent(), 0.001);
        assertEquals(2.1,  metrics.gcOverhead(),  0.001);
        assertEquals(12.0, metrics.cpuPercent(),  0.001);
        assertFalse(metrics.leakSuspected());
        assertEquals(1234L, metrics.activeVThreads());
        assertTrue(metrics.reachable());
    }

    @Test
    void aggregateComputesRanges() {
        Map<String, Double> raw1 = PrometheusTextParser.parse(SAMPLE);
        Map<String, Double> raw2 = Map.of(
                "argus_heap_used_percent", 89.0,
                "argus_gc_overhead_percent", 8.3,
                "argus_cpu_process_percent", 67.0,
                "argus_virtual_threads_active", 2456.0,
                "argus_memory_leak_suspected", 1.0
        );
        ClusterHealthAggregator.InstanceMetrics m1 =
                ClusterHealthAggregator.extract("localhost:9202", raw1);
        ClusterHealthAggregator.InstanceMetrics m2 =
                ClusterHealthAggregator.extract("localhost:9203", raw2);

        ClusterHealthAggregator.AggregateStats stats =
                ClusterHealthAggregator.aggregate(java.util.List.of(m1, m2));

        assertEquals(56.3, stats.heapMin(), 0.1);
        assertEquals(89.0, stats.heapMax(), 0.1);
        assertEquals(2.1,  stats.gcMin(),   0.1);
        assertEquals(8.3,  stats.gcMax(),   0.1);
        assertEquals(3690L, stats.vtTotal());
        assertEquals(1, stats.leakCount());
        assertEquals("localhost:9203", stats.worstTarget());
    }
}
