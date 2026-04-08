package io.argus.cli.gclog;

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
        String log = """
                [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.456ms
                [0.567s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 32M->12M(256M) 5.123ms
                [1.234s][info][gc] GC(2) Pause Young (Concurrent Start) (G1 Humongous Allocation) 64M->32M(256M) 12.345ms
                [5.678s][info][gc] GC(3) Concurrent Mark 45.678ms
                """;
        Path file = tempDir.resolve("g1.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(4, events.size());

        // First event
        GcEvent first = events.getFirst();
        assertEquals(0.234, first.timestampSec(), 0.001);
        assertTrue(first.pauseMs() > 3.0 && first.pauseMs() < 4.0);
        assertEquals(24 * 1024, first.heapBeforeKB());
        assertEquals(8 * 1024, first.heapAfterKB());
        assertFalse(first.isFullGc());
        assertFalse(first.isConcurrent());

        // Concurrent event
        GcEvent concurrent = events.getLast();
        assertTrue(concurrent.isConcurrent());
    }

    @Test
    void parseLegacyFormat() throws IOException {
        String log = """
                1.234: [GC (Allocation Failure) [PSYoungGen: 65536K->8192K(76288K)] 65536K->8200K(251392K), 0.0123456 secs]
                5.678: [Full GC (Ergonomics) [PSYoungGen: 0K->0K(76288K)] 180000K->120000K(251392K), 0.5678901 secs]
                """;
        Path file = tempDir.resolve("legacy.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(2, events.size());

        GcEvent young = events.getFirst();
        assertEquals("Young", young.type());
        assertEquals("Allocation Failure", young.cause());
        assertFalse(young.isFullGc());

        GcEvent full = events.getLast();
        assertTrue(full.isFullGc());
        assertTrue(full.pauseMs() > 500);
    }

    @Test
    void parseDecoratedTimestamp() throws IOException {
        String log = "[2024-01-15T10:30:45.123+0000][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.456ms\n";
        Path file = tempDir.resolve("decorated.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(1, events.size());
        assertTrue(events.getFirst().timestampSec() > 0);
    }

    @Test
    void parseShenandoahPause() throws IOException {
        String log = "[0.500s][info][gc] GC(0) Pause Init Mark 0.123ms\n" +
                     "[0.600s][info][gc] GC(1) Pause Final Mark 0.456ms\n";
        Path file = tempDir.resolve("shenandoah.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(2, events.size());
        assertTrue(events.getFirst().type().contains("Shenandoah"));
        assertTrue(events.getFirst().pauseMs() < 1.0); // sub-ms precision
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
        String log = """
                [0.001s][info][os] Application started in 2.345 seconds
                [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.456ms
                [0.300s][info][os] Some random log line
                [0.400s][info][os] Another non-GC line
                """;
        Path file = tempDir.resolve("mixed.log");
        Files.writeString(file, log);

        List<GcEvent> events = GcLogParser.parse(file);
        assertEquals(1, events.size());
    }
}
