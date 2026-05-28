package io.argus.diagnostics.gclog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link GcLogParser#parseWithPhases} populates {@link G1Stats}
 * from a synthetic G1 log fragment.
 */
class G1StatsParserTest {

    private static Path writeLog(Path dir, String content) throws IOException {
        Path f = dir.resolve("gc.log");
        Files.writeString(f, content);
        return f;
    }

    @Test
    void evacuation_failure_counted(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, ""
                + "[0.500s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 256M->250M(512M) 12.5ms\n"
                + "[0.800s][info][gc] GC(2) To-space exhausted\n"
                + "[1.000s][info][gc] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 260M->258M(512M) 14.2ms\n");
        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(log);
        assertEquals(1, result.g1Stats().evacuationFailures());
    }

    @Test
    void humongous_signals_collected(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, ""
                + "[0.500s][info][gc] GC(1) Pause Young (Normal) (G1 Humongous Allocation) 256M->250M(512M) 12.5ms\n"
                + "[0.700s][info][gc,heap] GC(2) Humongous regions: 4->6\n"
                + "[0.900s][info][gc] GC(2) Pause Young (Normal) (G1 Humongous Allocation) 260M->258M(512M) 14.2ms\n");
        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(log);
        assertEquals(2, result.g1Stats().humongousAllocationCycles());
        assertEquals(6, result.g1Stats().humongousRegionsPeak());
    }

    @Test
    void mixed_vs_prepare_mixed_distinguished(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, ""
                + "[1.000s][info][gc] GC(1) Pause Young (Prepare Mixed) (G1 Evacuation Pause) 256M->250M(512M) 12.5ms\n"
                + "[2.000s][info][gc] GC(2) Pause Young (Mixed) (G1 Evacuation Pause) 250M->245M(512M) 14.2ms\n"
                + "[3.000s][info][gc] GC(3) Pause Young (Mixed) (G1 Evacuation Pause) 248M->242M(512M) 13.8ms\n");
        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(log);
        assertEquals(2, result.g1Stats().mixedPauses());
        assertEquals(1, result.g1Stats().prepareMixedPauses());
    }

    @Test
    void concurrent_cycle_markers_counted(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, ""
                + "[1.000s][info][gc] GC(1) Concurrent Mark Cycle\n"
                + "[2.000s][info][gc] GC(2) Concurrent Mark Cycle\n");
        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(log);
        assertEquals(2, result.g1Stats().concurrentCycleMarkers());
    }

    @Test
    void mixed_starvation_suspected_when_concurrent_without_mixed(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, ""
                + "[1.000s][info][gc] GC(1) Concurrent Mark Cycle\n"
                + "[2.000s][info][gc] GC(2) Concurrent Mark Cycle\n");
        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(log);
        assertTrue(result.g1Stats().mixedStarvationSuspected());
    }

    @Test
    void non_g1_log_yields_empty_stats(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, ""
                + "[0.500s][info][gc] GC(1) Pause Mark Start 0.4ms\n"
                + "[0.800s][info][gc] GC(1) Pause Mark End 2.1ms\n");
        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(log);
        assertFalse(result.g1Stats().present(),
                "Expected empty G1Stats for ZGC log, got " + result.g1Stats());
    }

    @Test
    void full_gc_seen_on_pause_full(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, ""
                + "[1.000s][info][gc] GC(1) Pause Full (G1 Compaction Pause) 512M->500M(512M) 850ms\n");
        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(log);
        assertTrue(result.g1Stats().fullGcSeen());
    }
}
