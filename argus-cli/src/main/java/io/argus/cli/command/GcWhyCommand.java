package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.gclog.GcEvent;
import io.argus.cli.gclog.GcLogParser;
import io.argus.cli.gcwhy.GcWhyAnalyzer;
import io.argus.cli.gcwhy.GcWhyJfrCollector;
import io.argus.cli.gcwhy.GcWhyResult;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Narrates why the worst GC pause in a time window happened.
 *
 * <p>File form: {@code argus gcwhy <gc-log-file> [--last=5m] [--format=json]}
 * <p>Live form:  {@code argus gcwhy <pid> [--duration=30] [--last=Ns] [--format=json]}
 *
 * <p>The first argument is treated as a PID when it consists entirely of digits
 * (no file-separator characters). Otherwise it is treated as a GC log file path.
 */
public final class GcWhyCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final double DEFAULT_WINDOW_SEC = 5 * 60.0;
    private static final int DEFAULT_DURATION_SEC = 30;
    private static final String JFR_RECORDING_NAME = "gcwhy-argus";

    @Override public String name() { return "gcwhy"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public CommandMode mode() { return CommandMode.READ; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gcwhy.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus gcwhy <gc-log-file|pid> [--duration=30] [--last=5m] [--format=json]");
            return;
        }

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        double windowSec = DEFAULT_WINDOW_SEC;
        int durationSec = DEFAULT_DURATION_SEC;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--last=")) {
                Double parsed = parseDuration(arg.substring(7));
                if (parsed != null) windowSec = parsed;
            } else if (arg.startsWith("--duration=")) {
                try { durationSec = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            }
        }

        String firstArg = args[0];
        if (isPid(firstArg)) {
            long pid = Long.parseLong(firstArg);
            executeLive(pid, durationSec, windowSec, json, useColor, messages);
        } else {
            executeFile(firstArg, windowSec, json, useColor, messages);
        }
    }

    // ── PID detection ────────────────────────────────────────────────────────

    /**
     * Returns true when the argument is a pure numeric token with no file-separator
     * characters — i.e. it looks like a PID, not a path.
     */
    private static boolean isPid(String arg) {
        if (arg == null || arg.isEmpty()) return false;
        if (arg.contains("/") || arg.contains("\\") || arg.contains(".")) return false;
        for (int i = 0; i < arg.length(); i++) {
            if (!Character.isDigit(arg.charAt(i))) return false;
        }
        return true;
    }

    // ── File form ────────────────────────────────────────────────────────────

    private void executeFile(String filePath, double windowSec, boolean json,
                             boolean useColor, Messages messages) {
        Path logFile = Path.of(filePath);
        if (!Files.exists(logFile)) {
            System.err.println("File not found: " + logFile);
            return;
        }

        List<GcEvent> events;
        try {
            events = GcLogParser.parse(logFile);
        } catch (IOException e) {
            System.err.println("Failed to read " + logFile + ": " + e.getMessage());
            return;
        }
        if (events.isEmpty()) {
            System.err.println("No GC events parsed from " + logFile);
            return;
        }

        renderResult(events, windowSec, json, useColor, messages);
    }

    // ── Live JFR form ────────────────────────────────────────────────────────

    private void executeLive(long pid, int durationSec, double windowSec,
                             boolean json, boolean useColor, Messages messages) {
        Path tmpFile = null;
        try {
            tmpFile = Files.createTempFile("argus-gcwhy-" + pid + "-", ".jfr");
            String jfrPath = tmpFile.toAbsolutePath().toString();

            System.out.println("  " + messages.get("gcwhy.live.capturing", String.valueOf(pid), String.valueOf(durationSec)));

            // Start JFR recording on the target JVM. Do NOT pass `duration=`: with that flag
            // the JVM auto-stops + finalises the recording at the deadline, racing our explicit
            // JFR.dump and yielding an empty file. We control timing by sleeping then stopping.
            String startOut = runJcmd(pid, "JFR.start",
                    "name=" + JFR_RECORDING_NAME,
                    "settings=default");
            if (startOut == null) {
                System.err.println("Failed to start JFR recording. Ensure jcmd is available and the JVM is accessible.");
                return;
            }
            if (startOut.contains("Could not") || startOut.toLowerCase().contains("error")) {
                System.err.println("JFR.start failed: " + startOut.trim());
                return;
            }

            // Wait for the recording to complete.
            try {
                Thread.sleep((long) durationSec * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for JFR recording.");
                return;
            }

            // Dump and stop.
            String dumpOut = runJcmd(pid, "JFR.dump",
                    "name=" + JFR_RECORDING_NAME,
                    "filename=" + jfrPath);
            if (dumpOut == null || dumpOut.contains("Could not") || dumpOut.toLowerCase().contains("error")) {
                System.err.println("JFR.dump failed: " + (dumpOut != null ? dumpOut.trim() : "no output"));
                return;
            }
            runJcmd(pid, "JFR.stop", "name=" + JFR_RECORDING_NAME);

            if (!Files.exists(tmpFile) || Files.size(tmpFile) == 0) {
                System.err.println("JFR file is empty or was not created. The JVM may not support JFR recording.");
                return;
            }

            // Parse JFR into GcEvent list using the same window that applies to file form.
            List<GcEvent> events;
            try {
                events = GcWhyJfrCollector.collect(tmpFile);
            } catch (IOException e) {
                System.err.println("Failed to parse JFR recording: " + e.getMessage());
                return;
            }

            if (events.isEmpty()) {
                System.err.println("No GC events captured in the " + durationSec + "s recording window.");
                return;
            }

            renderResult(events, windowSec, json, useColor, messages);

        } catch (IOException e) {
            System.err.println("Failed to capture live GC data for PID " + pid + ": " + e.getMessage());
            if (Boolean.getBoolean("argus.debug") || System.getenv("ARGUS_DEBUG") != null) {
                e.printStackTrace();
            }
        } finally {
            if (tmpFile != null) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    // ── Shared render path ───────────────────────────────────────────────────

    private static void renderResult(List<GcEvent> events, double windowSec,
                                     boolean json, boolean useColor, Messages messages) {
        GcWhyResult result = GcWhyAnalyzer.analyze(events, windowSec);
        if (result.cause().isEmpty() && result.pauseMs() == 0) {
            System.err.println("No qualifying pause event found in the last " + formatWindow(windowSec));
            return;
        }

        if (json) {
            printJson(result);
        } else {
            printReport(result, useColor, messages, windowSec);
        }
    }

    // ── jcmd execution ───────────────────────────────────────────────────────

    /** Runs {@code jcmd <pid> <command> [args...]} and returns stdout, or null on failure. */
    private static String runJcmd(long pid, String command, String... extraArgs) {
        try {
            String[] fullCmd = new String[3 + extraArgs.length];
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

    // ── Rendering ───────────────────────────────────────────────────────────

    private static void printReport(GcWhyResult r, boolean useColor, Messages messages, double windowSec) {
        System.out.print(RichRenderer.brandedHeader(useColor, "gcwhy", messages.get("desc.gcwhy")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gcwhy"),
                WIDTH, "window:" + formatWindow(windowSec)));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String red = AnsiStyle.style(useColor, AnsiStyle.RED);

        String head = "  " + bold + "Worst pause:" + reset + " "
                + red + String.format("%.0f ms", r.pauseMs()) + reset
                + " at " + dim + String.format("t=%.2fs", r.timestampSec()) + reset
                + "  (" + r.type() + " / " + r.cause() + ")";
        System.out.println(RichRenderer.boxLine(head, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        System.out.println(RichRenderer.boxLine("  " + bold + "Why this happened:" + reset, WIDTH));
        for (String b : r.bullets()) {
            System.out.println(RichRenderer.boxLine("   • " + b, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine("  " + bold + "Related counters:" + reset, WIDTH));
        for (Map.Entry<String, String> e : r.counters().entrySet()) {
            String line = String.format("   %-22s %s", e.getKey() + ":", e.getValue());
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(GcWhyResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestampSec\":").append(r.timestampSec())
                .append(",\"type\":\"").append(RichRenderer.escapeJson(r.type())).append('"')
                .append(",\"cause\":\"").append(RichRenderer.escapeJson(r.cause())).append('"')
                .append(",\"pauseMs\":").append(r.pauseMs())
                .append(",\"bullets\":[");
        for (int i = 0; i < r.bullets().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(r.bullets().get(i))).append('"');
        }
        sb.append("],\"counters\":{");
        int i = 0;
        for (Map.Entry<String, String> e : r.counters().entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(e.getKey())).append("\":\"")
                    .append(RichRenderer.escapeJson(e.getValue())).append('"');
        }
        sb.append("}}");
        System.out.println(sb);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Accepts "5m", "30s", "2h", or a bare number (treated as seconds). Returns seconds, or null. */
    static Double parseDuration(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            char last = s.charAt(s.length() - 1);
            if (Character.isDigit(last)) return Double.parseDouble(s);
            double n = Double.parseDouble(s.substring(0, s.length() - 1));
            return switch (last) {
                case 's' -> n;
                case 'm' -> n * 60;
                case 'h' -> n * 3600;
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatWindow(double sec) {
        if (sec >= 3600 && sec % 3600 == 0) return ((long) (sec / 3600)) + "h";
        if (sec >= 60 && sec % 60 == 0) return ((long) (sec / 60)) + "m";
        return ((long) sec) + "s";
    }
}
