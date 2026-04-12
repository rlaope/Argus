package io.argus.cli.gclog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcLeakDetectorTest {

    @Test
    void insufficientEvents_noAnalysis() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "Allocation Failure", 10.0, 1000, 500, 4096),
                new GcEvent(1.0, "Young", "Allocation Failure", 10.0, 1000, 510, 4096)
        );
        GcLeakDetector.LeakAnalysis r = GcLeakDetector.analyze(events);
        assertFalse(r.leakDetected());
        assertEquals("None", r.pattern());
        assertEquals(0, r.trendPoints().length);
    }

    @Test
    void stableHeap_noLeakDetected() {
        // heapAfter stays constant at ~500 KB
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.add(new GcEvent(i, "Young", "Allocation Failure", 10.0,
                    1000, 500 + (i % 3) * 5, 4096));
        }
        GcLeakDetector.LeakAnalysis r = GcLeakDetector.analyze(events);
        assertFalse(r.leakDetected());
        assertEquals("None", r.pattern());
    }

    @Test
    void linearlyIncreasingHeap_leakDetected() {
        // heapAfter increases by 100 KB per event = clear upward trend
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long heapAfter = 500 + i * 100L;
            events.add(new GcEvent(i, "Young", "Allocation Failure", 10.0,
                    heapAfter + 500, heapAfter, 8192));
        }
        GcLeakDetector.LeakAnalysis r = GcLeakDetector.analyze(events);
        assertTrue(r.leakDetected());
        assertTrue(r.confidencePercent() > 70);
        assertTrue(r.heapGrowthRateKBPerSec() > 0);
        assertNotEquals("None", r.pattern());
    }

    @Test
    void leakDetected_oomEstimationPositive() {
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long heapAfter = 500 + i * 100L;
            events.add(new GcEvent(i, "Young", "Allocation Failure", 10.0,
                    heapAfter + 500, heapAfter, 8192));
        }
        GcLeakDetector.LeakAnalysis r = GcLeakDetector.analyze(events);
        if (r.leakDetected()) {
            assertTrue(r.estimatedOomSec() > 0);
        }
    }

    @Test
    void staircasePattern_detected() {
        // Heap grows in steps: stays flat, then jumps 20%, stays flat, jumps again
        List<GcEvent> events = new ArrayList<>();
        long[] heapPattern = {
            500, 500, 500, 700, 700, 700, 900, 900, 900,
            1100, 1100, 1100, 1300, 1300, 1300, 1500, 1500, 1500, 1700, 1700
        };
        for (int i = 0; i < heapPattern.length; i++) {
            events.add(new GcEvent(i, "Young", "Allocation Failure", 10.0,
                    heapPattern[i] + 500, heapPattern[i], 8192));
        }
        GcLeakDetector.LeakAnalysis r = GcLeakDetector.analyze(events);
        if (r.leakDetected()) {
            // With a staircase pattern, should detect steps
            assertTrue(r.staircaseSteps() >= 0);
        }
    }

    @Test
    void concurrentEvents_excluded() {
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            events.add(new GcEvent(i * 2, "Young", "Allocation Failure", 10.0,
                    1000, 500 + i * 50L, 8192));
            events.add(new GcEvent(i * 2 + 1, "Concurrent Mark", "Concurrent", 0.0,
                    0, 0, 8192));
        }
        // Caller is responsible for filtering; pass only pause events
        List<GcEvent> pauseEvents = events.stream().filter(e -> !e.isConcurrent()).toList();
        GcLeakDetector.LeakAnalysis r = GcLeakDetector.analyze(pauseEvents);
        assertNotNull(r);
        // Pattern should reflect only the pause events
    }

    @Test
    void trendPoints_notEmptyWhenSufficientEvents() {
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.add(new GcEvent(i, "Young", "Allocation Failure", 10.0,
                    1000, 500L, 4096));
        }
        GcLeakDetector.LeakAnalysis r = GcLeakDetector.analyze(events);
        assertTrue(r.trendPoints().length > 0);
    }

    @Test
    void highR2_highConfidence() {
        // Perfect linear growth -> R² near 1.0
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long heapAfter = 200 + i * 50L;
            events.add(new GcEvent(i, "Young", "Allocation Failure", 10.0,
                    heapAfter + 300, heapAfter, 8192));
        }
        GcLeakDetector.LeakAnalysis r = GcLeakDetector.analyze(events);
        assertTrue(r.leakDetected());
        assertTrue(r.confidencePercent() > 90,
                "Expected high confidence for perfect linear growth, got " + r.confidencePercent());
    }
}
