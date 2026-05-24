package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.diagnostics.jcmd.JcmdExecutor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotCommandTest {

    private static final Messages MESSAGES = new Messages("en");

    private static CliConfig defaultConfig() {
        return new CliConfig("en", "auto", false, "text", 9202);
    }

    private static ProviderRegistry emptyRegistry() {
        return new ProviderRegistry();
    }

    // -------------------------------------------------------------------------
    // --safe mode (default): tar.gz produced, manifest has mode=safe, heap skipped
    // -------------------------------------------------------------------------

    @Test
    void safeMode_producesTarGzWithManifest(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(JcmdExecutor.isJcmdAvailable(),
                "jcmd not available in this environment");

        long selfPid = ProcessHandle.current().pid();
        String outDir = tmp.resolve("snap-out").toString();

        SnapshotCommand cmd = new SnapshotCommand();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            cmd.execute(new String[]{String.valueOf(selfPid), "--output=" + outDir, "--safe"},
                    defaultConfig(), emptyRegistry(), MESSAGES);
        } finally {
            System.setOut(orig);
        }

        Path outPath = Path.of(outDir);
        assertTrue(Files.exists(outPath), "Output directory must exist");
        List<Path> archives = Files.list(outPath)
                .filter(p -> p.getFileName().toString().endsWith(".tar.gz"))
                .collect(Collectors.toList());
        assertEquals(1, archives.size(), "Exactly one tar.gz must be produced");

        Path archive = archives.get(0);
        assertTrue(Files.size(archive) > 1024, "tar.gz must be > 1 KB");

        Map<String, byte[]> entries = readTarGz(archive);

        assertTrue(entries.containsKey("manifest.json"), "manifest.json must be in archive");
        assertTrue(entries.containsKey("info.txt"), "info.txt must be in archive");
        assertTrue(entries.containsKey("threads.txt"), "threads.txt must be in archive");
        assertTrue(entries.containsKey("histo.txt"), "histo.txt must be in archive");
        assertTrue(entries.containsKey("doctor.txt"), "doctor.txt must be in archive");
        assertFalse(entries.containsKey("heap.hprof"), "heap.hprof must NOT be in archive in --safe mode");

        String manifestJson = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifestJson.contains("\"mode\": \"safe\""), "manifest must record mode=safe");
        assertTrue(manifestJson.contains("\"schemaVersion\": 1"), "manifest must have schemaVersion=1");
        assertTrue(manifestJson.contains("\"name\": \"heap.hprof\""), "manifest must list heap.hprof item");
        assertTrue(manifestJson.contains("\"status\": \"skipped\""), "heap.hprof status must be skipped");
    }

    // -------------------------------------------------------------------------
    // schemaVersion=1 in manifest (no explicit --safe flag — default is safe)
    // -------------------------------------------------------------------------

    @Test
    void safeMode_manifestSchemaVersion(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(JcmdExecutor.isJcmdAvailable(),
                "jcmd not available in this environment");

        long selfPid = ProcessHandle.current().pid();
        String outDir = tmp.resolve("snap-schema").toString();

        SnapshotCommand cmd = new SnapshotCommand();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            cmd.execute(new String[]{String.valueOf(selfPid), "--output=" + outDir},
                    defaultConfig(), emptyRegistry(), MESSAGES);
        } finally {
            System.setOut(orig);
        }

        Path archive = Files.list(Path.of(outDir))
                .filter(p -> p.getFileName().toString().endsWith(".tar.gz"))
                .findFirst().orElseThrow();

        Map<String, byte[]> entries = readTarGz(archive);
        assertTrue(entries.containsKey("manifest.json"), "manifest.json must be present");
        String manifestJson = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifestJson.contains("\"schemaVersion\": 1"), "schemaVersion must be 1");
    }

    // -------------------------------------------------------------------------
    // --safe and --full together must fail with exit code != 0
    // -------------------------------------------------------------------------

    @Test
    void safeAndFullConflict_exitsWithError(@TempDir Path tmp) {
        SnapshotCommand cmd = new SnapshotCommand();
        String outDir = tmp.resolve("snap-conflict").toString();

        PrintStream origErr = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuf));
        PrintStream origOut = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            CommandExitException ex = assertThrows(CommandExitException.class, () ->
                    cmd.execute(new String[]{"--safe", "--full", "--output=" + outDir},
                            defaultConfig(), emptyRegistry(), MESSAGES));
            assertTrue(ex.exitCode() != 0, "Must exit with non-zero code");
        } finally {
            System.setErr(origErr);
            System.setOut(origOut);
        }

        String errOutput = errBuf.toString();
        assertTrue(errOutput.contains("mutually exclusive") || errOutput.contains("--safe"),
                "Error message must mention the conflict");
    }

    // -------------------------------------------------------------------------
    // Minimal tar.gz reader using only JDK (GZIPInputStream + manual tar parsing)
    // -------------------------------------------------------------------------

    private static Map<String, byte[]> readTarGz(Path archive) throws Exception {
        Map<String, byte[]> result = new HashMap<>();
        try (InputStream fis = Files.newInputStream(archive);
             GZIPInputStream gzip = new GZIPInputStream(fis)) {
            byte[] headerBuf = new byte[512];
            while (true) {
                int read = readFully(gzip, headerBuf);
                if (read < 512) break;
                // Check for end-of-archive (two zero blocks)
                boolean allZero = true;
                for (byte b : headerBuf) { if (b != 0) { allZero = false; break; } }
                if (allZero) break;

                // Parse name (offset 0, 100 bytes, NUL-terminated)
                String name = parseName(headerBuf, 0, 100);
                if (name.isEmpty()) break;

                // Parse size (offset 124, 12 bytes, octal)
                long size = parseOctal(headerBuf, 124, 12);

                // Read file data
                byte[] data = new byte[(int) size];
                readFully(gzip, data);

                // Skip padding to 512-byte boundary
                int remainder = (int)(size % 512);
                if (remainder != 0) {
                    byte[] pad = new byte[512 - remainder];
                    readFully(gzip, pad);
                }

                result.put(name, data);
            }
        }
        return result;
    }

    private static int readFully(InputStream in, byte[] buf) throws Exception {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static String parseName(byte[] buf, int offset, int length) {
        int end = offset;
        while (end < offset + length && buf[end] != 0) end++;
        return new String(buf, offset, end - offset, StandardCharsets.US_ASCII).trim();
    }

    private static long parseOctal(byte[] buf, int offset, int length) {
        String s = new String(buf, offset, length, StandardCharsets.US_ASCII).trim();
        // Strip NUL and spaces
        s = s.replaceAll("[\\x00 ]", "");
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); }
        catch (NumberFormatException e) { return 0; }
    }
}
