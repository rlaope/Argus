package io.argus.cli.gclog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcLogFollowerTest {

    private static final String G1_LINE_0 =
            "[0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.456ms\n";
    private static final String G1_LINE_1 =
            "[0.567s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 32M->12M(256M) 5.123ms\n";
    private static final String G1_LINE_2 =
            "[1.234s][info][gc] GC(2) Pause Full (Ergonomics) 64M->32M(256M) 120.000ms\n";
    private static final String G1_LINE_3 =
            "[2.000s][info][gc] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 40M->15M(256M) 4.000ms\n";

    @TempDir Path tempDir;

    @Test
    void readAll_parsesEntireFile() throws IOException {
        Path log = tempDir.resolve("gc.log");
        Files.writeString(log, G1_LINE_0 + G1_LINE_1);

        GcLogFollower follower = new GcLogFollower(log);
        List<GcEvent> events = follower.readAll();

        assertEquals(2, events.size());
        assertEquals(0.234, events.get(0).timestampSec(), 0.001);
        assertEquals(0.567, events.get(1).timestampSec(), 0.001);
    }

    @Test
    void pollNewEvents_returnsEmptyWhenNoChange() throws IOException {
        Path log = tempDir.resolve("gc.log");
        Files.writeString(log, G1_LINE_0);

        GcLogFollower follower = new GcLogFollower(log);
        follower.readAll(); // consume everything

        List<GcEvent> polled = follower.pollNewEvents();
        assertTrue(polled.isEmpty());
    }

    @Test
    void pollNewEvents_returnsNewLinesAfterAppend() throws IOException {
        Path log = tempDir.resolve("gc.log");
        Files.writeString(log, G1_LINE_0);

        GcLogFollower follower = new GcLogFollower(log);
        List<GcEvent> initial = follower.readAll();
        assertEquals(1, initial.size());

        // Simulate log file growing
        Files.writeString(log, G1_LINE_1, StandardOpenOption.APPEND);

        List<GcEvent> polled = follower.pollNewEvents();
        assertEquals(1, polled.size());
        assertEquals(0.567, polled.get(0).timestampSec(), 0.001);
    }

    @Test
    void pollNewEvents_handlesMultipleAppends() throws IOException {
        Path log = tempDir.resolve("gc.log");
        Files.writeString(log, G1_LINE_0);

        GcLogFollower follower = new GcLogFollower(log);
        follower.readAll();

        Files.writeString(log, G1_LINE_1, StandardOpenOption.APPEND);
        List<GcEvent> first = follower.pollNewEvents();
        assertEquals(1, first.size());

        Files.writeString(log, G1_LINE_2 + G1_LINE_3, StandardOpenOption.APPEND);
        List<GcEvent> second = follower.pollNewEvents();
        assertEquals(2, second.size());
        assertTrue(second.get(0).isFullGc());
    }

    @Test
    void rollingWindow_evictsOldestWhenFull() throws IOException {
        Path log = tempDir.resolve("gc.log");
        Files.writeString(log, G1_LINE_0 + G1_LINE_1 + G1_LINE_2 + G1_LINE_3);

        // maxEvents = 2 — only the last 2 should be in window
        GcLogFollower follower = new GcLogFollower(log, 2);
        RollingGcAnalysis rolling = new RollingGcAnalysis(2);

        List<GcEvent> all = follower.readAll();
        assertEquals(4, all.size());

        rolling.addEvents(all);

        assertEquals(2, rolling.windowSize());
        assertEquals(4, rolling.totalEventsEver());
    }

    @Test
    void rollingAnalysis_snapshotReflectsWindow() throws IOException {
        Path log = tempDir.resolve("gc.log");
        Files.writeString(log, G1_LINE_0 + G1_LINE_1);

        GcLogFollower follower = new GcLogFollower(log);
        RollingGcAnalysis rolling = new RollingGcAnalysis();
        rolling.addEvents(follower.readAll());

        RollingGcAnalysis.Snapshot snap = rolling.snapshot();
        assertEquals(2, snap.totalEventsEver());
        assertTrue(snap.throughputPercent() > 0);
        assertTrue(snap.p50PauseMs() > 0);
    }

    @Test
    void rollingAnalysis_tracksFullGcTime() throws IOException {
        Path log = tempDir.resolve("gc.log");
        Files.writeString(log, G1_LINE_0 + G1_LINE_2); // G1_LINE_2 is Full GC

        GcLogFollower follower = new GcLogFollower(log);
        RollingGcAnalysis rolling = new RollingGcAnalysis();
        rolling.addEvents(follower.readAll());

        RollingGcAnalysis.Snapshot snap = rolling.snapshot();
        assertEquals(1, snap.fullGcCount());
        assertTrue(snap.secsSinceLastFullGc() >= 0);
    }

    @Test
    void parseLine_unifiedFormat() {
        GcEvent e = GcLogFollower.parseLine(
                "[0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.456ms",
                true);
        assertNotNull(e);
        assertEquals(0.234, e.timestampSec(), 0.001);
        assertFalse(e.isFullGc());
    }

    @Test
    void parseLine_legacyFormat() {
        GcEvent e = GcLogFollower.parseLine(
                "1.234: [GC (Allocation Failure) [PSYoungGen: 65536K->8192K(76288K)] 65536K->8200K(251392K), 0.0123456 secs]",
                false);
        assertNotNull(e);
        assertEquals(1.234, e.timestampSec(), 0.001);
        assertEquals("Allocation Failure", e.cause());
    }
}
