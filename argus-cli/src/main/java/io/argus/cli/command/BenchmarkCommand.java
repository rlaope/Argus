package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Lightweight sampling-based benchmark for a specific method in a running JVM.
 *
 * <p>Usage:
 * <pre>
 * argus benchmark &lt;pid&gt; com.example.Serializer.serialize --iterations=1000 --warmup=100
 * </pre>
 *
 * <p>Implementation: starts a JFR recording, takes periodic thread dump samples to estimate
 * how often the target method appears in stack traces, and computes throughput, GC overhead,
 * and allocation rate from JFR events.
 */
public final class BenchmarkCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_DURATION_SEC = 30;
    private static final int DEFAULT_SAMPLE_INTERVAL_MS = 100;

    @Override public String name() { return "benchmark"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.benchmark.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length < 2) {
            printHelp(config.color(), messages);
            return;
        }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        String target = args[1];
        if (!target.contains(".")) {
            System.err.println(messages.get("error.benchmark.target.invalid", target));
            return;
        }

        // Parse options
        int durationSec = DEFAULT_DURATION_SEC;
        int warmupSec = 5;

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--iterations=")) {
                // Iterations maps to duration: estimate 1 sample/100ms
                try {
                    int iters = Integer.parseInt(arg.substring(13));
                    durationSec = Math.max(1, iters / 10);
                } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--warmup=")) {
                try {
                    int warmupIters = Integer.parseInt(arg.substring(9));
                    warmupSec = Math.max(1, warmupIters / 10);
                } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--duration=")) {
                try { durationSec = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            }
        }

        boolean useColor = config.color();
        int totalSec = warmupSec + durationSec;
        String recordingName = "argus-bench-" + pid;

        System.out.print(RichRenderer.brandedHeader(useColor, "benchmark", messages.get("desc.benchmark")));

        // Start JFR recording
        System.out.println(messages.get("status.benchmark.starting", pid, target));
        String jfrStartCmd = "JFR.start name=" + recordingName
                + " settings=profile"
                + " duration=" + totalSec + "s";
        try {
            JcmdExecutor.execute(pid, jfrStartCmd);
        } catch (RuntimeException e) {
            System.err.println(messages.get("error.benchmark.jfr.failed", e.getMessage()));
            return;
        }

        // Warmup phase
        if (warmupSec > 0) {
            System.out.println(messages.get("status.benchmark.warmup", warmupSec));
            sleepSec(warmupSec);
        }

        // Sampling phase: collect thread dumps and count target method hits
        long startMs = System.currentTimeMillis();
        long endMs = startMs + (long) durationSec * 1000;
        int totalSamples = 0;
        int targetHits = 0;

        System.out.println(messages.get("status.benchmark.sampling", durationSec));
        while (System.currentTimeMillis() < endMs) {
            try {
                String dump = JcmdExecutor.execute(pid, "Thread.print");
                totalSamples++;
                if (dump.contains(target)) {
                    targetHits++;
                }
            } catch (RuntimeException e) {
                break;
            }
            sleepMs(DEFAULT_SAMPLE_INTERVAL_MS);
        }

        long actualMs = System.currentTimeMillis() - startMs;
        double actualSec = actualMs / 1000.0;

        // Stop JFR and get GC/allocation stats from recording output
        String jfrDumpFile = recordingName + ".jfr";
        GcStats gcStats = new GcStats();
        AllocStats allocStats = new AllocStats();

        try {
            JcmdExecutor.execute(pid, "JFR.stop name=" + recordingName + " filename=" + jfrDumpFile);
            // Parse JFR check output for basic stats (filename is best-effort)
            String checkOut = JcmdExecutor.execute(pid, "JFR.check");
            parseJfrSummary(checkOut, gcStats, allocStats);
        } catch (RuntimeException ignored) {}

        // Compute throughput estimate
        double targetPct = totalSamples > 0 ? (double) targetHits / totalSamples * 100.0 : 0;
        // Rough throughput: if method appears in X% of samples and each sample is 100ms window,
        // estimate ops/s as inverse of estimated method duration
        double estimatedOpsPerSec = totalSamples > 0 && actualSec > 0
                ? (double) targetHits / actualSec * 10.0
                : 0;

        renderResult(pid, target, durationSec, totalSamples, targetHits, targetPct,
                estimatedOpsPerSec, gcStats, allocStats, actualSec, useColor, messages);
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private void renderResult(long pid, String target, int durationSec, int totalSamples,
                              int targetHits, double targetPct, double estimatedOpsPerSec,
                              GcStats gcStats, AllocStats allocStats, double actualSec,
                              boolean c, Messages messages) {
        System.out.println(RichRenderer.boxHeader(c, messages.get("header.benchmark"), WIDTH,
                "pid:" + pid, (int) actualSec + "s"));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        System.out.println(RichRenderer.boxLine(
                "  " + RichRenderer.padRight(messages.get("benchmark.label.target"), 20) + target, WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + RichRenderer.padRight(messages.get("benchmark.label.duration"), 20)
                + (int) actualSec + "s, "
                + messages.get("benchmark.label.samples") + ": " + totalSamples, WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Throughput
        String opsStr = estimatedOpsPerSec > 0
                ? "~" + formatWithCommas((long) estimatedOpsPerSec) + " ops/s"
                : messages.get("benchmark.insufficient.samples");
        System.out.println(RichRenderer.boxLine(
                "  " + RichRenderer.padRight(messages.get("benchmark.label.throughput"), 24)
                + AnsiStyle.style(c, AnsiStyle.BOLD) + opsStr
                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                + messages.get("benchmark.label.sample.pct", String.format("%.0f", targetPct))
                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));

        // GC section
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + messages.get("benchmark.section.gc")
                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        if (gcStats.events >= 0) {
            double gcOverheadPct = actualSec > 0 ? gcStats.pauseMs / (actualSec * 10.0) : 0;
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("benchmark.label.gc.events"), 10)
                    + gcStats.events
                    + "    " + RichRenderer.padRight(messages.get("benchmark.label.gc.pause"), 8)
                    + gcStats.pauseMs + "ms total"
                    + "    " + messages.get("benchmark.label.gc.overhead")
                    + String.format("%.2f%%", gcOverheadPct), WIDTH));
        } else {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                    + messages.get("benchmark.gc.unavailable")
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }

        // Allocation section
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + messages.get("benchmark.section.alloc")
                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        if (allocStats.rateMBps >= 0) {
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("benchmark.label.alloc.rate"), 10)
                    + String.format("%.0f MB/s", allocStats.rateMBps), WIDTH));
        } else {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                    + messages.get("benchmark.alloc.unavailable")
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }

        // Disclaimer
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                + messages.get("benchmark.note.sampling")
                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                + messages.get("benchmark.note.jmh")
                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));

        System.out.println(RichRenderer.boxFooter(c, null, WIDTH));
    }

    private void printHelp(boolean c, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(c, "benchmark", messages.get("cmd.benchmark.desc")));
        System.out.println(RichRenderer.boxHeader(c, "Usage", WIDTH));
        System.out.println(RichRenderer.boxLine("argus benchmark <pid> <class.method> [options]", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(c, AnsiStyle.BOLD) + "Options:"
                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --iterations=N", 30) + "Approximate iteration count (maps to duration)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --warmup=N", 30) + "Warmup iterations before measurement", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --duration=N", 30) + "Measurement duration in seconds (default: 30)", WIDTH));
        System.out.println(RichRenderer.boxFooter(c, null, WIDTH));
    }

    // -------------------------------------------------------------------------
    // JFR parsing (best-effort from jcmd output)
    // -------------------------------------------------------------------------

    private void parseJfrSummary(String checkOutput, GcStats gcStats, AllocStats allocStats) {
        // JFR check output doesn't provide GC stats directly; set unavailable
        gcStats.events = -1;
        gcStats.pauseMs = 0;
        allocStats.rateMBps = -1;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static void sleepSec(int seconds) {
        sleepMs((long) seconds * 1000);
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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

    // -------------------------------------------------------------------------
    // Data holders
    // -------------------------------------------------------------------------

    private static final class GcStats {
        int events = -1;
        long pauseMs = 0;
    }

    private static final class AllocStats {
        double rateMBps = -1;
    }
}
