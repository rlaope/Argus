package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.jfr.JfrAnalyzer;
import io.argus.cli.model.JfrAnalyzeResult;
import io.argus.cli.model.JfrAnalyzeResult.AllocationSite;
import io.argus.cli.model.JfrAnalyzeResult.ContentionSite;
import io.argus.cli.model.JfrAnalyzeResult.HotMethod;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Analyzes a JFR recording file and produces a comprehensive summary.
 * Usage: argus jfranalyze <file.jfr>
 */
public final class JfrAnalyzeCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public boolean supportsTui() { return false; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public String name() {
        return "jfranalyze";
    }

    @Override public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.jfranalyze.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus jfranalyze <file.jfr>");
            return;
        }

        Path jfrFile = Path.of(args[0]);
        if (!Files.exists(jfrFile)) {
            System.err.println("File not found: " + jfrFile);
            return;
        }

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--format=json")) json = true;
        }

        JfrAnalyzeResult result;
        try {
            result = JfrAnalyzer.analyze(jfrFile);
        } catch (IOException e) {
            System.err.println("Failed to read JFR file: " + e.getMessage());
            return;
        }

        if (json) {
            printJson(result);
            return;
        }

        printRich(result, useColor, messages);
    }

    private void printRich(JfrAnalyzeResult r, boolean c, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(c, "jfranalyze", messages.get("desc.jfranalyze")));
        System.out.println(RichRenderer.boxHeader(c, messages.get("header.jfranalyze"),
                WIDTH, "file:" + Path.of(r.filePath()).getFileName()));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Overview
        section(c, "Overview");
        kv(c, "Duration", RichRenderer.formatDuration(r.durationMs()));
        kv(c, "Total Events", RichRenderer.formatNumber(r.totalEvents()));

        // GC
        if (r.gcEventCount() > 0) {
            section(c, "Garbage Collection");
            kv(c, "GC Events", String.valueOf(r.gcEventCount()));
            kv(c, "Total Pause", r.totalGcPauseMs() + " ms");
            kv(c, "Max Pause",
                    AnsiStyle.colorByThreshold(c, r.maxGcPauseMs(), 100, 500)
                            + r.maxGcPauseMs() + " ms" + AnsiStyle.style(c, AnsiStyle.RESET));
            if (r.durationMs() > 0) {
                double overhead = (double) r.totalGcPauseMs() / r.durationMs() * 100;
                kv(c, "GC Overhead",
                        AnsiStyle.colorByThreshold(c, overhead, 3, 10)
                                + String.format("%.2f%%", overhead)
                                + AnsiStyle.style(c, AnsiStyle.RESET));
            }
            if (!r.gcCauseDistribution().isEmpty()) {
                System.out.println(RichRenderer.emptyLine(WIDTH));
                System.out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + "GC Causes:"
                                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                for (var entry : r.gcCauseDistribution().entrySet()) {
                    System.out.println(RichRenderer.boxLine(
                            "    " + RichRenderer.padRight(entry.getKey(), 35)
                                    + RichRenderer.padLeft(String.valueOf(entry.getValue()), 6), WIDTH));
                }
            }
        }

        // CPU
        if (r.avgCpuLoad() > 0) {
            section(c, "CPU");
            kv(c, "Avg JVM CPU", String.format("%.1f%%", r.avgCpuLoad() * 100));
            kv(c, "Max JVM CPU",
                    AnsiStyle.colorByThreshold(c, r.maxCpuLoad() * 100, 50, 80)
                            + String.format("%.1f%%", r.maxCpuLoad() * 100)
                            + AnsiStyle.style(c, AnsiStyle.RESET));
            kv(c, "Avg System CPU", String.format("%.1f%%", r.avgSystemCpuLoad() * 100));
        }

        // Hot Methods
        if (!r.hotMethods().isEmpty()) {
            section(c, "Hot Methods (CPU Samples)");
            String hdr = AnsiStyle.style(c, AnsiStyle.BOLD)
                    + RichRenderer.padLeft("Samples", 10) + RichRenderer.padLeft("%", 8) + "  Method"
                    + AnsiStyle.style(c, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine("  " + hdr, WIDTH));
            for (HotMethod m : r.hotMethods()) {
                String line = RichRenderer.padLeft(String.valueOf(m.sampleCount()), 10)
                        + RichRenderer.padLeft(String.format("%.1f%%", m.percentage()), 8)
                        + "  " + AnsiStyle.style(c, AnsiStyle.CYAN)
                        + RichRenderer.truncate(m.method(), WIDTH - 28)
                        + AnsiStyle.style(c, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine("  " + line, WIDTH));
            }
        }

        // Allocations
        if (!r.topAllocations().isEmpty()) {
            section(c, "Top Allocating Classes");
            String hdr = AnsiStyle.style(c, AnsiStyle.BOLD)
                    + RichRenderer.padLeft("Count", 10) + RichRenderer.padLeft("Bytes", 14) + "  Class"
                    + AnsiStyle.style(c, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine("  " + hdr, WIDTH));
            for (AllocationSite a : r.topAllocations()) {
                String line = RichRenderer.padLeft(RichRenderer.formatNumber(a.count()), 10)
                        + RichRenderer.padLeft(RichRenderer.formatBytes(a.totalBytes()), 14)
                        + "  " + RichRenderer.truncate(a.className(), WIDTH - 32);
                System.out.println(RichRenderer.boxLine("  " + line, WIDTH));
            }
        }

        // Contention
        if (!r.topContention().isEmpty()) {
            section(c, "Lock Contention");
            String hdr = AnsiStyle.style(c, AnsiStyle.BOLD)
                    + RichRenderer.padLeft("Count", 10) + RichRenderer.padLeft("Total ms", 12) + "  Monitor"
                    + AnsiStyle.style(c, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine("  " + hdr, WIDTH));
            for (ContentionSite s : r.topContention()) {
                String line = RichRenderer.padLeft(String.valueOf(s.count()), 10)
                        + RichRenderer.padLeft(String.valueOf(s.totalDurationMs()), 12)
                        + "  " + AnsiStyle.style(c, AnsiStyle.YELLOW)
                        + RichRenderer.truncate(s.monitorClass(), WIDTH - 30)
                        + AnsiStyle.style(c, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine("  " + line, WIDTH));
            }
        }

        // Exceptions
        if (!r.exceptionCounts().isEmpty()) {
            section(c, "Exceptions");
            for (var entry : r.exceptionCounts().entrySet()) {
                System.out.println(RichRenderer.boxLine(
                        "    " + AnsiStyle.style(c, AnsiStyle.RED)
                                + RichRenderer.padLeft(String.valueOf(entry.getValue()), 6)
                                + AnsiStyle.style(c, AnsiStyle.RESET) + "  "
                                + RichRenderer.truncate(entry.getKey(), WIDTH - 16), WIDTH));
            }
        }

        // I/O
        if (r.fileReadCount() + r.socketReadCount() > 0) {
            section(c, "I/O Summary");
            if (r.fileReadCount() > 0)
                kv(c, "File Reads", r.fileReadCount() + " (" + RichRenderer.formatBytes(r.totalFileReadBytes()) + ")");
            if (r.fileWriteCount() > 0)
                kv(c, "File Writes", String.valueOf(r.fileWriteCount()));
            if (r.socketReadCount() > 0)
                kv(c, "Socket Reads", r.socketReadCount() + " (" + RichRenderer.formatBytes(r.totalSocketReadBytes()) + ")");
            if (r.socketWriteCount() > 0)
                kv(c, "Socket Writes", String.valueOf(r.socketWriteCount()));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c, r.totalEvents() + " events analyzed", WIDTH));
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

    private static void printJson(JfrAnalyzeResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"filePath\":\"").append(RichRenderer.escapeJson(r.filePath())).append('"');
        sb.append(",\"durationMs\":").append(r.durationMs());
        sb.append(",\"totalEvents\":").append(r.totalEvents());

        // GC
        sb.append(",\"gc\":{\"events\":").append(r.gcEventCount());
        sb.append(",\"totalPauseMs\":").append(r.totalGcPauseMs());
        sb.append(",\"maxPauseMs\":").append(r.maxGcPauseMs());
        sb.append(",\"causes\":{");
        boolean first = true;
        for (var e : r.gcCauseDistribution().entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("}}");

        // CPU
        sb.append(",\"cpu\":{\"avgLoad\":").append(r.avgCpuLoad());
        sb.append(",\"maxLoad\":").append(r.maxCpuLoad());
        sb.append(",\"avgSystemLoad\":").append(r.avgSystemCpuLoad()).append('}');

        // Hot methods
        sb.append(",\"hotMethods\":[");
        for (int i = 0; i < r.hotMethods().size(); i++) {
            HotMethod m = r.hotMethods().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"method\":\"").append(RichRenderer.escapeJson(m.method())).append('"');
            sb.append(",\"samples\":").append(m.sampleCount());
            sb.append(",\"percentage\":").append(String.format("%.2f", m.percentage())).append('}');
        }
        sb.append(']');

        // Allocations
        sb.append(",\"allocations\":[");
        for (int i = 0; i < r.topAllocations().size(); i++) {
            AllocationSite a = r.topAllocations().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"class\":\"").append(RichRenderer.escapeJson(a.className())).append('"');
            sb.append(",\"bytes\":").append(a.totalBytes());
            sb.append(",\"count\":").append(a.count()).append('}');
        }
        sb.append(']');

        // I/O
        sb.append(",\"io\":{\"fileReads\":").append(r.fileReadCount());
        sb.append(",\"fileWrites\":").append(r.fileWriteCount());
        sb.append(",\"socketReads\":").append(r.socketReadCount());
        sb.append(",\"socketWrites\":").append(r.socketWriteCount()).append('}');

        sb.append('}');
        System.out.println(sb);
    }
}
