package io.argus.cli.command;

import io.argus.cli.doctor.JvmSnapshot;
import io.argus.cli.model.NmtResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompareCommandTest {

    // Minimal JvmSnapshot with just the fields snapshotToJson uses
    private static JvmSnapshot minimalSnapshot(long heapUsed, long heapMax, long gcCount,
                                               double gcOverhead, double cpuLoad,
                                               int threads, int deadlocked, int classes,
                                               String gcAlgo) {
        // uptimeMs drives gcOverheadPercent: gcOverheadPercent = totalGcTimeMs / uptimeMs * 100
        // We want gcOverhead as a raw double stored in gcOverhead field — but the snapshot
        // computes it from totalGcTimeMs/uptimeMs. Store gcOverhead≈0 and verify other fields.
        return new JvmSnapshot(
                heapUsed, heapMax, 0L, 0L,
                Map.of(), List.of(),
                gcCount, 0L, 1000L,  // uptimeMs=1000, totalGcTimeMs=0 → gcOverheadPercent=0
                cpuLoad, -1.0, Runtime.getRuntime().availableProcessors(),
                threads, 0, 0,
                Map.of(), deadlocked,
                List.of(),
                classes, 0L, 0L, 0,
                "", "", gcAlgo, List.of()
        );
    }

    // -------------------------------------------------------------------------
    // snapshotToJson — NMT block present
    // -------------------------------------------------------------------------

    @Test
    void snapshotToJson_includesNmtBlock() {
        CompareCommand cmd = new CompareCommand();
        JvmSnapshot snap = minimalSnapshot(512L * 1024 * 1024, 1024L * 1024 * 1024,
                42, 0.0, 0.25, 20, 0, 300, "G1");
        NmtResult nmt = new NmtResult(2_000_000, 1_000_000, List.of(
                new NmtResult.NmtCategory("Java Heap", 1_048_576, 524_288),
                new NmtResult.NmtCategory("Class", 245_760, 8_192)
        ));

        String json = cmd.snapshotToJson(snap, nmt);

        assertTrue(json.contains("\"nmt\""), "JSON must contain nmt key");
        assertTrue(json.contains("\"totalReservedKB\":2000000"), "totalReservedKB must be present");
        assertTrue(json.contains("\"totalCommittedKB\":1000000"), "totalCommittedKB must be present");
        assertTrue(json.contains("\"Java Heap\""), "Java Heap category must be present");
        assertTrue(json.contains("\"Class\""), "Class category must be present");
        assertTrue(json.contains("\"heapUsed\":" + (512L * 1024 * 1024)),
                "heapUsed must still be present");
    }

    @Test
    void snapshotToJson_withoutNmt_omitsNmtBlock() {
        CompareCommand cmd = new CompareCommand();
        JvmSnapshot snap = minimalSnapshot(256L * 1024 * 1024, 512L * 1024 * 1024,
                10, 0.0, 0.1, 8, 0, 100, "ZGC");

        String json = cmd.snapshotToJson(snap, null);

        assertFalse(json.contains("\"nmt\""), "No NMT block expected when nmt is null");
        assertTrue(json.contains("\"heapUsed\""), "heapUsed still required");
        assertTrue(json.contains("ZGC"), "gcAlgorithm must be present");
    }

    // -------------------------------------------------------------------------
    // loadBaselineNmt — round-trip
    // -------------------------------------------------------------------------

    @Test
    void loadBaselineNmt_roundTrip(@TempDir Path tmp) throws IOException {
        CompareCommand cmd = new CompareCommand();
        JvmSnapshot snap = minimalSnapshot(128L * 1024 * 1024, 256L * 1024 * 1024,
                5, 0.0, -1.0, 4, 0, 50, "Shenandoah");
        NmtResult nmt = new NmtResult(3_000_000, 1_500_000, List.of(
                new NmtResult.NmtCategory("Java Heap", 1_048_576, 524_288),
                new NmtResult.NmtCategory("Thread", 32_768, 16_384),
                new NmtResult.NmtCategory("Code", 262_144, 131_072)
        ));

        String json = cmd.snapshotToJson(snap, nmt);
        Path file = tmp.resolve("baseline.json");
        Files.writeString(file, json);

        NmtResult loaded = CompareCommand.loadBaselineNmt(file.toString());

        assertNotNull(loaded, "loadBaselineNmt must parse the nmt block");
        assertEquals(3_000_000, loaded.totalReservedKB());
        assertEquals(1_500_000, loaded.totalCommittedKB());
        assertEquals(3, loaded.categories().size());

        NmtResult.NmtCategory heap = findCategory(loaded.categories(), "Java Heap");
        assertEquals(1_048_576, heap.reservedKB());
        assertEquals(524_288, heap.committedKB());

        NmtResult.NmtCategory thread = findCategory(loaded.categories(), "Thread");
        assertEquals(16_384, thread.committedKB());

        NmtResult.NmtCategory code = findCategory(loaded.categories(), "Code");
        assertEquals(131_072, code.committedKB());
    }

    @Test
    void loadBaselineNmt_returnsNullForOldSnapshotWithoutNmtBlock(@TempDir Path tmp) throws IOException {
        // Simulate a pre-NMT snapshot (old format without "nmt" key)
        String oldJson = "{\"heapUsed\":134217728,\"heapMax\":268435456,"
                + "\"gcOverhead\":0.0,\"gcCount\":5,\"gcAlgorithm\":\"G1\","
                + "\"cpuLoad\":0.1,\"threadCount\":4,\"blockedThreads\":0,"
                + "\"deadlockedThreads\":0,\"loadedClasses\":50}";
        Path file = tmp.resolve("old-baseline.json");
        Files.writeString(file, oldJson);

        NmtResult result = CompareCommand.loadBaselineNmt(file.toString());

        assertNull(result, "Old baseline without nmt block must return null (forward-compatible)");
    }

    @Test
    void loadBaselineNmt_returnsNullForNonexistentFile() {
        NmtResult result = CompareCommand.loadBaselineNmt("/nonexistent/path/baseline.json");
        assertNull(result, "Missing file must return null, not throw");
    }

    // -------------------------------------------------------------------------
    // snapshotToJson + loadBaselineNmt — heap fields remain accessible
    // -------------------------------------------------------------------------

    @Test
    void snapshotToJson_preservesAllHeapFields(@TempDir Path tmp) throws IOException {
        CompareCommand cmd = new CompareCommand();
        JvmSnapshot snap = minimalSnapshot(
                400L * 1024 * 1024, 800L * 1024 * 1024,
                99, 0.0, 0.5, 32, 2, 500, "ParallelGC");
        NmtResult nmt = new NmtResult(500_000, 250_000, List.of(
                new NmtResult.NmtCategory("Java Heap", 200_000, 100_000)
        ));

        String json = cmd.snapshotToJson(snap, nmt);
        Path file = tmp.resolve("snap.json");
        Files.writeString(file, json);

        // Verify heap fields survive round-trip through the file (using raw string search)
        String persisted = Files.readString(file);
        assertTrue(persisted.contains("\"heapUsed\":" + (400L * 1024 * 1024)));
        assertTrue(persisted.contains("\"heapMax\":" + (800L * 1024 * 1024)));
        assertTrue(persisted.contains("\"gcCount\":99"));
        assertTrue(persisted.contains("\"threadCount\":32"));
        assertTrue(persisted.contains("\"deadlockedThreads\":2"));
        assertTrue(persisted.contains("\"loadedClasses\":500"));
        assertTrue(persisted.contains("\"gcAlgorithm\":\"ParallelGC\""));
        // NMT block also present
        assertTrue(persisted.contains("\"nmt\":{"));
        assertTrue(persisted.contains("\"Java Heap\""));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static NmtResult.NmtCategory findCategory(List<NmtResult.NmtCategory> cats, String name) {
        return cats.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Category not found: " + name));
    }
}
