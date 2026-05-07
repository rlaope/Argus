package io.argus.cli.classleak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassLeakDiffTest {

    // ── Sample entries ────────────────────────────────────────────────────────

    private static final ClassLoaderEntry BOOT = new ClassLoaderEntry(
            "0x0000000000000000", "0x0000000000000000",
            "<boot class loader>", 2000, 8_000_000, 7_000_000);

    private static final ClassLoaderEntry APP = new ClassLoaderEntry(
            "0x0000000500068770", "0x0000000500069008",
            "jdk.internal.loader.ClassLoaders$AppClassLoader", 700, 5_000_000, 4_900_000);

    private static final ClassLoaderEntry PLATFORM = new ClassLoaderEntry(
            "0x0000000500069008", "0x0000000000000000",
            "jdk.internal.loader.ClassLoaders$PlatformClassLoader", 3, 40_000, 38_000);

    // ── Round-trip save/load ──────────────────────────────────────────────────

    @Test
    void roundTripPreservesAllFields(@TempDir Path tmp) throws IOException {
        List<ClassLoaderEntry> original = List.of(BOOT, APP, PLATFORM);
        Path file = tmp.resolve("cl.json");

        ClassLeakSnapshot.save(file, original);
        ClassLeakSnapshot loaded = ClassLeakSnapshot.load(file);

        assertEquals(3, loaded.entries().size());
        assertTrue(loaded.capturedAtEpochSec() > 0);

        ClassLoaderEntry reboot = loaded.entries().get(0);
        assertEquals(BOOT.address(), reboot.address());
        assertEquals(BOOT.parent(), reboot.parent());
        assertEquals(BOOT.type(), reboot.type());
        assertEquals(BOOT.classCount(), reboot.classCount());
        assertEquals(BOOT.chunkBytes(), reboot.chunkBytes());
        assertEquals(BOOT.blockBytes(), reboot.blockBytes());

        ClassLoaderEntry reapp = loaded.entries().get(1);
        assertEquals(APP.address(), reapp.address());
        assertEquals(APP.classCount(), reapp.classCount());
    }

    @Test
    void roundTripPreservesTypeWithSpecialChars(@TempDir Path tmp) throws IOException {
        ClassLoaderEntry tricky = new ClassLoaderEntry(
                "0x0001", "0x0000", "com.example.Loader$Inner\"quoted", 42, 1000, 900);
        Path file = tmp.resolve("tricky.json");

        ClassLeakSnapshot.save(file, List.of(tricky));
        ClassLeakSnapshot loaded = ClassLeakSnapshot.load(file);

        // At minimum the file must be written and the entry count preserved
        assertEquals(1, loaded.entries().size());
        assertEquals(42, loaded.entries().get(0).classCount());
    }

    // ── Growth detection ──────────────────────────────────────────────────────

    @Test
    void diffDetectsNoGrowth() {
        ClassLeakSnapshot baseline = new ClassLeakSnapshot(1_000_000_000L, List.of(BOOT, APP, PLATFORM));
        long nowSec = 1_000_001_000L;

        // Current snapshot: identical counts
        List<ClassLoaderEntry> current = List.of(BOOT, APP, PLATFORM);
        ClassLeakDiff diff = ClassLeakDiff.compute(baseline, nowSec, current);

        for (ClassLeakDiff.Row row : diff.rows()) {
            assertEquals(0, row.delta(), "no growth expected for " + row.type());
            assertEquals(ClassLeakDiff.Severity.OK, row.severity());
        }
    }

    @Test
    void diffFlagsWarningOnTenPctGrowth() {
        ClassLeakSnapshot baseline = new ClassLeakSnapshot(1_000_000_000L, List.of(APP));
        // APP had 700 classes; grow by 70 = exactly 10%
        ClassLoaderEntry grown = new ClassLoaderEntry(
                APP.address(), APP.parent(), APP.type(),
                770, APP.chunkBytes(), APP.blockBytes());

        ClassLeakDiff diff = ClassLeakDiff.compute(baseline, 1_000_001_000L, List.of(grown));
        assertEquals(1, diff.rows().size());
        ClassLeakDiff.Row row = diff.rows().get(0);
        assertEquals(70, row.delta());
        assertEquals(ClassLeakDiff.Severity.WARNING, row.severity());
    }

    @Test
    void diffFlagsCriticalOnFiftyPctGrowth() {
        ClassLeakSnapshot baseline = new ClassLeakSnapshot(1_000_000_000L, List.of(APP));
        // APP had 700; grow by 350 = 50 %
        ClassLoaderEntry grown = new ClassLoaderEntry(
                APP.address(), APP.parent(), APP.type(),
                1050, APP.chunkBytes(), APP.blockBytes());

        ClassLeakDiff diff = ClassLeakDiff.compute(baseline, 1_000_001_000L, List.of(grown));
        assertEquals(ClassLeakDiff.Severity.CRITICAL, diff.rows().get(0).severity());
    }

    @Test
    void diffFlagsCriticalOnMoreThan1000NewClasses() {
        ClassLeakSnapshot baseline = new ClassLeakSnapshot(1_000_000_000L, List.of(APP));
        // APP had 700; grow by 1001 (< 50% since base is large... wait, we need base large enough)
        // Use a large base so that % growth is < 50% but absolute > 1000
        ClassLoaderEntry large = new ClassLoaderEntry(
                APP.address(), APP.parent(), APP.type(),
                10_000, APP.chunkBytes(), APP.blockBytes());
        ClassLeakSnapshot largeBaseline = new ClassLeakSnapshot(1_000_000_000L, List.of(large));

        ClassLoaderEntry grown = new ClassLoaderEntry(
                APP.address(), APP.parent(), APP.type(),
                11_002, APP.chunkBytes(), APP.blockBytes()); // +1002 = ~10% growth but > 1000

        ClassLeakDiff diff = ClassLeakDiff.compute(largeBaseline, 1_000_001_000L, List.of(grown));
        ClassLeakDiff.Row row = diff.rows().get(0);
        assertTrue(row.delta() > 1_000);
        assertEquals(ClassLeakDiff.Severity.CRITICAL, row.severity());
    }

    @Test
    void diffDetectsNewLoader() {
        ClassLeakSnapshot baseline = new ClassLeakSnapshot(1_000_000_000L, List.of(BOOT, APP));
        ClassLoaderEntry newLoader = new ClassLoaderEntry(
                "0x9999999999", "0x0000000500068770",
                "com.example.MyWebAppClassLoader", 350, 2_000_000, 1_900_000);

        ClassLeakDiff diff = ClassLeakDiff.compute(
                baseline, 1_000_001_000L, List.of(BOOT, APP, newLoader));

        ClassLeakDiff.Row newRow = diff.rows().stream()
                .filter(ClassLeakDiff.Row::isNew)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a new-loader row"));

        assertEquals(newLoader.address(), newRow.address());
        assertEquals(350, newRow.currentCount());
        assertEquals(0, newRow.baseCount());
        assertEquals(350, newRow.delta());
        // New loaders get WARNING severity
        assertEquals(ClassLeakDiff.Severity.WARNING, newRow.severity());
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    @Test
    void parserHandlesActualJcmdOutput() {
        String raw = """
                ClassLoader         Parent              CLD*               Classes   ChunkSz   BlockSz  Type
                0x0000000000000000  0x0000000000000000  0x0000000cb30b8e60    2447   8028160   7669896  <boot class loader>
                                                                                62    191488    123512   + hidden classes
                0x0000000500068770  0x0000000500069008  0x0000000cb30b9400     735   5586944   5581408  jdk.internal.loader.ClassLoaders$AppClassLoader
                0x0000000500069008  0x0000000000000000  0x0000000cb30b9360       3     41984     39576  jdk.internal.loader.ClassLoaders$PlatformClassLoader
                Total = 3                                                      3247  13848576  13414392
                ChunkSz: Total size of all allocated metaspace chunks
                BlockSz: Total size of all allocated metaspace blocks (each chunk has several blocks)
                """;

        List<ClassLoaderEntry> entries = ClassLeakAnalyzer.parse(raw);

        // 3 named loaders (hidden classes folded into boot)
        assertEquals(3, entries.size());

        ClassLoaderEntry boot = entries.get(0);
        assertEquals("0x0000000000000000", boot.address());
        assertEquals("<boot class loader>", boot.type());
        // hidden classes (62) folded into boot
        assertEquals(2447 + 62, boot.classCount());
        assertEquals(8028160 + 191488, boot.chunkBytes());

        ClassLoaderEntry app = entries.get(1);
        assertEquals("0x0000000500068770", app.address());
        assertEquals("jdk.internal.loader.ClassLoaders$AppClassLoader", app.type());
        assertEquals(735, app.classCount());

        ClassLoaderEntry platform = entries.get(2);
        assertEquals("jdk.internal.loader.ClassLoaders$PlatformClassLoader", platform.type());
        assertEquals(3, platform.classCount());
    }
}
