package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.export.HtmlExporter;
import io.argus.cli.gclog.*;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Analyzes GC log files and produces comprehensive analysis with
 * pause distribution, cause breakdown, and tuning recommendations.
 * Free alternative to GCEasy.io.
 *
 * Usage: argus gclog /var/log/gc.log
 */
public final class GcLogCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name() { return "gclog"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gclog.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus gclog <gc-log-file>");
            return;
        }

        Path logFile = Path.of(args[0]);
        if (!Files.exists(logFile)) {
            System.err.println("File not found: " + logFile);
            return;
        }

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        boolean flagsOnly = false;
        String exportHtml = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--format=json")) json = true;
            if (args[i].equals("--suggest-flags")) flagsOnly = true;
            if (args[i].startsWith("--export=")) exportHtml = args[i].substring(9);
        }

        List<GcEvent> events;
        try {
            events = GcLogParser.parse(logFile);
        } catch (IOException e) {
            System.err.println("Failed to read GC log: " + e.getMessage());
            return;
        }

        if (events.isEmpty()) {
            System.err.println("No GC events found in " + logFile);
            return;
        }

        GcLogAnalysis analysis = GcLogAnalyzer.analyze(events);

        if (flagsOnly) {
            for (var rec : analysis.recommendations()) {
                if (!rec.flag().isEmpty()) System.out.println(rec.flag());
            }
            return;
        }

        if (json) {
            printJson(analysis, logFile);
            return;
        }

        if (exportHtml != null) {
            PrintStream original = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));
            printRich(analysis, logFile, true);
            System.setOut(original);
            String html = HtmlExporter.toHtml(capture.toString(), "Argus GC Log Analysis — " + logFile.getFileName());
            try {
                Path outPath = Path.of(exportHtml.equals("html") ? "argus-gclog.html" : exportHtml);
                Files.writeString(outPath, html);
                System.out.println("Exported to: " + outPath.toAbsolutePath());
            } catch (Exception e) {
                System.err.println("Export failed: " + e.getMessage());
            }
            return;
        }

        printRich(analysis, logFile, useColor);
    }

    private void printRich(GcLogAnalysis a, Path file, boolean c) {
        System.out.print(RichRenderer.brandedHeader(c, "gclog",
                "GC log analysis with tuning recommendations"));
        System.out.println(RichRenderer.boxHeader(c, "GC Log Analysis", WIDTH,
                "file:" + file.getFileName()));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Summary
        section(c, "Summary");
        kv(c, "Duration", String.format("%.0f seconds", a.durationSec()));
        kv(c, "Total Events", String.valueOf(a.totalEvents()));
        kv(c, "Pause Events", String.valueOf(a.pauseEvents()));
        if (a.fullGcEvents() > 0) {
            kv(c, "Full GC", AnsiStyle.style(c, AnsiStyle.RED) + a.fullGcEvents()
                    + AnsiStyle.style(c, AnsiStyle.RESET));
        }
        kv(c, "Concurrent", String.valueOf(a.concurrentEvents()));

        String tColor = a.throughputPercent() >= 95 ? AnsiStyle.style(c, AnsiStyle.GREEN)
                : a.throughputPercent() >= 90 ? AnsiStyle.style(c, AnsiStyle.YELLOW)
                : AnsiStyle.style(c, AnsiStyle.RED);
        kv(c, "Throughput", tColor + String.format("%.1f%%", a.throughputPercent())
                + AnsiStyle.style(c, AnsiStyle.RESET));

        // Pause Distribution
        section(c, "Pause Distribution");
        System.out.println(RichRenderer.boxLine(String.format(
                "  p50: %dms   p95: %dms   p99: %dms   max: %dms   avg: %dms",
                a.p50PauseMs(), a.p95PauseMs(), a.p99PauseMs(), a.maxPauseMs(), a.avgPauseMs()), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // ASCII histogram bar
        String bar = "  " + AnsiStyle.style(c, AnsiStyle.GREEN)
                + "\u2588".repeat(Math.min((int)(a.p50PauseMs() / 10), 20))
                + AnsiStyle.style(c, AnsiStyle.YELLOW)
                + "\u2588".repeat(Math.min((int)((a.p95PauseMs() - a.p50PauseMs()) / 10), 15))
                + AnsiStyle.style(c, AnsiStyle.RED)
                + "\u2588".repeat(Math.min((int)((a.maxPauseMs() - a.p95PauseMs()) / 10), 10))
                + AnsiStyle.style(c, AnsiStyle.RESET)
                + "  " + a.p50PauseMs() + "ms ─── " + a.maxPauseMs() + "ms";
        System.out.println(RichRenderer.boxLine(bar, WIDTH));

        // Heap
        section(c, "Heap");
        kv(c, "Peak Heap", formatKB(a.peakHeapKB()));
        kv(c, "Avg After GC", formatKB(a.avgHeapAfterKB()));

        // Cause Breakdown
        if (!a.causeBreakdown().isEmpty()) {
            section(c, "GC Causes");
            String hdr = AnsiStyle.style(c, AnsiStyle.BOLD)
                    + RichRenderer.padRight("Cause", 35)
                    + RichRenderer.padLeft("Count", 8)
                    + RichRenderer.padLeft("Avg ms", 10)
                    + RichRenderer.padLeft("Max ms", 10)
                    + AnsiStyle.style(c, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine("  " + hdr, WIDTH));
            System.out.println(RichRenderer.boxSeparator(WIDTH));

            for (var entry : a.causeBreakdown().values()) {
                boolean warn = entry.cause().toLowerCase().contains("humongous")
                        || entry.cause().toLowerCase().contains("full")
                        || entry.cause().toLowerCase().contains("metadata");
                String causeColor = warn ? AnsiStyle.style(c, AnsiStyle.YELLOW) : "";
                String line = causeColor
                        + RichRenderer.padRight(RichRenderer.truncate(entry.cause(), 33), 35)
                        + (warn ? AnsiStyle.style(c, AnsiStyle.RESET) : "")
                        + RichRenderer.padLeft(String.valueOf(entry.count()), 8)
                        + RichRenderer.padLeft(String.valueOf(entry.avgMs()), 10)
                        + RichRenderer.padLeft(String.valueOf(entry.maxMs()), 10);
                if (warn) line += " \u26a0";
                System.out.println(RichRenderer.boxLine("  " + line, WIDTH));
            }
        }

        // Tuning Recommendations
        if (!a.recommendations().isEmpty()) {
            section(c, "Tuning Recommendations");

            for (int i = 0; i < a.recommendations().size(); i++) {
                var rec = a.recommendations().get(i);
                String sevColor = switch (rec.severity()) {
                    case "CRITICAL" -> AnsiStyle.style(c, AnsiStyle.RED, AnsiStyle.BOLD);
                    case "WARNING" -> AnsiStyle.style(c, AnsiStyle.YELLOW, AnsiStyle.BOLD);
                    default -> AnsiStyle.style(c, AnsiStyle.CYAN);
                };

                System.out.println(RichRenderer.boxLine(
                        "  " + sevColor + (i + 1) + ". " + rec.problem()
                                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                System.out.println(RichRenderer.boxLine(
                        "     \u2192 " + rec.suggestion(), WIDTH));
                if (!rec.flag().isEmpty()) {
                    System.out.println(RichRenderer.boxLine(
                            "     " + AnsiStyle.style(c, AnsiStyle.GREEN)
                                    + rec.flag() + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                }
                System.out.println(RichRenderer.emptyLine(WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(c,
                a.pauseEvents() + " pauses, " + String.format("%.1f%%", a.throughputPercent()) + " throughput", WIDTH));
    }

    private void section(boolean c, String title) {
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD, AnsiStyle.CYAN) + title
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
    }

    private void kv(boolean c, String key, String value) {
        System.out.println(RichRenderer.boxLine(
                "  " + RichRenderer.padRight(key, 18) + "  " + value, WIDTH));
    }

    private static String formatKB(long kb) {
        if (kb < 1024) return kb + "KB";
        if (kb < 1024 * 1024) return (kb / 1024) + "MB";
        return String.format("%.1fGB", kb / (1024.0 * 1024));
    }

    private static void printJson(GcLogAnalysis a, Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"file\":\"").append(RichRenderer.escapeJson(file.toString())).append('"');
        sb.append(",\"totalEvents\":").append(a.totalEvents());
        sb.append(",\"pauseEvents\":").append(a.pauseEvents());
        sb.append(",\"fullGcEvents\":").append(a.fullGcEvents());
        sb.append(",\"durationSec\":").append(String.format("%.1f", a.durationSec()));
        sb.append(",\"throughputPercent\":").append(String.format("%.1f", a.throughputPercent()));
        sb.append(",\"pauses\":{");
        sb.append("\"totalMs\":").append(a.totalPauseMs());
        sb.append(",\"maxMs\":").append(a.maxPauseMs());
        sb.append(",\"p50Ms\":").append(a.p50PauseMs());
        sb.append(",\"p95Ms\":").append(a.p95PauseMs());
        sb.append(",\"p99Ms\":").append(a.p99PauseMs());
        sb.append(",\"avgMs\":").append(a.avgPauseMs()).append('}');
        sb.append(",\"causes\":{");
        boolean first = true;
        for (var entry : a.causeBreakdown().values()) {
            if (!first) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(entry.cause())).append("\":{");
            sb.append("\"count\":").append(entry.count());
            sb.append(",\"avgMs\":").append(entry.avgMs());
            sb.append(",\"maxMs\":").append(entry.maxMs()).append('}');
            first = false;
        }
        sb.append('}');
        sb.append(",\"recommendations\":[");
        for (int i = 0; i < a.recommendations().size(); i++) {
            var rec = a.recommendations().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"severity\":\"").append(rec.severity()).append('"');
            sb.append(",\"problem\":\"").append(RichRenderer.escapeJson(rec.problem())).append('"');
            sb.append(",\"flag\":\"").append(RichRenderer.escapeJson(rec.flag())).append("\"}");
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
