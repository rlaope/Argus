package io.argus.server.analysis;

import io.argus.server.analysis.CorrelationAnalyzer.PauseWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the W4 timing-overlap correlation surface on {@link CorrelationAnalyzer}:
 * the significance threshold, the pause-window recording, the overlap query, and
 * trace-id retention.
 */
class CorrelationAnalyzerTracePauseTest {

    @Test
    void sub_threshold_pauses_are_ignored() {
        CorrelationAnalyzer analyzer = new CorrelationAnalyzer(50.0);
        Instant t = Instant.parse("2026-05-28T10:00:00Z");
        boolean recorded = analyzer.recordPauseWindow(
                t, t.plusMillis(10), "G1 Young Generation", "G1 Evacuation Pause",
                10.0, 0L, null);
        assertFalse(recorded, "10ms pause is below the 50ms threshold");
        assertTrue(analyzer.getRecentPauseWindows().isEmpty(), "no window should be retained");
    }

    @Test
    void significant_pause_is_recorded_with_trace_id() {
        CorrelationAnalyzer analyzer = new CorrelationAnalyzer(50.0);
        Instant start = Instant.parse("2026-05-28T10:00:00Z");
        boolean recorded = analyzer.recordPauseWindow(
                start, start.plusMillis(120), "G1 Old Generation", "G1 Humongous Allocation",
                120.0, 4_096L, "0af7651916cd43dd8448eb211c80319c");
        assertTrue(recorded, "120ms pause clears the 50ms threshold");

        List<PauseWindow> windows = analyzer.getRecentPauseWindows();
        assertEquals(1, windows.size());
        PauseWindow w = windows.get(0);
        assertEquals("G1 Old Generation", w.gcName());
        assertEquals("G1 Humongous Allocation", w.gcCause());
        assertEquals(120.0, w.pauseMs(), 0.001);
        assertEquals(4_096L, w.reclaimedBytes());
        assertEquals("0af7651916cd43dd8448eb211c80319c", w.traceId());
    }

    @Test
    void overlap_query_returns_pauses_intersecting_the_window() {
        CorrelationAnalyzer analyzer = new CorrelationAnalyzer(50.0);
        Instant base = Instant.parse("2026-05-28T10:00:00Z");

        // Pause A: 10:00:00.000 -> 10:00:00.100 (overlaps query start edge)
        analyzer.recordPauseWindow(base, base.plusMillis(100),
                "G1", "Evac", 100.0, 0L, "aaaa651916cd43dd8448eb211c80319c");
        // Pause B: 10:00:02.000 -> 10:00:02.080 (inside the query window)
        analyzer.recordPauseWindow(base.plusSeconds(2), base.plusSeconds(2).plusMillis(80),
                "G1", "Evac", 80.0, 0L, null);
        // Pause C: 10:00:10.000 -> 10:00:10.060 (outside the query window)
        analyzer.recordPauseWindow(base.plusSeconds(10), base.plusSeconds(10).plusMillis(60),
                "G1", "Evac", 60.0, 0L, null);

        // Query window: 10:00:00.050 -> 10:00:05.000
        List<PauseWindow> overlapping = analyzer.pausesOverlapping(
                base.plusMillis(50), base.plusSeconds(5));

        assertEquals(2, overlapping.size(), "A (edge overlap) and B (inside) must match; C must not");
        // Sorted oldest-first
        assertEquals("aaaa651916cd43dd8448eb211c80319c", overlapping.get(0).traceId());
        assertEquals(100.0, overlapping.get(0).pauseMs(), 0.001);
        assertNull(overlapping.get(1).traceId());
        assertEquals(80.0, overlapping.get(1).pauseMs(), 0.001);
    }

    @Test
    void analysis_exposes_threshold_and_pauses() {
        CorrelationAnalyzer analyzer = new CorrelationAnalyzer(75.0);
        Instant t = Instant.parse("2026-05-28T10:00:00Z");
        analyzer.recordPauseWindow(t, t.plusMillis(200), "ZGC", "Proactive", 200.0, 0L, null);

        var result = analyzer.getAnalysis();
        assertEquals(75.0, result.significantPauseThresholdMs(), 0.001);
        assertEquals(1, result.tracePauses().size());
    }

    @Test
    void clear_removes_recorded_pauses() {
        CorrelationAnalyzer analyzer = new CorrelationAnalyzer(50.0);
        Instant t = Instant.parse("2026-05-28T10:00:00Z");
        analyzer.recordPauseWindow(t, t.plusMillis(100), "G1", "Evac", 100.0, 0L, null);
        assertFalse(analyzer.getRecentPauseWindows().isEmpty());
        analyzer.clear();
        assertTrue(analyzer.getRecentPauseWindows().isEmpty());
    }
}
