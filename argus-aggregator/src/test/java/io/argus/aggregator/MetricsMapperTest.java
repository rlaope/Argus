package io.argus.aggregator;

import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.scrape.MetricsMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link MetricsMapper} never conflates bytes-valued or
 * ratio-valued series into a 0–100 percent field. Regression for the bug
 * where {@code jvm_memory_used_bytes} (billions) was being interpreted as
 * a heap percent, making every tile RED.
 */
class MetricsMapperTest {

    @Test
    void doesNotUseBytesAsHeapPercent() {
        TileMetrics m = MetricsMapper.map(Map.of("jvm_memory_used_bytes", 12_345_678_900.0));
        assertNull(m.heapPercent(),
                "bytes-valued metric must not be reported as heap percent");
    }

    @Test
    void picksArgusHeapPercent() {
        TileMetrics m = MetricsMapper.map(Map.of("argus_heap_used_percent", 65.0));
        assertEquals(65.0, m.heapPercent(), 0.0001);
    }

    @Test
    void picksGenericHeapPercent() {
        TileMetrics m = MetricsMapper.map(Map.of("heap_used_percent", 42.5));
        assertEquals(42.5, m.heapPercent(), 0.0001);
    }

    @Test
    void cpuRatioGetsScaledToPercent() {
        TileMetrics m = MetricsMapper.map(Map.of("process_cpu_usage", 0.37));
        assertNotNull(m.cpuPercent());
        assertEquals(37.0, m.cpuPercent(), 0.0001);
    }

    @Test
    void argusCpuPercentPreferredOverRatio() {
        TileMetrics m = MetricsMapper.map(Map.of(
                "argus_cpu_process_percent", 78.0,
                "process_cpu_usage", 0.5));
        assertEquals(78.0, m.cpuPercent(), 0.0001);
    }

    @Test
    void leakFlagFromAlias() {
        TileMetrics m = MetricsMapper.map(Map.of("memory_leak_suspected", 1.0));
        assertTrue(m.leakSuspected());
    }

    @Test
    void emptyInputProducesEmptyMetrics() {
        TileMetrics m = MetricsMapper.map(Map.of());
        assertNull(m.heapPercent());
        assertNull(m.gcOverheadPercent());
        assertNull(m.cpuPercent());
        assertEquals(0, m.activeVThreads());
        assertFalse(m.leakSuspected());
    }
}
