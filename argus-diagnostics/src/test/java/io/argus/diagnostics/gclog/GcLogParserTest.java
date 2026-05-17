package io.argus.diagnostics.gclog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcLogParserTest {

    @TempDir Path tempDir;

    @Test
    void parseG1UnifiedLog() throws IOException {
        String log =
                "[0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.456ms\n" +
                "[0.567s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 32M->12M(256M) 5.123ms\n" +
                "[1.234s][info][gc] GC(2) Pause Young (Concurrent Start) (G1 Humongous Allocation) 64M->32M(256M) 12.345ms\n" +
                "[5.678s][info][gc] GC(3) Concurrent Mark 45.678ms\n";
        Path file = tempDir.resolve("g1.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(4, events.size());

        // First event
        GcEvent first = events.get(0);
        assertEquals(0.234, first.timestampSec(), 0.001);
        assertTrue(first.pauseMs() > 3.0 && first.pauseMs() < 4.0);
        assertEquals(24 * 1024, first.heapBeforeKB());
        assertEquals(8 * 1024, first.heapAfterKB());
        assertFalse(first.isFullGc());
        assertFalse(first.isConcurrent());

        // Concurrent event
        GcEvent concurrent = events.get(events.size() - 1);
        assertTrue(concurrent.isConcurrent());
    }

    @Test
    void parseLegacyFormat() throws IOException {
        String log =
                "1.234: [GC (Allocation Failure) [PSYoungGen: 65536K->8192K(76288K)] 65536K->8200K(251392K), 0.0123456 secs]\n" +
                "5.678: [Full GC (Ergonomics) [PSYoungGen: 0K->0K(76288K)] 180000K->120000K(251392K), 0.5678901 secs]\n";
        Path file = tempDir.resolve("legacy.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(2, events.size());

        GcEvent young = events.get(0);
        assertEquals("Young", young.type());
        assertEquals("Allocation Failure", young.cause());
        assertFalse(young.isFullGc());

        GcEvent full = events.get(events.size() - 1);
        assertTrue(full.isFullGc());
        assertTrue(full.pauseMs() > 500);
    }

    @Test
    void parseDecoratedTimestamp() throws IOException {
        // Two events 5s apart across an ISO baseline. The first event's relative time is 0
        // (it IS the baseline); subsequent events are deltas. This semantics replaces the old
        // seconds-of-day extraction which silently wrapped at midnight.
        String log =
                "[2024-01-15T10:30:45.123+0000][info][gc] GC(0) Pause Young (G1 Evacuation Pause) 24M->8M(256M) 3.456ms\n" +
                "[2024-01-15T10:30:50.123+0000][info][gc] GC(1) Pause Young (G1 Evacuation Pause) 32M->12M(256M) 5.123ms\n";
        Path file = tempDir.resolve("decorated.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(2, events.size());
        assertEquals(0.0, events.get(0).timestampSec(), 0.001,
                "first ISO event sets baseline → relative timestamp is zero");
        assertEquals(5.0, events.get(events.size() - 1).timestampSec(), 0.5,
                "second event is ~5s after the baseline");
    }

    @Test
    void parseShenandoahPause() throws IOException {
        // Real Shenandoah unified logs carry the [gc,shenandoah] tag; without that gate, a
        // bare "Pause Init Mark" line (which G1 also emits in some configs) would misclassify.
        String log =
                "[0.500s][info][gc,shenandoah] GC(0) Pause Init Mark 0.123ms\n" +
                "[0.600s][info][gc,shenandoah] GC(1) Pause Final Mark 0.456ms\n";
        Path file = tempDir.resolve("shenandoah.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(2, events.size());
        assertTrue(events.get(0).type().contains("Shenandoah"));
        assertTrue(events.get(0).pauseMs() < 1.0); // sub-ms precision
    }

    @Test
    void parseZgcAllocationStalls() throws IOException {
        String log =
                "[1282.654s][info][gc] Allocation Stall (http-worker-347) 508.772ms\n" +
                "[1283.100s][info][gc] Allocation Stall (http-worker-348) 123.456ms\n" +
                "[1284.200s][info][gc] Allocation Stall (http-worker-347) 200.000ms\n";
        Path file = tempDir.resolve("zgc-stall.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(3, events.size(), "expected 3 allocation stall events");
        for (GcEvent e : events) {
            assertEquals("ZGC Allocation Stall", e.type());
        }
        assertEquals("http-worker-347", events.get(0).cause());
        assertEquals(508.772, events.get(0).pauseMs(), 0.001);
        assertEquals("http-worker-348", events.get(1).cause());
        assertEquals(123.456, events.get(1).pauseMs(), 0.001);
        assertEquals("http-worker-347", events.get(2).cause());
        assertEquals(200.000, events.get(2).pauseMs(), 0.001);
    }

    @Test
    void emptyFile_noEvents() throws IOException {
        Path file = tempDir.resolve("empty.log");
        Files.writeString(file, "");

        List<GcEvent> events = GcLogParser.parse(file);
        assertTrue(events.isEmpty());
    }

    @Test
    void nonGcLines_ignored() throws IOException {
        // First line must hint unified format for auto-detection
        String log =
                "[0.001s][info][os] Application started in 2.345 seconds\n" +
                "[0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.456ms\n" +
                "[0.300s][info][os] Some random log line\n" +
                "[0.400s][info][os] Another non-GC line\n";
        Path file = tempDir.resolve("mixed.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(1, events.size());
    }
}
