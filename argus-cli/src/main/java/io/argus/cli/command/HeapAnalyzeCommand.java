package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.heapanalyze.HprofParser;
import io.argus.cli.heapanalyze.HprofSummary;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * CLI heap dump analysis — MAT alternative. One command, instant answers.
 *
 * <p>Parses HPROF binary format in streaming mode (handles multi-GB dumps)
 * and produces: class histogram by size, top objects, array analysis,
 * and string statistics.
 *
 * <p>Usage:
 * <pre>
 * argus heapanalyze app.hprof                    # full analysis
 * argus heapanalyze app.hprof --top 30           # top 30 classes
 * argus heapanalyze app.hprof --format=json      # JSON output for CI
 * argus heapanalyze app.hprof --sort count       # sort by instance count
 * </pre>
 *
 * <p>Exit codes: 0 = success, 2 = parse error
 */
public final class HeapAnalyzeCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_TOP = 20;

    @Override public String name() { return "heapanalyze"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.heapanalyze.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus heapanalyze <file.hprof> [--top N] [--sort size|count] [--format=json]");
            return;
        }

        String filePath = null;
        int topN = DEFAULT_TOP;
        String sortBy = "size";
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--top=")) {
                try { topN = Integer.parseInt(arg.substring(6)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--top") && i + 1 < args.length) {
                try { topN = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--sort=")) {
                sortBy = arg.substring(7);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (!arg.startsWith("--")) {
                filePath = arg;
            }
        }

        if (filePath == null) {
            System.err.println("Error: HPROF file path is required.");
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("Error: File not found: " + filePath);
            return;
        }
        if (!file.canRead()) {
            System.err.println("Error: Cannot read file: " + filePath);
            return;
        }

        // Parse
        long startTime = System.currentTimeMillis();
        HprofSummary summary;
        try {
            System.err.println("Parsing " + file.getName() + " (" + RichRenderer.formatBytes(file.length()) + ")...");
            summary = HprofParser.parse(file);
        } catch (Exception e) {
            System.err.println("Error parsing HPROF: " + e.getMessage());
            return;
        }
        long elapsed = System.currentTimeMillis() - startTime;

        if (json) {
            printJson(summary, topN, sortBy);
            return;
        }

        printRich(summary, topN, sortBy, useColor, elapsed);
    }

    private void printRich(HprofSummary s, int topN, String sortBy, boolean c, long elapsedMs) {
        System.out.print(RichRenderer.brandedHeader(c, "heapanalyze",
                "Heap dump analysis — instant answers from HPROF binary"));
        System.out.println(RichRenderer.boxHeader(c, "Heap Analysis", WIDTH,
                s.fileName(), RichRenderer.formatBytes(s.fileSize())));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Summary stats
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + "Summary"
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(String.format(
                "  Objects: %,d  |  Classes: %,d  |  Shallow size: %s",
                s.totalObjects(), s.classCount(), RichRenderer.formatBytes(s.totalShallowBytes())), WIDTH));
        System.out.println(RichRenderer.boxLine(String.format(
                "  Instances: %,d (%s)  |  Arrays: %,d (%s)",
                s.totalInstances(), RichRenderer.formatBytes(s.totalBytes()),
                s.totalArrays(), RichRenderer.formatBytes(s.totalArrayBytes())), WIDTH));
        System.out.println(RichRenderer.boxLine(String.format(
                "  Strings: %,d  |  ID size: %d bytes  |  Parsed in %,dms",
                s.stringCount(), s.idSize(), elapsedMs), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Top classes
        boolean byCount = "count".equalsIgnoreCase(sortBy);
        List<Map.Entry<String, long[]>> top = byCount ? s.topByCount(topN) : s.topBySize(topN);
        String sortLabel = byCount ? "by instance count" : "by shallow size";

        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + "Top " + topN + " classes " + sortLabel
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Header
        String hdr = AnsiStyle.style(c, AnsiStyle.BOLD)
                + RichRenderer.padRight("#", 4)
                + RichRenderer.padRight("Instances", 14)
                + RichRenderer.padRight("Shallow Size", 16)
                + RichRenderer.padRight("%", 7)
                + "Class"
                + AnsiStyle.style(c, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine("  " + hdr, WIDTH));
        System.out.println(RichRenderer.boxLine("  " + "\u2500".repeat(Math.min(WIDTH - 6, 75)), WIDTH));

        long totalShallow = s.totalShallowBytes() > 0 ? s.totalShallowBytes() : 1;
        for (int i = 0; i < top.size(); i++) {
            var entry = top.get(i);
            long count = entry.getValue()[0];
            long bytes = entry.getValue()[1];
            double pct = (bytes * 100.0) / totalShallow;

            String pctColor;
            if (pct > 10) pctColor = AnsiStyle.style(c, AnsiStyle.RED, AnsiStyle.BOLD);
            else if (pct > 5) pctColor = AnsiStyle.style(c, AnsiStyle.YELLOW);
            else pctColor = AnsiStyle.style(c, AnsiStyle.DIM);

            String className = humanClassName(entry.getKey());

            String line = RichRenderer.padRight(String.valueOf(i + 1), 4)
                    + RichRenderer.padRight(String.format("%,d", count), 14)
                    + RichRenderer.padRight(RichRenderer.formatBytes(bytes), 16)
                    + pctColor + RichRenderer.padRight(String.format("%.1f%%", pct), 7)
                    + AnsiStyle.style(c, AnsiStyle.RESET)
                    + AnsiStyle.style(c, AnsiStyle.GREEN) + className
                    + AnsiStyle.style(c, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine("  " + line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Quick insights
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD, AnsiStyle.CYAN) + "Insights"
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Check for byte[]/char[] dominance (common leak pattern)
        long byteArrayBytes = 0, charArrayBytes = 0;
        for (var entry : s.histogram().entrySet()) {
            if (entry.getKey().equals("byte[]")) byteArrayBytes = entry.getValue()[1];
            if (entry.getKey().equals("char[]")) charArrayBytes = entry.getValue()[1];
        }
        double byteArrayPct = (byteArrayBytes * 100.0) / totalShallow;
        double charArrayPct = (charArrayBytes * 100.0) / totalShallow;

        if (byteArrayPct > 30) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.YELLOW) + "\u26a0 byte[] occupies "
                            + String.format("%.1f%%", byteArrayPct) + " of heap — check String retention, byte buffers"
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }
        if (charArrayPct > 20) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.YELLOW) + "\u26a0 char[] occupies "
                            + String.format("%.1f%%", charArrayPct) + " of heap — check String duplication"
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }
        if (s.stringCount() > s.totalInstances() * 0.3) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.YELLOW) + "\u26a0 Strings are "
                            + String.format("%.0f%%", (s.stringCount() * 100.0) / s.totalInstances())
                            + " of all instances — consider string deduplication (-XX:+UseStringDeduplication)"
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }

        // Array vs instance ratio
        double arrayRatio = (s.totalArrayBytes() * 100.0) / totalShallow;
        if (arrayRatio > 60) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.CYAN) + "\u2139 Arrays consume "
                            + String.format("%.0f%%", arrayRatio) + " of heap — check collection sizing"
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }

        if (byteArrayPct <= 30 && charArrayPct <= 20 && arrayRatio <= 60
                && s.stringCount() <= s.totalInstances() * 0.3) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.GREEN) + "\u2714 No obvious anomalies detected"
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c,
                String.format("%,d objects, %s", s.totalObjects(), RichRenderer.formatBytes(s.totalShallowBytes())), WIDTH));
    }

    private void printJson(HprofSummary s, int topN, String sortBy) {
        boolean byCount = "count".equalsIgnoreCase(sortBy);
        List<Map.Entry<String, long[]>> top = byCount ? s.topByCount(topN) : s.topBySize(topN);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"file\":\"").append(RichRenderer.escapeJson(s.fileName())).append('"');
        sb.append(",\"fileSize\":").append(s.fileSize());
        sb.append(",\"totalObjects\":").append(s.totalObjects());
        sb.append(",\"totalShallowBytes\":").append(s.totalShallowBytes());
        sb.append(",\"totalInstances\":").append(s.totalInstances());
        sb.append(",\"totalArrays\":").append(s.totalArrays());
        sb.append(",\"stringCount\":").append(s.stringCount());
        sb.append(",\"classCount\":").append(s.classCount());
        sb.append(",\"top\":[");
        for (int i = 0; i < top.size(); i++) {
            var entry = top.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"class\":\"").append(RichRenderer.escapeJson(entry.getKey())).append('"');
            sb.append(",\"instances\":").append(entry.getValue()[0]);
            sb.append(",\"shallowBytes\":").append(entry.getValue()[1]);
            double pct = (entry.getValue()[1] * 100.0) / Math.max(1, s.totalShallowBytes());
            sb.append(",\"percent\":").append(String.format("%.2f", pct));
            sb.append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private static String humanClassName(String name) {
        if (name == null) return "?";
        // Already human-readable from parser (dots, not slashes)
        // Just shorten very long package names
        String[] parts = name.split("\\.");
        if (parts.length > 4) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return name;
    }
}
