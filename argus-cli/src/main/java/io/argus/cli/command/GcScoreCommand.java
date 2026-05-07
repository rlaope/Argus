package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.gclog.GcEvent;
import io.argus.cli.gclog.GcLogAnalysis;
import io.argus.cli.gclog.GcLogAnalyzer;
import io.argus.cli.gclog.GcLogParser;
import io.argus.cli.gcscore.AxisScore;
import io.argus.cli.gcscore.GcScoreCalculator;
import io.argus.cli.gcscore.GcScoreResult;
import io.argus.cli.gcwhy.GcWhyJfrCollector;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Produces a one-page GC Health Score Card from a GC log file or a live JVM PID.
 *
 * <p>File form: {@code argus gcscore <gc-log-file> [--format=json]}
 * <p>Live form:  {@code argus gcscore <pid> [--duration=30] [--format=json]}
 *
 * <p>The first argument is treated as a PID when it consists entirely of digits
 * (no file-separator characters). Otherwise it is treated as a GC log file path.
 */
public final class GcScoreCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_DURATION_SEC = 30;
    private static final String JFR_RECORDING_NAME = "gcscore-argus";

    @Override public String name() { return "gcscore"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public CommandMode mode() { return CommandMode.READ; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gcscore.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus gcscore <gc-log-file|pid> [--duration=30] [--format=json]");
            return;
        }

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        int durationSec = DEFAULT_DURATION_SEC;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--duration=")) {
                try { durationSec = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            }
        }

        String firstArg = args[0];
        if (isPid(firstArg)) {
            long pid = Long.parseLong(firstArg);
            executeLive(pid, durationSec, json, useColor, messages);
        } else {
            executeFile(firstArg, json, useColor, messages);
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

    private void executeFile(String filePath, boolean json, boolean useColor, Messages messages) {
        Path logFile = Path.of(filePath);
        if (!Files.exists(logFile)) {
            System.err.println("gcscore expects a live PID or a path to a GC log file. Got: " + filePath);
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

        scoreAndRender(events, json, useColor, messages, logFile.getFileName().toString());
    }

    // ── Live JFR form ────────────────────────────────────────────────────────

    private void executeLive(long pid, int durationSec, boolean json, boolean useColor, Messages messages) {
        Path tmpFile = null;
        try {
            tmpFile = Files.createTempFile("argus-gcscore-" + pid + "-", ".jfr");
            String jfrPath = tmpFile.toAbsolutePath().toString();

            System.out.println("  " + messages.get("gcscore.live.capturing",
                    String.valueOf(pid), String.valueOf(durationSec)));

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

            try {
                Thread.sleep((long) durationSec * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for JFR recording.");
                return;
            }

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

            List<GcEvent> events;
            try {
                events = GcWhyJfrCollector.collect(tmpFile);
            } catch (IOException e) {
                System.err.println("Failed to parse JFR recording: " + e.getMessage());
                return;
            }

            if (events.isEmpty()) {
                System.err.println(messages.get("gcscore.no.events", String.valueOf(durationSec)));
                return;
            }

            scoreAndRender(events, json, useColor, messages, "pid:" + pid);

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

    // ── Shared scoring + render path ─────────────────────────────────────────

    private static void scoreAndRender(List<GcEvent> events, boolean json, boolean useColor,
                                       Messages messages, String sourceLabel) {
        GcLogAnalysis analysis = GcLogAnalyzer.analyze(events);
        GcScoreResult result = GcScoreCalculator.compute(analysis);

        if (json) {
            printJson(result);
            return;
        }
        printCard(result, useColor, messages, sourceLabel);
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private static void printCard(GcScoreResult r, boolean useColor, Messages messages, String sourceLabel) {
        System.out.print(RichRenderer.brandedHeader(useColor, "gcscore", messages.get("desc.gcscore")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gcscore"),
                WIDTH, "source:" + sourceLabel));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String gradeColor = gradeColor(useColor, r.grade());

        String overallLine = "  " + bold + "Overall: " + reset
                + gradeColor + r.grade() + reset + "  "
                + "(" + r.summary() + ")  "
                + AnsiStyle.style(useColor, AnsiStyle.DIM) + "[" + r.overall() + "/100]" + reset;
        System.out.println(RichRenderer.boxLine(overallLine, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        for (AxisScore ax : r.axes()) {
            System.out.println(RichRenderer.boxLine(renderAxis(ax, useColor), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine("  " + bold + "Hints:" + reset, WIDTH));
        for (String hint : r.hints()) {
            System.out.println(RichRenderer.boxLine("   • " + hint, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static String renderAxis(AxisScore ax, boolean useColor) {
        String mark;
        String color;
        switch (ax.verdict()) {
            case PASS -> { mark = "\u2713 Pass "; color = AnsiStyle.GREEN; }
            case WARN -> { mark = "\u26A0 Warn "; color = AnsiStyle.YELLOW; }
            case FAIL -> { mark = "\u2717 Fail "; color = AnsiStyle.RED; }
            default   -> { mark = "\u2013 N/A  "; color = AnsiStyle.DIM;    }
        }
        String coloredMark = AnsiStyle.style(useColor, color) + mark + AnsiStyle.style(useColor, AnsiStyle.RESET);
        String valueStr = ax.available() ? formatValue(ax) : "-";
        String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        return String.format("  %-20s %s %10s   %s(target %s)%s",
                ax.name(), coloredMark, valueStr, dim, ax.target(), reset);
    }

    private static String formatValue(AxisScore ax) {
        return switch (ax.name()) {
            case "Pause p99", "Pause tail (max)" -> String.format("%.0f %s", ax.value(), ax.unit());
            case "Throughput", "Promotion ratio" -> String.format("%.1f%s", ax.value(), ax.unit());
            case "Allocation rate" -> String.format("%.0f %s", ax.value(), ax.unit());
            case "Full GC frequency" -> String.format("%.1f %s", ax.value(), ax.unit());
            default -> String.format("%.2f %s", ax.value(), ax.unit());
        };
    }

    private static String gradeColor(boolean useColor, String grade) {
        String code = switch (grade) {
            case "A" -> AnsiStyle.GREEN;
            case "B" -> AnsiStyle.CYAN;
            case "C" -> AnsiStyle.YELLOW;
            case "D" -> AnsiStyle.YELLOW;
            default  -> AnsiStyle.RED;
        };
        return AnsiStyle.style(useColor, AnsiStyle.BOLD) + AnsiStyle.style(useColor, code);
    }

    private static void printJson(GcScoreResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"overall\":").append(r.overall())
                .append(",\"grade\":\"").append(r.grade()).append('"')
                .append(",\"summary\":\"").append(RichRenderer.escapeJson(r.summary())).append('"')
                .append(",\"axes\":[");
        for (int i = 0; i < r.axes().size(); i++) {
            AxisScore ax = r.axes().get(i);
            if (i > 0) sb.append(',');
            sb.append('{')
                    .append("\"name\":\"").append(RichRenderer.escapeJson(ax.name())).append('"')
                    .append(",\"available\":").append(ax.available())
                    .append(",\"value\":").append(ax.value())
                    .append(",\"unit\":\"").append(ax.unit()).append('"')
                    .append(",\"target\":\"").append(RichRenderer.escapeJson(ax.target())).append('"')
                    .append(",\"score\":").append(ax.score())
                    .append(",\"verdict\":\"").append(ax.verdict().name()).append('"')
                    .append('}');
        }
        sb.append("],\"hints\":[");
        for (int i = 0; i < r.hints().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(r.hints().get(i))).append('"');
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
