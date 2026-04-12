package io.argus.server.analysis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcMetricsComputerTest {

    /** Build a GCSummary with epoch-second timestamps for predictable math. */
    private static GCAnalyzer.GCSummary summary(long timestampEpochSec,
                                                 long heapUsedBeforeBytes,
                                                 long heapUsedAfterBytes) {
        return new GCAnalyzer.GCSummary(
                Instant.ofEpochSecond(timestampEpochSec),
                "G1 Young Generation",
                "G1 Evacuation Pause",
                10.0,
                heapUsedBeforeBytes,
                heapUsedAfterBytes,
                heapUsedBeforeBytes - heapUsedAfterBytes
        );
    }

    // ── computeRates ─────────────────────────────────────────────────────────

    @Test
    void emptyList_returnsZeroRates() {
        GcMetricsComputer.RateMetrics m = GcMetricsComputer.computeRates(List.of());
        assertEquals(0.0, m.allocationRateKBPerSec(), 0.001);
        assertEquals(0.0, m.promotionRateKBPerSec(), 0.001);
    }

    @Test
    void singleEvent_returnsZeroRates() {
        GcMetricsComputer.RateMetrics m = GcMetricsComputer.computeRates(
                List.of(summary(0, 2 * 1024 * 1024, 1 * 1024 * 1024))
        );
        assertEquals(0.0, m.allocationRateKBPerSec(), 0.001);
        assertEquals(0.0, m.promotionRateKBPerSec(), 0.001);
    }

    @Test
    void steadyAllocation_computesCorrectRate() {
        // Each second: heapBefore = 2 MB, heapAfter = 1 MB (prev).
        // Allocated per interval = 2 MB - 1 MB = 1 MB = 1024 KB.
        // Rate = 1024 KB / 1 s = 1024 KB/s.
        long oneMB = 1024 * 1024L;
        long twoMB = 2 * oneMB;
        List<GCAnalyzer.GCSummary> summaries = List.of(
                summary(0, twoMB, oneMB),
                summary(1, twoMB, oneMB),
                summary(2, twoMB, oneMB),
                summary(3, twoMB, oneMB)
        );
        GcMetricsComputer.RateMetrics m = GcMetricsComputer.computeRates(summaries);
        // allocated = (2MB - 1MB) / 1s = 1024 KB/s
        assertEquals(1024.0, m.allocationRateKBPerSec(), 5.0);
    }

    @Test
    void promotionDetected_heapAfterIncreasing() {
        // heapAfter grows by 512 KB each second = 512 KB/s promotion rate.
        long base = 1024 * 1024L; // 1 MB
        long step = 512 * 1024L;  // 512 KB
        List<GCAnalyzer.GCSummary> summaries = List.of(
                summary(0, base * 2, base),
                summary(1, base * 2 + step, base + step),
                summary(2, base * 2 + step * 2, base + step * 2),
                summary(3, base * 2 + step * 3, base + step * 3)
        );
        GcMetricsComputer.RateMetrics m = GcMetricsComputer.computeRates(summaries);
        assertTrue(m.promotionRateKBPerSec() > 0,
                "Expected positive promotion rate, got " + m.promotionRateKBPerSec());
        assertEquals(512.0, m.promotionRateKBPerSec(), 10.0);
    }

    @Test
    void stableHeapAfter_zeroPromotionRate() {
        // heapAfter stays constant — no promotion.
        long oneMB = 1024 * 1024L;
        List<GCAnalyzer.GCSummary> summaries = List.of(
                summary(0, 2 * oneMB, oneMB),
                summary(1, 2 * oneMB, oneMB),
                summary(2, 2 * oneMB, oneMB),
                summary(3, 2 * oneMB, oneMB)
        );
        GcMetricsComputer.RateMetrics m = GcMetricsComputer.computeRates(summaries);
        assertEquals(0.0, m.promotionRateKBPerSec(), 0.001);
    }

    // ── detectLeak ───────────────────────────────────────────────────────────

    @Test
    void insufficientEvents_noLeakSuspected() {
        GcMetricsComputer.LeakMetrics m = GcMetricsComputer.detectLeak(List.of(
                summary(0, 2 * 1024 * 1024, 1 * 1024 * 1024),
                summary(1, 2 * 1024 * 1024, 1 * 1024 * 1024)
        ));
        assertFalse(m.leakSuspected());
        assertEquals(0.0, m.confidencePercent(), 0.001);
    }

    @Test
    void risingHeapTrend_leakSuspected() {
        // heapAfter grows linearly: 1 MB, 2 MB, 3 MB, ... → clear upward trend.
        List<GCAnalyzer.GCSummary> summaries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long heapAfter = (i + 1) * 1024 * 1024L;
            summaries.add(summary(i, heapAfter + 512 * 1024L, heapAfter));
        }
        GcMetricsComputer.LeakMetrics m = GcMetricsComputer.detectLeak(summaries);
        assertTrue(m.leakSuspected(), "Expected leak to be suspected for linearly rising heap");
        assertTrue(m.confidencePercent() > 70.0,
                "Expected high confidence, got " + m.confidencePercent());
    }

    @Test
    void stableHeap_noLeakSuspected() {
        // heapAfter oscillates slightly around 1 MB — not a leak.
        List<GCAnalyzer.GCSummary> summaries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long heapAfter = 1024 * 1024L + (i % 3) * 10 * 1024L; // ±30 KB noise
            summaries.add(summary(i, heapAfter + 512 * 1024L, heapAfter));
        }
        GcMetricsComputer.LeakMetrics m = GcMetricsComputer.detectLeak(summaries);
        assertFalse(m.leakSuspected(), "Expected no leak for stable heap");
    }

    @Test
    void perfectLinearGrowth_highConfidence() {
        // R² should be very close to 1.0 for perfect linear growth.
        List<GCAnalyzer.GCSummary> summaries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long heapAfter = (500 + i * 50) * 1024L;
            summaries.add(summary(i, heapAfter + 200 * 1024L, heapAfter));
        }
        GcMetricsComputer.LeakMetrics m = GcMetricsComputer.detectLeak(summaries);
        assertTrue(m.leakSuspected());
        assertTrue(m.confidencePercent() > 95.0,
                "Expected near-100% confidence for perfect linear growth, got " + m.confidencePercent());
    }

    @Test
    void eventsWithZeroHeapAfter_ignoredInLeakDetection() {
        // Some events have heapUsedAfter == 0 (heap-summary not yet merged).
        // detectLeak should skip them and still work with valid events.
        List<GCAnalyzer.GCSummary> summaries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            summaries.add(summary(i * 2, 0, 0)); // invalid — no heap data
        }
        for (int i = 0; i < 15; i++) {
            long heapAfter = (i + 1) * 1024 * 1024L;
            summaries.add(summary(20 + i, heapAfter + 512 * 1024L, heapAfter));
        }
        GcMetricsComputer.LeakMetrics m = GcMetricsComputer.detectLeak(summaries);
        // Should still detect the leak from the valid events
        assertTrue(m.leakSuspected(), "Expected leak detection despite zero-heap events");
    }
}
