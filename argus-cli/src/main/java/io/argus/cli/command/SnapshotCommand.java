package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.core.command.CommandGroup;
import io.argus.diagnostics.doctor.DoctorEngine;
import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.JvmSnapshotCollector;
import io.argus.diagnostics.jcmd.JcmdExecutor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Forensic snapshot bundle: collects multiple JVM diagnostics into a single tar.gz for incident response.
 */
public final class SnapshotCommand implements Command {

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    @Override
    public String name() { return "snapshot"; }

    @Override
    public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.snapshot.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        long pid = 0;
        String outputDir = null;
        boolean safe = true;
        boolean full = false;
        boolean help = false;
        boolean explicitSafe = false;

        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                help = true;
            } else if (arg.startsWith("--output=")) {
                outputDir = arg.substring(9);
            } else if (arg.equals("--full")) {
                full = true;
                safe = false;
            } else if (arg.equals("--safe")) {
                safe = true;
                explicitSafe = true;
            } else if (!arg.startsWith("--")) {
                try { pid = Long.parseLong(arg); } catch (NumberFormatException ignored) {}
            }
        }

        if (help) {
            System.out.println(messages.get("snapshot.usage"));
            return;
        }

        if (full && explicitSafe) {
            System.err.println(messages.get("snapshot.error.safeAndFullConflict"));
            throw new CommandExitException(1);
        }

        if (pid <= 0) pid = ProcessHandle.current().pid();

        String timestamp = TS.format(Instant.now());
        if (outputDir == null) outputDir = "./argus-snapshot-" + timestamp;

        Path outPath = Path.of(outputDir);
        try {
            Files.createDirectories(outPath);
        } catch (IOException e) {
            System.err.println("Cannot create output directory: " + e.getMessage());
            throw new CommandExitException(1);
        }

        String mode = full ? "full" : "safe";
        System.out.println(messages.get("snapshot.collecting", String.valueOf(pid)));

        List<ManifestItem> items = new ArrayList<>();
        List<byte[]> fileContents = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();

        // 1. info.txt
        collectJcmd(pid, "VM.info", "info.txt", "jcmd VM.info",
                items, fileNames, fileContents, messages);

        // 2. threads.txt — 3 dumps, 5s apart
        collectThreadDumps(pid, items, fileNames, fileContents, messages);

        // 3. histo.txt
        collectJcmd(pid, "GC.class_histogram", "histo.txt", "jcmd GC.class_histogram",
                items, fileNames, fileContents, messages);

        // 4. doctor.txt
        collectDoctor(pid, items, fileNames, fileContents, messages);

        // 5. heap.hprof (--full only)
        if (full) {
            collectHeapDump(pid, outPath, timestamp, items, fileNames, fileContents, messages);
        } else {
            String reason = messages.get("snapshot.error.heapDumpRequiresFull");
            items.add(new ManifestItem("heap.hprof", "jcmd GC.heap_dump", "skipped", 0, null, reason));
            System.out.println(messages.get("snapshot.item.skipped", "heap.hprof", reason));
        }

        // 6. Build manifest.json and prepend it
        String hostname = hostname();
        String jvmVersion = getJvmVersion(pid);
        String jvmArgs = getJvmArgs(pid);
        byte[] manifest = buildManifest(pid, jvmVersion, jvmArgs, hostname, mode, timestamp, items);
        fileNames.add(0, "manifest.json");
        fileContents.add(0, manifest);

        // 7. Bundle into tar.gz
        System.out.println(messages.get("snapshot.compressing"));
        String archiveName = "argus-snapshot-" + pid + "-" + timestamp + ".tar.gz";
        Path archivePath = outPath.resolve(archiveName);

        try {
            writeTarGz(archivePath, fileNames, fileContents);
        } catch (IOException e) {
            System.err.println("Failed to write archive: " + e.getMessage());
            throw new CommandExitException(1);
        }

        long archiveBytes;
        try { archiveBytes = Files.size(archivePath); }
        catch (IOException e) { archiveBytes = -1; }

        System.out.println(messages.get("snapshot.success",
                archivePath.toString(), formatBytes(archiveBytes)));
    }

    private void collectJcmd(long pid, String jcmdCmd, String fileName, String source,
                              List<ManifestItem> items, List<String> names,
                              List<byte[]> contents, Messages messages) {
        try {
            String out = JcmdExecutor.execute(pid, jcmdCmd);
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            items.add(new ManifestItem(fileName, source, "ok", bytes.length, null, null));
            names.add(fileName);
            contents.add(bytes);
            System.out.println(messages.get("snapshot.item.ok", fileName, formatBytes(bytes.length)));
        } catch (Exception e) {
            items.add(new ManifestItem(fileName, source, "error", 0, e.getMessage(), null));
            System.out.println(messages.get("snapshot.item.error", fileName, e.getMessage()));
        }
    }

    private void collectThreadDumps(long pid, List<ManifestItem> items, List<String> names,
                                    List<byte[]> contents, Messages messages) {
        StringBuilder sb = new StringBuilder();
        Exception lastError = null;
        for (int i = 1; i <= 3; i++) {
            try {
                String ts = Instant.now().toString();
                sb.append("=== dump ").append(i).append(" @ ").append(ts).append(" ===\n");
                sb.append(JcmdExecutor.execute(pid, "Thread.print")).append("\n");
                if (i < 3) Thread.sleep(5000);
            } catch (Exception e) {
                lastError = e;
                sb.append("ERROR: ").append(e.getMessage()).append("\n");
            }
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (lastError == null) {
            items.add(new ManifestItem("threads.txt", "jcmd Thread.print x3", "ok", bytes.length, null, null));
            System.out.println(messages.get("snapshot.item.ok", "threads.txt", formatBytes(bytes.length)));
        } else {
            items.add(new ManifestItem("threads.txt", "jcmd Thread.print x3", "error", bytes.length, lastError.getMessage(), null));
            System.out.println(messages.get("snapshot.item.error", "threads.txt", lastError.getMessage()));
        }
        names.add("threads.txt");
        contents.add(bytes);
    }

    private void collectDoctor(long pid, List<ManifestItem> items, List<String> names,
                               List<byte[]> contents, Messages messages) {
        try {
            var snapshot = JvmSnapshotCollector.collect(pid);
            List<Finding> findings = DoctorEngine.diagnose(snapshot);
            StringBuilder sb = new StringBuilder();
            if (findings.isEmpty()) {
                sb.append("All checks passed — JVM is healthy\n");
            } else {
                for (Finding f : findings) {
                    sb.append("[").append(f.severity().name()).append("] ")
                      .append(f.category()).append(": ").append(f.title()).append("\n");
                    if (!f.detail().isEmpty()) sb.append("  ").append(f.detail()).append("\n");
                    for (String rec : f.recommendations()) sb.append("  -> ").append(rec).append("\n");
                    sb.append("\n");
                }
            }
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            items.add(new ManifestItem("doctor.txt", "DoctorEngine.diagnose", "ok", bytes.length, null, null));
            names.add("doctor.txt");
            contents.add(bytes);
            System.out.println(messages.get("snapshot.item.ok", "doctor.txt", formatBytes(bytes.length)));
        } catch (Exception e) {
            byte[] bytes = ("ERROR: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            items.add(new ManifestItem("doctor.txt", "DoctorEngine.diagnose", "error", 0, e.getMessage(), null));
            names.add("doctor.txt");
            contents.add(bytes);
            System.out.println(messages.get("snapshot.item.error", "doctor.txt", e.getMessage()));
        }
    }

    private void collectHeapDump(long pid, Path outPath, String timestamp,
                                 List<ManifestItem> items, List<String> names,
                                 List<byte[]> contents, Messages messages) {
        Path tmpHprof = outPath.resolve("heap-" + pid + "-" + timestamp + ".hprof");
        try {
            JcmdExecutor.execute(pid, "GC.heap_dump filename=" + tmpHprof.toAbsolutePath());
            byte[] bytes = Files.readAllBytes(tmpHprof);
            Files.deleteIfExists(tmpHprof);
            items.add(new ManifestItem("heap.hprof", "jcmd GC.heap_dump", "ok", bytes.length, null, null));
            names.add("heap.hprof");
            contents.add(bytes);
            System.out.println(messages.get("snapshot.item.ok", "heap.hprof", formatBytes(bytes.length)));
        } catch (Exception e) {
            try { Files.deleteIfExists(tmpHprof); } catch (IOException ignored) {}
            items.add(new ManifestItem("heap.hprof", "jcmd GC.heap_dump", "error", 0, e.getMessage(), null));
            System.out.println(messages.get("snapshot.item.error", "heap.hprof", e.getMessage()));
        }
    }

    private static byte[] buildManifest(long pid, String jvmVersion, String jvmArgs,
                                        String hostname, String mode, String timestamp,
                                        List<ManifestItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": 1,\n");
        sb.append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"argusVersion\": \"").append(argusVersion()).append("\",\n");
        sb.append("  \"target\": {\n");
        sb.append("    \"pid\": ").append(pid).append(",\n");
        sb.append("    \"jvmVersion\": \"").append(escapeJson(jvmVersion)).append("\",\n");
        sb.append("    \"jvmArgs\": \"").append(escapeJson(jvmArgs)).append("\",\n");
        sb.append("    \"hostname\": \"").append(escapeJson(hostname)).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"mode\": \"").append(mode).append("\",\n");
        sb.append("  \"items\": [\n");
        for (int i = 0; i < items.size(); i++) {
            ManifestItem item = items.get(i);
            sb.append("    {");
            sb.append("\"name\": \"").append(escapeJson(item.name)).append("\", ");
            sb.append("\"source\": \"").append(escapeJson(item.source)).append("\", ");
            sb.append("\"status\": \"").append(item.status).append("\"");
            if ("ok".equals(item.status)) {
                sb.append(", \"bytes\": ").append(item.bytes);
            } else if ("skipped".equals(item.status) && item.reason != null) {
                sb.append(", \"reason\": \"").append(escapeJson(item.reason)).append("\"");
            } else if ("error".equals(item.status) && item.error != null) {
                sb.append(", \"error\": \"").append(escapeJson(item.error)).append("\"");
            }
            sb.append("}");
            if (i < items.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes a tar.gz archive using only JDK built-ins (GZIPOutputStream + manual POSIX tar blocks).
     * Each 512-byte header block encodes: name (100B), mode (8B), uid/gid (8B each),
     * size (12B octal), mtime (12B octal), checksum (8B), type flag (1B), link name (100B),
     * then the UStar magic + version + padding to 512 bytes.
     */
    private static void writeTarGz(Path dest, List<String> names, List<byte[]> contents) throws IOException {
        try (OutputStream fos = Files.newOutputStream(dest);
             GZIPOutputStream gzip = new GZIPOutputStream(fos)) {
            for (int i = 0; i < names.size(); i++) {
                writeTarEntry(gzip, names.get(i), contents.get(i));
            }
            // End-of-archive: two 512-byte zero blocks
            byte[] eoa = new byte[1024];
            gzip.write(eoa);
        }
    }

    private static void writeTarEntry(OutputStream out, String name, byte[] data) throws IOException {
        byte[] header = new byte[512];
        // name (offset 0, 100 bytes)
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 99));
        // mode (offset 100, 8 bytes): 0000644\0
        putOctal(header, 100, 8, 0644);
        // uid (offset 108, 8 bytes)
        putOctal(header, 108, 8, 0);
        // gid (offset 116, 8 bytes)
        putOctal(header, 116, 8, 0);
        // size (offset 124, 12 bytes)
        putOctal(header, 124, 12, data.length);
        // mtime (offset 136, 12 bytes)
        putOctal(header, 136, 12, System.currentTimeMillis() / 1000L);
        // checksum placeholder (offset 148, 8 bytes): 8 spaces
        Arrays.fill(header, 148, 156, (byte) ' ');
        // type flag (offset 156): '0' = regular file
        header[156] = '0';
        // UStar magic (offset 257, 6 bytes): "ustar\0"
        byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        // UStar version (offset 263, 2 bytes): "00"
        header[263] = '0';
        header[264] = '0';
        // Compute checksum over header with spaces in checksum field (already set above)
        int checksum = 0;
        for (byte b : header) checksum += (b & 0xFF);
        // Write checksum as 6 octal digits + NUL + space (POSIX convention)
        byte[] csOctal = String.format("%06o", checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(csOctal, 0, header, 148, 6);
        header[154] = 0;
        header[155] = ' ';

        out.write(header);
        out.write(data);
        // Pad data to 512-byte boundary
        int remainder = data.length % 512;
        if (remainder != 0) {
            out.write(new byte[512 - remainder]);
        }
    }

    private static void putOctal(byte[] buf, int offset, int length, long value) {
        String octal = String.format("%" + (length - 1) + "s", Long.toOctalString(value))
                .replace(' ', '0');
        byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buf, offset, Math.min(bytes.length, length - 1));
        buf[offset + length - 1] = 0; // NUL terminator
    }

    private static String getJvmVersion(long pid) {
        try {
            return JcmdExecutor.execute(pid, "VM.version").lines().findFirst().orElse("unknown").trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String getJvmArgs(long pid) {
        try {
            return JcmdExecutor.execute(pid, "VM.flags").lines().findFirst().orElse("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    private static String argusVersion() {
        String v = SnapshotCommand.class.getPackage().getImplementationVersion();
        return v != null ? v : "unknown";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static final class ManifestItem {
        final String name;
        final String source;
        final String status;
        final long bytes;
        final String error;
        final String reason;

        ManifestItem(String name, String source, String status,
                     long bytes, String error, String reason) {
            this.name = name;
            this.source = source;
            this.status = status;
            this.bytes = bytes;
            this.error = error;
            this.reason = reason;
        }
    }
}
