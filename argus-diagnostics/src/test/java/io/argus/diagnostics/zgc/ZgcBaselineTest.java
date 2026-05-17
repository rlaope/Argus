package io.argus.diagnostics.zgc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZgcBaselineTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ZgcDiagnosis buildDiagnosis(int stallCount, long heapCommitted,
                                                long softMax, boolean softMaxBreached,
                                                double pauseMarkEndMs,
                                                int minorCycles, int majorCycles) {
        ZgcDiagnosis d = new ZgcDiagnosis();
        d.usingZgc           = true;
        d.generational       = true;
        d.heapCommittedBytes = heapCommitted;
        d.softMaxHeapBytes   = softMax;
        d.maxHeapBytes       = softMax > 0 ? softMax * 2 : heapCommitted * 2;
        d.minorCycles        = minorCycles;
        d.majorCycles        = majorCycles;
        d.avgCycleIntervalSec = 0.75;
        d.avgCycleDurationSec = 0.10;
        d.pauseMarkStartMs   = 0.10;
        d.pauseMarkEndMs     = pauseMarkEndMs;
        d.pauseRelocateStartMs = 0.12;
        d.softMaxBreached    = softMaxBreached;
        d.cycleOverlap       = false;
        for (int i = 0; i < stallCount; i++) {
            d.stalls.add(new ZgcDiagnosis.Stall("http-worker-" + i, 10.0 + i));
        }
        return d;
    }

    // ── Test 1: save / load round-trip ────────────────────────────────────────

    @Test
    void saveLoadRoundTripPreservesAllFields(@TempDir Path tmp) throws IOException {
        ZgcDiagnosis d = buildDiagnosis(
                3,                     // stallCount
                4L * 1024 * 1024 * 1024,  // heapCommitted = 4 GB
                4L * 1024 * 1024 * 1024,  // softMax = 4 GB
                true,                  // softMaxBreached
                0.42,                  // pauseMarkEndMs
                2400, 200);            // minor/major cycles
        d.stallAllocHotspots.add(new ZgcDiagnosis.AllocHotspot("com.example.Foo.bar(Foo.java:42)", 120, 35.2));
        d.stallAllocHotspots.add(new ZgcDiagnosis.AllocHotspot("com.example.Bar.baz(Bar.java:10)", 80, 20.1));

        Path file = tmp.resolve("zgc-baseline.txt");
        ZgcBaseline.save(file, d, 12345);

        ZgcBaseline loaded = ZgcBaseline.load(file);

        assertEquals(12345, loaded.pid);
        assertTrue(loaded.generational);
        assertEquals(d.heapCommittedBytes, loaded.heapCommittedBytes);
        assertEquals(d.softMaxHeapBytes,   loaded.softMaxHeapBytes);
        assertEquals(d.maxHeapBytes,       loaded.maxHeapBytes);
        assertEquals(d.minorCycles,        loaded.minorCycles);
        assertEquals(d.majorCycles,        loaded.majorCycles);
        assertEquals(d.avgCycleIntervalSec, loaded.avgCycleIntervalSec, 1e-5);
        assertEquals(d.avgCycleDurationSec, loaded.avgCycleDurationSec, 1e-5);
        assertEquals(d.pauseMarkStartMs,    loaded.pauseMarkStartMs,    1e-5);
        assertEquals(d.pauseMarkEndMs,      loaded.pauseMarkEndMs,      1e-5);
        assertEquals(d.pauseRelocateStartMs,loaded.pauseRelocateStartMs,1e-5);
        assertEquals(3,    loaded.stallCount);
        assertTrue(loaded.stallMaxMs > 0);
        assertEquals("http-worker-2", loaded.stallMaxThread); // worst stall (10+2=12ms)
        assertTrue(loaded.softMaxBreached);
        assertFalse(loaded.cycleOverlap);
        assertEquals(2, loaded.topAllocFrames.size());
        assertTrue(loaded.topAllocFrames.get(0).contains("com.example.Foo.bar"));
        assertNotNull(loaded.capturedAt);
    }

    // ── Test 2: diff on identical snapshots → all INFO, no REGRESSION ─────────

    @Test
    void diffIdenticalSnapshotsAllInfoNoRegression(@TempDir Path tmp) throws IOException {
        ZgcDiagnosis d = buildDiagnosis(0, 2L << 30, 4L << 30, false, 0.30, 1200, 100);
        Path file = tmp.resolve("base.txt");
        ZgcBaseline.save(file, d, 1);
        ZgcBaseline baseline = ZgcBaseline.load(file);

        // Current = identical values
        ZgcDiagnosis current = buildDiagnosis(0, 2L << 30, 4L << 30, false, 0.30, 1200, 100);

        List<ZgcBaseline.DiffRow> rows = ZgcBaseline.diff(baseline, current);

        assertFalse(rows.isEmpty());
        for (ZgcBaseline.DiffRow row : rows) {
            assertNotEquals(ZgcBaseline.Severity.REGRESSION, row.severity(),
                    "Expected no REGRESSION for row: " + row.label());
        }
    }

    // ── Test 3: stalls 0→5 → stalls row REGRESSION ───────────────────────────

    @Test
    void diffNewStallsProducesRegressionRow(@TempDir Path tmp) throws IOException {
        ZgcDiagnosis base = buildDiagnosis(0, 2L << 30, 4L << 30, false, 0.30, 1200, 100);
        Path file = tmp.resolve("base.txt");
        ZgcBaseline.save(file, base, 1);
        ZgcBaseline baseline = ZgcBaseline.load(file);

        ZgcDiagnosis current = buildDiagnosis(5, 2L << 30, 4L << 30, false, 0.30, 1200, 100);

        List<ZgcBaseline.DiffRow> rows = ZgcBaseline.diff(baseline, current);

        ZgcBaseline.DiffRow stallRow = rows.stream()
                .filter(r -> "stallCount".equals(r.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("stallCount row missing"));

        assertEquals(ZgcBaseline.Severity.REGRESSION, stallRow.severity(),
                "stalls 0→5 should be REGRESSION");
        assertEquals("0", stallRow.baselineValue());
        assertEquals("5", stallRow.currentValue());
    }

    // ── Test 4: committed past softMax → committed row REGRESSION ─────────────

    @Test
    void diffCommittedPastSoftMaxProducesRegressionRow(@TempDir Path tmp) throws IOException {
        long softMax = 4L * 1024 * 1024 * 1024; // 4 GB
        // baseline: committed = 3 GB, within softMax
        ZgcDiagnosis base = buildDiagnosis(0, 3L * 1024 * 1024 * 1024, softMax, false, 0.30, 1200, 100);
        Path file = tmp.resolve("base.txt");
        ZgcBaseline.save(file, base, 1);
        ZgcBaseline baseline = ZgcBaseline.load(file);

        // current: committed = 4.5 GB, past softMax → softMaxBreached=true
        ZgcDiagnosis current = buildDiagnosis(0,
                (long)(4.5 * 1024 * 1024 * 1024), softMax, true, 0.30, 1200, 100);

        List<ZgcBaseline.DiffRow> rows = ZgcBaseline.diff(baseline, current);

        ZgcBaseline.DiffRow committedRow = rows.stream()
                .filter(r -> "heapCommitted".equals(r.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("heapCommitted row missing"));

        assertEquals(ZgcBaseline.Severity.REGRESSION, committedRow.severity(),
                "committed past softMax should be REGRESSION");
    }
}
