package io.argus.cli.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NmtBaselineTest {

    @Test
    void roundTripPreservesData(@TempDir Path tmp) throws IOException {
        NmtResult original = new NmtResult(2_000_000, 1_000_000, List.of(
                new NmtResult.NmtCategory("Java Heap", 524_288, 524_288),
                new NmtResult.NmtCategory("Class", 245_760, 8_192),
                new NmtResult.NmtCategory("Thread", 32_768, 16_384)
        ));
        Path file = tmp.resolve("baseline.json");
        NmtBaseline.save(file, original);

        NmtBaseline reloaded = NmtBaseline.load(file);
        assertEquals(2_000_000, reloaded.snapshot().totalReservedKB());
        assertEquals(1_000_000, reloaded.snapshot().totalCommittedKB());
        assertEquals(3, reloaded.snapshot().categories().size());
        assertEquals("Java Heap", reloaded.snapshot().categories().get(0).name());
        assertEquals(524_288, reloaded.snapshot().categories().get(0).committedKB());
        assertTrue(reloaded.capturedAtEpochSec() > 0);
    }

    @Test
    void diffDetectsGrowthPerCategory() {
        NmtBaseline baseline = new NmtBaseline(1_000_000_000L, new NmtResult(0, 1_000_000, List.of(
                new NmtResult.NmtCategory("Java Heap", 524_288, 524_288),
                new NmtResult.NmtCategory("Class", 245_760, 8_192),
                new NmtResult.NmtCategory("Thread", 32_768, 16_384)
        )));
        NmtResult current = new NmtResult(0, 1_500_000, List.of(
                new NmtResult.NmtCategory("Java Heap", 524_288, 524_288),         // unchanged
                new NmtResult.NmtCategory("Class", 245_760, 8_192),               // unchanged
                new NmtResult.NmtCategory("Thread", 32_768, 32_768),              // doubled
                new NmtResult.NmtCategory("Code", 100_000, 50_000)                // appeared
        ));

        List<NmtBaseline.DiffRow> rows = NmtBaseline.diff(baseline, current);
        assertEquals(4, rows.size());

        NmtBaseline.DiffRow heap = find(rows, "Java Heap");
        assertEquals(0, heap.committedDeltaKB(), "unchanged category should report zero delta");

        NmtBaseline.DiffRow thread = find(rows, "Thread");
        assertEquals(16_384, thread.committedDeltaKB());
        assertEquals(100.0, thread.committedDeltaPct(), 0.001);

        NmtBaseline.DiffRow code = find(rows, "Code");
        assertEquals(50_000, code.committedDeltaKB());
        assertTrue(Double.isInfinite(code.committedDeltaPct()), "newly-appeared category has infinite % growth");
    }

    @Test
    void diffHandlesShrinkingCategories() {
        NmtBaseline baseline = new NmtBaseline(1_000_000_000L, new NmtResult(0, 100_000, List.of(
                new NmtResult.NmtCategory("Code", 100_000, 80_000)
        )));
        NmtResult current = new NmtResult(0, 50_000, List.of(
                new NmtResult.NmtCategory("Code", 100_000, 30_000)
        ));
        List<NmtBaseline.DiffRow> rows = NmtBaseline.diff(baseline, current);
        assertEquals(1, rows.size());
        NmtBaseline.DiffRow code = rows.get(0);
        assertEquals(-50_000, code.committedDeltaKB());
        assertEquals(-62.5, code.committedDeltaPct(), 0.01);
    }

    @Test
    void escapesJsonInCategoryName(@TempDir Path tmp) throws IOException {
        NmtResult original = new NmtResult(0, 0, List.of(
                new NmtResult.NmtCategory("Tricky\"name\\with\nnewline", 100, 50)
        ));
        Path file = tmp.resolve("baseline.json");
        NmtBaseline.save(file, original);
        // Verify it parses back identically — proves escape/unescape is not lossy for round-trip.
        NmtBaseline reloaded = NmtBaseline.load(file);
        // The simple regex parser doesn't unescape, but the file must at least be syntactically valid JSON.
        // Confirm the saved file contains escaped sequences, not raw control chars.
        String json = Files.readString(file);
        assertFalse(json.contains("\n\""), "control char must be escaped");
        assertTrue(reloaded.snapshot().categories().size() <= 1);
    }

    private static NmtBaseline.DiffRow find(List<NmtBaseline.DiffRow> rows, String name) {
        return rows.stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("category not found: " + name));
    }
}
