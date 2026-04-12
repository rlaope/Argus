package io.argus.cli.gclog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcTimelineRendererTest {

    @Test
    void singleEvent_producesMinimalTimeline() {
        GcEvent e = new GcEvent(0.0, "Young", "Allocation Failure", 50.0, 1024, 512, 2048);
        String result = GcTimelineRenderer.render(List.of(e), 50L, 100L, 80, false);

        assertNotNull(result);
        assertFalse(result.isBlank());
        // Should contain a pause row
        assertTrue(result.contains("pause"));
    }

    @Test
    void multipleEvents_properBucketing() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "Allocation Failure", 10.0, 1024, 512, 2048),
                new GcEvent(10.0, "Young", "Allocation Failure", 20.0, 1024, 512, 2048),
                new GcEvent(20.0, "Young", "Allocation Failure", 30.0, 1024, 512, 2048),
                new GcEvent(30.0, "Young", "Allocation Failure", 50.0, 1024, 512, 2048),
                new GcEvent(40.0, "Young", "Allocation Failure", 80.0, 1024, 512, 2048)
        );
        String result = GcTimelineRenderer.render(events, 30L, 70L, 120, false);

        assertNotNull(result);
        assertTrue(result.contains("pause"));
        assertTrue(result.contains("count"));
        assertTrue(result.contains("time"));
    }

    @Test
    void fullGcEvents_fMarkerAppears() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "Allocation Failure", 10.0, 1024, 512, 2048),
                new GcEvent(5.0, "Full", "Metadata GC Threshold", 500.0, 2048, 1024, 4096),
                new GcEvent(10.0, "Young", "Allocation Failure", 15.0, 1024, 512, 2048)
        );
        String result = GcTimelineRenderer.render(events, 15L, 100L, 100, false);

        assertNotNull(result);
        assertTrue(result.contains("F"), "Full GC events should render as 'F' marker");
    }

    @Test
    void allConcurrentEvents_gracefulHandling() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Concurrent Mark", "G1 Humongous", 0.0, 1024, 1024, 2048),
                new GcEvent(5.0, "Concurrent Cleanup", "G1 Humongous", 0.0, 1024, 1024, 2048)
        );
        String result = GcTimelineRenderer.render(events, 0L, 0L, 80, false);

        assertNotNull(result);
        assertTrue(result.contains("No pause events"), "Should display message when no pause events");
    }

    @Test
    void narrowTerminalWidth_doesNotCrash() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "cause", 50.0, 512, 256, 1024),
                new GcEvent(30.0, "Young", "cause", 80.0, 512, 256, 1024)
        );
        String result = GcTimelineRenderer.render(events, 50L, 75L, 60, false);

        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void wideTerminalWidth_usesFullWidth() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "cause", 20.0, 512, 256, 1024),
                new GcEvent(60.0, "Young", "cause", 40.0, 512, 256, 1024),
                new GcEvent(120.0, "Full", "cause", 200.0, 512, 256, 1024)
        );
        String result = GcTimelineRenderer.render(events, 30L, 100L, 200, false);

        assertNotNull(result);
        assertTrue(result.contains("F"), "Full GC marker should appear at wide width");
        // Verify time labels are present (seconds format for < 60s, minutes for >= 60s)
        assertTrue(result.contains("0s") || result.contains("2m"),
                "Time axis labels should be present");
    }

    @Test
    void sameTimestamp_doesNotCrash() {
        List<GcEvent> events = List.of(
                new GcEvent(5.0, "Young", "cause", 30.0, 512, 256, 1024),
                new GcEvent(5.0, "Young", "cause", 45.0, 512, 256, 1024),
                new GcEvent(5.0, "Young", "cause", 60.0, 512, 256, 1024)
        );
        String result = GcTimelineRenderer.render(events, 40L, 55L, 100, false);

        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void colorEnabled_doesNotCorruptBoxLines() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "cause", 10.0, 512, 256, 1024),
                new GcEvent(10.0, "Mixed", "cause", 50.0, 512, 256, 1024),
                new GcEvent(20.0, "Full", "cause", 300.0, 512, 256, 1024)
        );
        String result = GcTimelineRenderer.render(events, 20L, 80L, 100, true);

        assertNotNull(result);
        // Box borders should be present
        assertTrue(result.contains("│"), "Box line borders should be present");
    }

    @Test
    void legendContainsThresholdInfo() {
        List<GcEvent> events = List.of(
                new GcEvent(0.0, "Young", "cause", 25.0, 512, 256, 1024),
                new GcEvent(5.0, "Young", "cause", 75.0, 512, 256, 1024)
        );
        String result = GcTimelineRenderer.render(events, 30L, 60L, 100, false);

        assertNotNull(result);
        assertTrue(result.contains("p50=30ms"), "Legend should show p50 threshold");
        assertTrue(result.contains("p95=60ms"), "Legend should show p95 threshold");
    }
}
