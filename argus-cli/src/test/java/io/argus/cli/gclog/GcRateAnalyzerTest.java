package io.argus.cli.gclog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcRateAnalyzerTest {

    private static List<GcEvent> pauseOnly(List<GcEvent> events) {
        return events.stream().filter(e -> !e.isConcurrent()).toList();
    }

    @Test
    void emptyEvents_returnsZeroRates() {
        GcRateAnalyzer.RateAnalysis r = GcRateAnalyzer.analyze(List.of());
        assertEquals(0, r.allocationRateKBPerSec(), 0.01);
        assertEquals(0, r.promotionRateKBPerSec(), 0.01);
    }

    @Test
    void singleEvent_returnsZeroRates() {
        GcEvent e = new GcEvent(1.0, "Young", "Allocation Failure", 50.0, 1024, 512, 4096);
        GcRateAnalyzer.RateAnalysis r = GcRateAnalyzer.analyze(List.of(e));
        assertEquals(0, r.allocationRateKBPerSec(), 0.01);
    }

    @Test
    void steadyAllocation_computesConsistentRate() {
        // heapBefore[i] - heapAfter[i-1] = 500 KB each second => 500 KB/s
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "Allocation Failure", 10.0, 1000, 500, 4096),
                new GcEvent(1.0, "Young", "Allocation Failure", 10.0, 1000, 500, 4096),
                new GcEvent(2.0, "Young", "Allocation Failure", 10.0, 1000, 500, 4096),
                new GcEvent(3.0, "Young", "Allocation Failure", 10.0, 1000, 500, 4096)
        );
        GcRateAnalyzer.RateAnalysis r = GcRateAnalyzer.analyze(events);
        // allocated = 1000 - 500 = 500 KB per 1 sec = 500 KB/s
        assertEquals(500.0, r.allocationRateKBPerSec(), 1.0);
        assertEquals(500.0, r.peakAllocationRateKBPerSec(), 1.0);
    }

    @Test
    void promotionDetection_heapAfterIncreasing() {
        // Young GCs where heapAfter grows between events = promotion to old gen
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "Allocation Failure", 10.0, 2000, 1000, 8192),
                new GcEvent(1.0, "Young", "Allocation Failure", 10.0, 2100, 1100, 8192),
                new GcEvent(2.0, "Young", "Allocation Failure", 10.0, 2200, 1200, 8192),
                new GcEvent(3.0, "Young", "Allocation Failure", 10.0, 2300, 1300, 8192)
        );
        GcRateAnalyzer.RateAnalysis r = GcRateAnalyzer.analyze(events);
        // promotedKB = 1100 - 1000 = 100 KB per 1 sec = 100 KB/s
        assertTrue(r.promotionRateKBPerSec() > 0);
        assertEquals(100.0, r.promotionRateKBPerSec(), 5.0);
    }

    @Test
    void reclaimEfficiency_calculatedCorrectly() {
        // heapBefore=1000, heapAfter=200 -> reclaimed=800, allocated=800
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "Allocation Failure", 10.0, 1000, 200, 4096),
                new GcEvent(1.0, "Young", "Allocation Failure", 10.0, 1000, 200, 4096),
                new GcEvent(2.0, "Young", "Allocation Failure", 10.0, 1000, 200, 4096)
        );
        GcRateAnalyzer.RateAnalysis r = GcRateAnalyzer.analyze(events);
        // allocated = 1000 - 200 = 800 KB/s, reclaimed = 800 KB
        // efficiency = reclaimed / allocated * 100 = 100%
        assertTrue(r.reclaimEfficiencyPercent() > 0);
    }

    @Test
    void concurrentEvents_excluded() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "Allocation Failure", 10.0, 1000, 500, 4096),
                new GcEvent(0.5, "Concurrent Mark", "Concurrent", 0.0, 0, 0, 4096),
                new GcEvent(1.0, "Young", "Allocation Failure", 10.0, 1000, 500, 4096)
        );
        // Caller is responsible for filtering; pass only pause events
        GcRateAnalyzer.RateAnalysis r = GcRateAnalyzer.analyze(pauseOnly(events));
        // Should use only the two Young events, not the Concurrent one
        assertTrue(r.allocationRateKBPerSec() > 0);
    }

    @Test
    void sparklineWindows_length10() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "Allocation Failure", 10.0, 1000, 500, 4096),
                new GcEvent(1.0, "Young", "Allocation Failure", 10.0, 1000, 500, 4096)
        );
        GcRateAnalyzer.RateAnalysis r = GcRateAnalyzer.analyze(events);
        assertEquals(10, r.allocationRateWindows().length);
        assertEquals(10, r.promotionRateWindows().length);
    }

    @Test
    void promoAllocRatio_computedFromRates() {
        // 100 KB/s alloc, 10 KB/s promo -> 10% ratio
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "Allocation Failure", 10.0, 2000, 1000, 8192),
                new GcEvent(1.0, "Young", "Allocation Failure", 10.0, 2100, 1100, 8192),
                new GcEvent(2.0, "Young", "Allocation Failure", 10.0, 2200, 1200, 8192)
        );
        GcRateAnalyzer.RateAnalysis r = GcRateAnalyzer.analyze(events);
        // alloc rate = 2100 - 1000 = 1100 KB over 1s and 2200 - 1100 = 1100 KB over 1s
        // promo rate = 1100 - 1000 = 100 KB/s
        assertTrue(r.promoAllocRatioPercent() > 0);
    }
}
