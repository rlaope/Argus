package io.argus.cli.gcwhy;

import io.argus.cli.gclog.GcEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcWhyAnalyzerTest {

    @Test
    void explicitSystemGcDetected() {
        List<GcEvent> events = new ArrayList<>();
        events.add(pause(1.0, "Young", "G1 Evacuation Pause", 30, 1000, 200, 4096));
        events.add(pause(2.0, "Young", "G1 Evacuation Pause", 28, 1200, 220, 4096));
        events.add(pause(3.0, "Full", "System.gc()", 400, 2000, 800, 4096));

        GcWhyResult r = GcWhyAnalyzer.analyze(events, 60);

        assertEquals(400, r.pauseMs());
        assertTrue(
                r.bullets().stream().anyMatch(b -> b.toLowerCase().contains("system.gc")),
                "expected System.gc bullet, got " + r.bullets());
    }

    @Test
    void humongousCauseDetected() {
        List<GcEvent> events = new ArrayList<>();
        events.add(pause(1.0, "Young", "G1 Evacuation Pause", 30, 1000, 200, 4096));
        events.add(pause(2.0, "Young", "G1 Humongous Allocation", 350, 1500, 800, 4096));

        GcWhyResult r = GcWhyAnalyzer.analyze(events, 60);

        assertTrue(
                r.bullets().stream().anyMatch(b -> b.toLowerCase().contains("humongous")),
                "expected humongous bullet, got " + r.bullets());
    }

    @Test
    void allocationBurstDetected() {
        List<GcEvent> events = new ArrayList<>();
        // Baseline: steady small deltas between heapAfter and next heapBefore.
        events.add(pause(1.0, "Young", "G1 Evacuation Pause", 20, 1000, 200, 10_000));
        events.add(pause(2.0, "Young", "G1 Evacuation Pause", 20, 1100, 210, 10_000));
        events.add(pause(3.0, "Young", "G1 Evacuation Pause", 20, 1100, 210, 10_000));
        events.add(pause(4.0, "Young", "G1 Evacuation Pause", 20, 1100, 210, 10_000));
        // Burst: heapBefore jumps way above prior heapAfter.
        events.add(pause(5.0, "Young", "G1 Evacuation Pause", 150, 8000, 800, 10_000));

        GcWhyResult r = GcWhyAnalyzer.analyze(events, 60);

        assertTrue(
                r.bullets().stream().anyMatch(b -> b.toLowerCase().contains("allocation rate surged")),
                "expected allocation burst bullet, got " + r.bullets());
    }

    @Test
    void highOccupancyDetected() {
        List<GcEvent> events = new ArrayList<>();
        events.add(pause(1.0, "Young", "G1 Evacuation Pause", 30, 1000, 200, 4096));
        events.add(pause(2.0, "Mixed", "G1 Evacuation Pause", 300, 3900, 1000, 4096));

        GcWhyResult r = GcWhyAnalyzer.analyze(events, 60);

        assertTrue(
                r.bullets().stream().anyMatch(b -> b.toLowerCase().contains("heap was")),
                "expected high-occupancy bullet, got " + r.bullets());
    }

    @Test
    void fullGcFallbackDetected() {
        List<GcEvent> events = new ArrayList<>();
        events.add(pause(1.0, "Young", "G1 Evacuation Pause", 30, 1000, 200, 4096));
        events.add(pause(2.0, "Full", "Allocation Failure", 800, 4000, 1500, 4096));

        GcWhyResult r = GcWhyAnalyzer.analyze(events, 60);

        assertTrue(
                r.bullets().stream().anyMatch(b -> b.toLowerCase().contains("full gc")),
                "expected full-gc bullet, got " + r.bullets());
    }

    @Test
    void emptyEventsReturnsEmptyResult() {
        GcWhyResult r = GcWhyAnalyzer.analyze(List.of(), 60);
        assertEquals(0, r.pauseMs());
        assertTrue(r.bullets().isEmpty());
    }

    @Test
    void windowFiltersOlderEvents() {
        List<GcEvent> events = new ArrayList<>();
        // Outside 30s window
        events.add(pause(1.0, "Full", "System.gc()", 900, 2000, 800, 4096));
        // Inside 30s window
        events.add(pause(40.0, "Young", "G1 Evacuation Pause", 40, 1000, 200, 4096));
        events.add(pause(50.0, "Young", "G1 Evacuation Pause", 50, 1100, 210, 4096));

        GcWhyResult r = GcWhyAnalyzer.analyze(events, 30);

        assertEquals(50, r.pauseMs(), "should pick in-window max, not the older System.gc one");
        assertFalse(
                r.bullets().stream().anyMatch(b -> b.toLowerCase().contains("system.gc")),
                "System.gc event is outside window and should not show, got " + r.bullets());
    }

    private static GcEvent pause(double ts, String type, String cause, double pauseMs,
                                 long heapBefore, long heapAfter, long heapTotal) {
        return new GcEvent(ts, type, cause, pauseMs, heapBefore, heapAfter, heapTotal);
    }
}
