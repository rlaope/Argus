package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.profiler.AllocationProfiler;
import io.argus.cli.profiler.AllocationProfiler.AllocatedType;
import io.argus.cli.profiler.AllocationProfiler.AllocationByClass;
import io.argus.cli.profiler.AllocationProfiler.AllocationProfile;
import io.argus.cli.profiler.AllocationProfiler.AllocationSite;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * GC-aware allocation profiling via JFR.
 *
 * <p>Starts a JFR recording on a live JVM using {@code jcmd JFR.start}, waits for the
 * configured duration, dumps and stops the recording, then parses the resulting .jfr file
 * for {@code jdk.ObjectAllocationInNewTLAB} and {@code jdk.ObjectAllocationOutsideTLAB}
 * events to identify the top allocation hotspots by stack frame.
 *
 * <p>Usage:
 * <pre>
 * argus gcprofile 12345
 * argus gcprofile 12345 --duration=30 --top=10
 * argus gcprofile 12345 --format=json
 * </pre>
 */
public final class GcProfileCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_DURATION = 30;
    private static final int DEFAULT_TOP = 10;
    private static final String JFR_RECORDING_NAME = "gcprofile-argus";

    @Override
    public String name() {
        return "gcprofile";
    }

    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gcprofile.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            printHelp(config.color());
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[0]));
            return;
        }

        int durationSec = DEFAULT_DURATION;
        int top = DEFAULT_TOP;
        boolean json = "json".equals(config.format());
        String by = "site";
        String foldPath = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--duration=")) {
                try { durationSec = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--duration") && i + 1 < args.length) {
                try { durationSec = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--top=")) {
                try { top = Integer.parseInt(arg.substring(6)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--format=json") || arg.equals("--json")) {
                json = true;
            } else if (arg.startsWith("--by=")) {
                String v = arg.substring(5).toLowerCase();
                if (v.equals("class") || v.equals("site")) by = v;
            } else if (arg.startsWith("--fold=")) {
                foldPath = arg.substring(7);
            }
        }

        boolean useColor = config.color();
        Path tmpFile = null;

        try {
            tmpFile = Files.createTempFile("argus-gcprofile-" + pid + "-", ".jfr");
            String jfrPath = tmpFile.toAbsolutePath().toString();

            // Step 1: Start JFR recording
            System.out.println("  Starting JFR recording on PID " + pid + " for " + durationSec + "s...");
            String startOut = runJcmd(pid, "JFR.start",
                    "name=" + JFR_RECORDING_NAME,
                    "duration=" + durationSec + "s",
                    "settings=profile");
            if (startOut == null) {
                System.err.println("Failed to start JFR recording. Ensure jcmd is available and the JVM is accessible.");
                return;
            }
            if (startOut.contains("Could not") || startOut.contains("error") || startOut.contains("Error")) {
                System.err.println("JFR.start failed: " + startOut.trim());
                return;
            }

            // Step 2: Wait for recording duration
            System.out.println("  Recording... (wait " + durationSec + "s)");
            try {
                Thread.sleep((long) durationSec * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for JFR recording.");
                return;
            }

            // Step 3: Dump the recording
            String dumpOut = runJcmd(pid, "JFR.dump",
                    "name=" + JFR_RECORDING_NAME,
                    "filename=" + jfrPath);
            if (dumpOut == null || dumpOut.contains("Could not") || dumpOut.contains("error")) {
                System.err.println("JFR.dump failed: " + (dumpOut != null ? dumpOut.trim() : "no output"));
                return;
            }

            // Step 4: Stop the recording
            runJcmd(pid, "JFR.stop", "name=" + JFR_RECORDING_NAME);

            // Step 5: Parse and display
            if (!Files.exists(tmpFile) || Files.size(tmpFile) == 0) {
                System.err.println("JFR file is empty or was not created. The JVM may not support JFR recording.");
                return;
            }

            // Optional: write folded stacks for flamegraph.pl consumption.
            if (foldPath != null) {
                Map<String, Long> folded = AllocationProfiler.analyzeFoldedStacks(tmpFile);
                writeFolded(Path.of(foldPath), folded);
                System.out.println("  Folded stacks written: " + foldPath
                        + " (" + folded.size() + " stacks)");
                System.out.println("  Render with: flamegraph.pl --title=\"alloc\" --colors=mem "
                        + foldPath + " > alloc.svg");
            }

            if ("class".equals(by)) {
                AllocationByClass byClass = AllocationProfiler.analyzeByClass(tmpFile);
                if (json) {
                    printJsonByClass(byClass, pid, durationSec, top);
                } else {
                    printRichByClass(byClass, pid, durationSec, top, useColor);
                }
            } else {
                AllocationProfile profile = AllocationProfiler.analyze(tmpFile);
                if (json) {
                    printJson(profile, pid, durationSec, top);
                } else {
                    printRich(profile, pid, durationSec, top, useColor);
                }
            }

        } catch (IOException e) {
            System.err.println("Failed to profile PID " + pid + ": " + e.getMessage());
            if (Boolean.getBoolean("argus.debug") || System.getenv("ARGUS_DEBUG") != null) {
                e.printStackTrace();
            }
        } finally {
            if (tmpFile != null) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // jcmd execution
    // -------------------------------------------------------------------------

    /**
     * Runs {@code jcmd <pid> <command> [args...]} and returns stdout, or null on failure.
     */
    private static String runJcmd(long pid, String command, String... extraArgs) {
        try {
            String[] cmd = new String[2 + extraArgs.length];
            cmd[0] = "jcmd";
            cmd[1] = String.valueOf(pid);
            cmd[2] = command;
            // pack extra args - jcmd expects them as a single space-joined argument for JFR commands
            // actually jcmd takes them space-separated on the command line
            String[] fullCmd = new String[2 + 1 + extraArgs.length];
            fullCmd[0] = "jcmd";
            fullCmd[1] = String.valueOf(pid);
            fullCmd[2] = command;
            System.arraycopy(extraArgs, 0, fullCmd, 3, extraArgs.length);

            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private static void printRich(AllocationProfile profile, long pid, int durationSec,
                                  int top, boolean useColor) {
        System.out.println();
        System.out.println(RichRenderer.boxHeader(useColor, "GC Allocation Profile", WIDTH,
                "pid:" + pid, durationSec + "s"));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Summary
        double totalMb = profile.totalBytes() / (1024.0 * 1024.0);
        double rateMbPerSec = profile.durationSec() > 0
                ? totalMb / profile.durationSec() : 0;
        System.out.println(RichRenderer.boxLine(
                "  Total Allocated: " + formatBytes(profile.totalBytes())
                + " in " + durationSec + "s"
                + " (" + formatBytesPerSec(rateMbPerSec) + ")", WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  Allocations: " + formatWithCommas(profile.totalAllocations()), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));

        if (profile.sites().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine("  No allocation events found in recording.", WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
            return;
        }

        // Column header
        String colHeader = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + "  " + RichRenderer.padRight("#", 4)
                + RichRenderer.padRight("Alloc Rate", 12)
                + RichRenderer.padRight("Total", 10)
                + "Stack"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(colHeader, WIDTH));

        List<AllocationSite> sites = profile.sites();
        int limit = Math.min(top, sites.size());
        for (int i = 0; i < limit; i++) {
            AllocationSite site = sites.get(i);
            String rate = formatBytesPerSec(site.bytesPerSec() / (1024.0 * 1024.0));
            String total = formatBytes(site.totalBytes());
            String stack = site.className() + "." + site.methodName()
                    + (site.lineNumber() > 0 ? ":" + site.lineNumber() : "");
            // Truncate stack to fit the remaining width
            int stackWidth = WIDTH - 4 - 4 - 12 - 10 - 2;
            String line = "  " + RichRenderer.padRight(String.valueOf(i + 1), 4)
                    + RichRenderer.padRight(rate, 12)
                    + RichRenderer.padRight(total, 10)
                    + RichRenderer.truncate(stack, Math.max(10, stackWidth));
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        // Insight line for the top allocator
        if (!sites.isEmpty() && profile.totalBytes() > 0) {
            AllocationSite top1 = sites.getFirst();
            double pct = (double) top1.totalBytes() / profile.totalBytes() * 100;
            if (pct > 10) {
                System.out.println(RichRenderer.emptyLine(WIDTH));
                String shortName = shortMethodName(top1.className(), top1.methodName());
                System.out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(useColor, AnsiStyle.CYAN)
                        + "\u2192 " + shortName + " allocates "
                        + String.format("%.0f%%", pct) + " of total"
                        + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
                if (pct > 30) {
                    System.out.println(RichRenderer.boxLine(
                            "    Consider object pooling or reducing intermediate objects", WIDTH));
                }
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor,
                profile.sites().size() + " allocation sites", WIDTH));
        System.out.println();
    }

    private static void printHelp(boolean useColor) {
        System.out.print(RichRenderer.brandedHeader(useColor, "gcprofile",
                "GC-aware allocation profiling via JFR"));
        System.out.println(RichRenderer.boxHeader(useColor, "Usage", WIDTH));
        System.out.println(RichRenderer.boxLine("argus gcprofile <pid> [options]", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Options:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --duration=N", 36)
                + "Recording duration in seconds (default: 30)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --top=N", 36)
                + "Show top N rows (default: 10)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --by=site|class", 36)
                + "Aggregate by stack frame (default) or allocated class", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --fold=FILE", 36)
                + "Write folded stacks for flamegraph.pl consumption", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --format=json", 36)
                + "Output as JSON", WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(AllocationProfile profile, long pid, int durationSec, int top) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"pid\":").append(pid);
        sb.append(",\"durationSec\":").append(durationSec);
        sb.append(",\"totalBytes\":").append(profile.totalBytes());
        sb.append(",\"totalAllocations\":").append(profile.totalAllocations());
        sb.append(",\"sites\":[");
        List<AllocationSite> sites = profile.sites();
        int limit = Math.min(top, sites.size());
        for (int i = 0; i < limit; i++) {
            AllocationSite s = sites.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"className\":\"").append(RichRenderer.escapeJson(s.className())).append('"');
            sb.append(",\"methodName\":\"").append(RichRenderer.escapeJson(s.methodName())).append('"');
            sb.append(",\"lineNumber\":").append(s.lineNumber());
            sb.append(",\"totalBytes\":").append(s.totalBytes());
            sb.append(",\"allocationCount\":").append(s.allocationCount());
            sb.append(",\"bytesPerSec\":").append(String.format("%.2f", s.bytesPerSec()));
            sb.append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    // -------------------------------------------------------------------------
    // Formatting utilities
    // -------------------------------------------------------------------------

    private static String formatBytes(long bytes) {
        return RichRenderer.formatBytes(bytes);
    }

    private static String formatBytesPerSec(double mbPerSec) {
        if (mbPerSec < 1.0) return String.format("%.0f KB/s", mbPerSec * 1024);
        if (mbPerSec < 1024.0) return String.format("%.0f MB/s", mbPerSec);
        return String.format("%.1f GB/s", mbPerSec / 1024.0);
    }

    private static String formatWithCommas(long n) {
        String s = String.valueOf(n);
        if (s.length() <= 3) return s;
        StringBuilder sb = new StringBuilder();
        int rem = s.length() % 3;
        if (rem > 0) sb.append(s, 0, rem);
        for (int i = rem; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s, i, i + 3);
        }
        return sb.toString();
    }

    private static String shortMethodName(String className, String methodName) {
        // Return "SimpleClass.method"
        int dot = className.lastIndexOf('.');
        String simple = dot >= 0 ? className.substring(dot + 1) : className;
        return simple + "." + methodName;
    }

    // -------------------------------------------------------------------------
    // --by=class rendering
    // -------------------------------------------------------------------------

    private static void printRichByClass(AllocationByClass byClass, long pid, int durationSec,
                                         int top, boolean useColor) {
        System.out.println();
        System.out.println(RichRenderer.boxHeader(useColor, "GC Allocation Profile (by class)",
                WIDTH, "pid:" + pid, durationSec + "s"));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        double totalMb = byClass.totalBytes() / (1024.0 * 1024.0);
        double mbPerSec = byClass.durationSec() > 0 ? totalMb / byClass.durationSec() : 0;
        System.out.println(RichRenderer.boxLine(
                "  Total Allocated: " + formatBytes(byClass.totalBytes())
                        + " in " + durationSec + "s"
                        + " (" + formatBytesPerSec(mbPerSec) + ")", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));

        if (byClass.sites().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine("  No allocation events found in recording.", WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
            return;
        }

        String colHeader = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + "  " + RichRenderer.padRight("#", 4)
                + RichRenderer.padRight("Total", 12)
                + RichRenderer.padRight("% ", 8)
                + RichRenderer.padRight("Count", 10)
                + "Class"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(colHeader, WIDTH));

        int limit = Math.min(top, byClass.sites().size());
        for (int i = 0; i < limit; i++) {
            AllocatedType t = byClass.sites().get(i);
            double pct = byClass.totalBytes() > 0
                    ? (t.totalBytes() * 100.0 / byClass.totalBytes()) : 0;
            int classWidth = WIDTH - 4 - 4 - 12 - 8 - 10 - 2;
            String line = "  " + RichRenderer.padRight(String.valueOf(i + 1), 4)
                    + RichRenderer.padRight(formatBytes(t.totalBytes()), 12)
                    + RichRenderer.padRight(String.format("%.1f%%", pct), 8)
                    + RichRenderer.padRight(formatWithCommas(t.allocationCount()), 10)
                    + RichRenderer.truncate(t.className(), Math.max(10, classWidth));
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor,
                byClass.sites().size() + " unique classes", WIDTH));
        System.out.println();
    }

    private static void printJsonByClass(AllocationByClass byClass, long pid, int durationSec, int top) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"pid\":").append(pid);
        sb.append(",\"durationSec\":").append(durationSec);
        sb.append(",\"by\":\"class\"");
        sb.append(",\"totalBytes\":").append(byClass.totalBytes());
        sb.append(",\"sites\":[");
        int limit = Math.min(top, byClass.sites().size());
        for (int i = 0; i < limit; i++) {
            AllocatedType t = byClass.sites().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"className\":\"").append(RichRenderer.escapeJson(t.className())).append('"');
            sb.append(",\"totalBytes\":").append(t.totalBytes());
            sb.append(",\"allocationCount\":").append(t.allocationCount());
            sb.append(",\"bytesPerSec\":").append(String.format("%.2f", t.bytesPerSec()));
            sb.append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    // -------------------------------------------------------------------------
    // Folded stacks writer (flamegraph.pl input)
    // -------------------------------------------------------------------------

    private static void writeFolded(Path out, Map<String, Long> folded) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : folded.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        Files.writeString(out, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
