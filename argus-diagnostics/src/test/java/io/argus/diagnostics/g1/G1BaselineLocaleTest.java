package io.argus.diagnostics.g1;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that G1Baseline save/load survives a non-English locale.
 *
 * <p>Without {@code Locale.ROOT} on the {@code String.format} calls, German /
 * French locales produce "42,5" while the parseDouble regex expects "42.5",
 * silently zeroing every floating-point field on reload.
 */
class G1BaselineLocaleTest {

    private Locale previous;

    @BeforeEach
    void switchLocale() {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(previous);
    }

    @Test
    void roundtrip_preserves_floats_in_german_locale(@TempDir Path tmp) throws Exception {
        G1Diagnosis d = new G1Diagnosis();
        d.usingG1 = true;
        d.regionSizeMb = 16;
        d.targetPauseMs = 200;
        d.heapCommittedBytes = 4L * 1024 * 1024 * 1024;
        d.maxPauseMs = 184.5;
        d.avgYoungPauseMs = 38.2;
        d.avgMmuPercent = 95.1;
        d.minMmuPercent = 88.3;
        d.predictedIhopPercent = 45.0;
        d.actualIhopPercent = 46.2;

        Path file = tmp.resolve("g1.baseline");
        G1Baseline.save(file, d, 12345);
        G1Baseline loaded = G1Baseline.load(file);

        // Without Locale.ROOT these would all be 0.0.
        assertEquals(184.5, loaded.maxPauseMs, 0.01);
        assertEquals(38.2, loaded.avgYoungPauseMs, 0.01);
        assertEquals(95.1, loaded.avgMmuPercent, 0.01);
        assertEquals(88.3, loaded.minMmuPercent, 0.01);
        assertEquals(45.0, loaded.predictedIhopPercent, 0.01);
        assertEquals(46.2, loaded.actualIhopPercent, 0.01);
    }

    @Test
    void diff_maxPause_marks_zero_to_high_as_regression(@TempDir Path tmp) throws Exception {
        // Baseline captured during warm-up with no GC events yet.
        G1Diagnosis base = new G1Diagnosis();
        base.maxPauseMs = 0;
        Path f = tmp.resolve("base.props");
        G1Baseline.save(f, base, 1);

        // Current capture shows 100ms pauses — 0 → 100 should be REGRESSION, not WARN.
        G1Diagnosis current = new G1Diagnosis();
        current.maxPauseMs = 100;

        List<G1Baseline.DiffRow> rows = G1Baseline.diff(G1Baseline.load(f), current);
        G1Baseline.DiffRow row = rows.stream()
                .filter(r -> "maxPause".equals(r.label())).findFirst().orElseThrow();
        assertEquals(G1Baseline.Severity.REGRESSION, row.severity());
    }

    @Test
    void diff_includes_ihop_mistimed_row(@TempDir Path tmp) throws Exception {
        G1Diagnosis base = new G1Diagnosis();
        base.ihopMistimed = false;
        Path f = tmp.resolve("base.props");
        G1Baseline.save(f, base, 1);

        G1Diagnosis current = new G1Diagnosis();
        current.ihopMistimed = true;

        List<G1Baseline.DiffRow> rows = G1Baseline.diff(G1Baseline.load(f), current);
        G1Baseline.DiffRow row = rows.stream()
                .filter(r -> "ihopMistimed".equals(r.label())).findFirst().orElseThrow();
        assertEquals(G1Baseline.Severity.REGRESSION, row.severity());
        assertEquals("NEW", row.delta());
    }
}
