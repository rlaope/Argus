package io.argus.cli.provider.jdk;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AsProfLoopCaptureTest {

    @Test
    void parsesMultiLineCollapsedToStackCounts() {
        String collapsed = String.join("\n",
                "main;a;b 10",
                "main;c 5",
                "main;a;b 3");
        Map<String, Long> counts = AsProfLoopCapture.parseCollapsedCounts(collapsed);
        assertEquals(13L, counts.get("main;a;b"), "duplicate stacks sum");
        assertEquals(5L, counts.get("main;c"));
        assertEquals(2, counts.size());
    }

    @Test
    void skipsBlankAndMalformedLines() {
        String collapsed = String.join("\n",
                "",
                "main;x 7",
                "no-count-here",
                "main;y notanumber",
                "   ",
                "main;z 4");
        Map<String, Long> counts = AsProfLoopCapture.parseCollapsedCounts(collapsed);
        assertEquals(7L, counts.get("main;x"));
        assertEquals(4L, counts.get("main;z"));
        assertEquals(2, counts.size(), "blank/malformed lines dropped");
    }

    @Test
    void preservesSemicolonsInFrames() {
        String collapsed = "java/lang/Thread.run;com.x.Foo.<init>;com.x.Bar.handle 42";
        Map<String, Long> counts = AsProfLoopCapture.parseCollapsedCounts(collapsed);
        assertEquals(42L, counts.get("java/lang/Thread.run;com.x.Foo.<init>;com.x.Bar.handle"));
        assertEquals(1, counts.size());
    }

    @Test
    void emptyOrNullOutputYieldsEmptyMap() {
        assertTrue(AsProfLoopCapture.parseCollapsedCounts(null).isEmpty());
        assertTrue(AsProfLoopCapture.parseCollapsedCounts("").isEmpty());
    }

    @Test
    void continuousDisabledNeverStarts() {
        AsProfLoopCapture cap = new AsProfLoopCapture(
                1234L, "cpu", 10, 60, false, (counts, ts) -> {});
        assertFalse(cap.isContinuousEnabled());
        assertFalse(cap.start(), "start() must be a no-op when disabled");
        assertFalse(cap.isRunning());
    }

    @Test
    void rejectsNonPositiveTimings() {
        assertThrows(IllegalArgumentException.class,
                () -> new AsProfLoopCapture(1L, "cpu", 0, 60, true, (c, t) -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new AsProfLoopCapture(1L, "cpu", 10, 0, true, (c, t) -> {}));
    }

    @Test
    void rejectsCadenceSmallerThanCapture() {
        // cadence (5s) < capture (30s) would queue overlapping captures — must be rejected.
        assertThrows(IllegalArgumentException.class,
                () -> new AsProfLoopCapture(1L, "cpu", 30, 5, true, (c, t) -> {}));
        // cadence == capture is allowed (boundary).
        assertDoesNotThrow(
                () -> new AsProfLoopCapture(1L, "cpu", 30, 30, false, (c, t) -> {}));
    }
}
