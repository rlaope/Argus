package io.argus.cli.gclog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcLogAnalyzerTest {

    @Test
    void emptyEvents_defaultAnalysis() {
        GcLogAnalysis a = GcLogAnalyzer.analyze(List.of());
        assertEquals(0, a.totalEvents());
        assertEquals(100, a.throughputPercent(), 0.01);
    }

    @Test
    void singlePause_correctStats() {
        GcEvent e = new GcEvent(1.0, "Young", "Allocation Failure", 50.0, 1024, 512, 2048);
        GcLogAnalysis a = GcLogAnalyzer.analyze(List.of(e));

        assertEquals(1, a.totalEvents());
        assertEquals(1, a.pauseEvents());
        assertEquals(50, a.maxPauseMs());
        assertEquals(50, a.p50PauseMs());
        assertEquals(50, a.avgPauseMs());
    }

    @Test
    void multiplePauses_percentilesCorrect() {
        List<GcEvent> events = List.of(
                new GcEvent(1.0, "Young", "cause", 10.0, 100, 50, 200),
                new GcEvent(2.0, "Young", "cause", 20.0, 100, 50, 200),
                new GcEvent(3.0, "Young", "cause", 30.0, 100, 50, 200),
                new GcEvent(4.0, "Young", "cause", 40.0, 100, 50, 200),
                new GcEvent(5.0, "Young", "cause", 100.0, 100, 50, 200)
        );
        GcLogAnalysis a = GcLogAnalyzer.analyze(events);

        assertEquals(5, a.pauseEvents());
        assertEquals(100, a.maxPauseMs());
        assertEquals(30, a.p50PauseMs());
        assertTrue(a.p95PauseMs() >= 40);
        assertTrue(a.p99PauseMs() >= 100);
    }

    @Test
    void fullGc_generatesRecommendation() {
        List<GcEvent> events = List.of(
                new GcEvent(1.0, "Full", "Ergonomics", 500.0, 4096, 2048, 4096),
                new GcEvent(2.0, "Full", "Ergonomics", 600.0, 4096, 2048, 4096)
        );
        GcLogAnalysis a = GcLogAnalyzer.analyze(events);

        assertEquals(2, a.fullGcEvents());
        assertFalse(a.recommendations().isEmpty());
        assertTrue(a.recommendations().stream().anyMatch(r -> r.severity().equals("CRITICAL")));
    }

    @Test
    void throughput_clamped() {
        // Edge case: pause > duration (shouldn't happen but clamp to 0)
        GcEvent e = new GcEvent(0.0, "Young", "cause", 2000.0, 100, 50, 200);
        GcLogAnalysis a = GcLogAnalyzer.analyze(List.of(e));

        assertTrue(a.throughputPercent() >= 0);
        assertTrue(a.throughputPercent() <= 100);
    }

    @Test
    void causeBreakdown_sortedByCount() {
        List<GcEvent> events = List.of(
                new GcEvent(1.0, "Young", "Alloc Failure", 10.0, 100, 50, 200),
                new GcEvent(2.0, "Young", "Alloc Failure", 10.0, 100, 50, 200),
                new GcEvent(3.0, "Young", "Alloc Failure", 10.0, 100, 50, 200),
                new GcEvent(4.0, "Young", "Metadata GC", 10.0, 100, 50, 200)
        );
        GcLogAnalysis a = GcLogAnalyzer.analyze(events);

        assertEquals(2, a.causeBreakdown().size());
        // "Alloc Failure" should have count 3
        var allocFailure = a.causeBreakdown().get("Alloc Failure");
        assertNotNull(allocFailure);
        assertEquals(3, allocFailure.count());
        assertEquals(1, a.causeBreakdown().get("Metadata GC").count());
    }

    @Test
    void concurrentEvents_excluded_fromPauseStats() {
        List<GcEvent> events = List.of(
                new GcEvent(1.0, "Young", "cause", 10.0, 100, 50, 200),
                new GcEvent(2.0, "Concurrent Mark", "Concurrent", 500.0, 0, 0, 0)
        );
        GcLogAnalysis a = GcLogAnalyzer.analyze(events);

        assertEquals(2, a.totalEvents());
        assertEquals(1, a.pauseEvents());
        assertEquals(1, a.concurrentEvents());
        assertEquals(10, a.maxPauseMs()); // concurrent 500ms NOT counted as pause
    }
}
