package io.argus.diagnostics.g1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class G1BaselineTest {

    @Test
    void save_then_load_roundtrip(@TempDir Path tmp) throws Exception {
        G1Diagnosis d = new G1Diagnosis();
        d.usingG1 = true;
        d.regionSizeMb = 16;
        d.targetPauseMs = 200;
        d.ihopPercent = 45;
        d.adaptiveIhop = true;
        d.heapCommittedBytes = 4L * 1024 * 1024 * 1024;
        d.maxHeapBytes = 8L * 1024 * 1024 * 1024;
        d.totalRegions = 2048;
        d.edenRegions = 400;
        d.youngCycles = 120;
        d.mixedCycles = 8;
        d.maxPauseMs = 120.4;
        d.avgMmuPercent = 95.2;
        d.minMmuPercent = 88.1;

        Path file = tmp.resolve("g1.baseline");
        G1Baseline.save(file, d, 12345);

        G1Baseline loaded = G1Baseline.load(file);
        assertEquals(12345, loaded.pid);
        assertEquals(16, loaded.regionSizeMb);
        assertEquals(200, loaded.targetPauseMs);
        assertEquals(45, loaded.ihopPercent);
        assertTrue(loaded.adaptiveIhop);
        assertEquals(4L * 1024 * 1024 * 1024, loaded.heapCommittedBytes);
        assertEquals(2048, loaded.totalRegions);
        assertEquals(120, loaded.youngCycles);
        assertEquals(8, loaded.mixedCycles);
        assertEquals(120.4, loaded.maxPauseMs, 0.01);
        assertEquals(95.2, loaded.avgMmuPercent, 0.01);
    }

    @Test
    void diff_marks_new_full_gc_as_regression(@TempDir Path tmp) throws Exception {
        G1Diagnosis base = new G1Diagnosis();
        base.usingG1 = true;
        base.fullGcSeen = false;
        Path f = tmp.resolve("base.props");
        G1Baseline.save(f, base, 1);

        G1Diagnosis current = new G1Diagnosis();
        current.usingG1 = true;
        current.fullGcSeen = true;
        current.fullGcCycles = 1;

        List<G1Baseline.DiffRow> rows = G1Baseline.diff(G1Baseline.load(f), current);

        G1Baseline.DiffRow fullRow = rows.stream()
                .filter(r -> "fullGcSeen".equals(r.label())).findFirst().orElseThrow();
        assertEquals(G1Baseline.Severity.REGRESSION, fullRow.severity());
        assertEquals("NEW", fullRow.delta());
    }

    @Test
    void diff_marks_new_evacuation_failure_as_regression(@TempDir Path tmp) throws Exception {
        G1Diagnosis base = new G1Diagnosis();
        Path f = tmp.resolve("base.props");
        G1Baseline.save(f, base, 1);

        G1Diagnosis current = new G1Diagnosis();
        current.evacuationFailureSeen = true;

        List<G1Baseline.DiffRow> rows = G1Baseline.diff(G1Baseline.load(f), current);
        G1Baseline.DiffRow row = rows.stream()
                .filter(r -> "evacuationFailure".equals(r.label())).findFirst().orElseThrow();
        assertEquals(G1Baseline.Severity.REGRESSION, row.severity());
    }

    @Test
    void diff_marks_max_pause_increase_above_50pct_as_regression(@TempDir Path tmp) throws Exception {
        G1Diagnosis base = new G1Diagnosis();
        base.maxPauseMs = 100;
        Path f = tmp.resolve("base.props");
        G1Baseline.save(f, base, 1);

        G1Diagnosis current = new G1Diagnosis();
        current.maxPauseMs = 200; // +100% — well over 50%

        List<G1Baseline.DiffRow> rows = G1Baseline.diff(G1Baseline.load(f), current);
        G1Baseline.DiffRow row = rows.stream()
                .filter(r -> "maxPause".equals(r.label())).findFirst().orElseThrow();
        assertEquals(G1Baseline.Severity.REGRESSION, row.severity());
    }

    @Test
    void diff_marks_mmu_drop_above_20pct_as_regression(@TempDir Path tmp) throws Exception {
        G1Diagnosis base = new G1Diagnosis();
        base.minMmuPercent = 90;
        Path f = tmp.resolve("base.props");
        G1Baseline.save(f, base, 1);

        G1Diagnosis current = new G1Diagnosis();
        current.minMmuPercent = 60; // dropped 33%

        List<G1Baseline.DiffRow> rows = G1Baseline.diff(G1Baseline.load(f), current);
        G1Baseline.DiffRow row = rows.stream()
                .filter(r -> "minMmu".equals(r.label())).findFirst().orElseThrow();
        assertEquals(G1Baseline.Severity.REGRESSION, row.severity());
    }
}
