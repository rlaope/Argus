package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.gclog.GcLogAnalysis;
import io.argus.cli.gclog.GcLogAnalyzer;
import io.argus.cli.gclog.GcLogParser;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two GC log files and shows metric deltas with color-coded
 * improvements and regressions.
 *
 * <p>Usage:
 * <pre>
 * argus gclogdiff gc1.log gc2.log
 * argus gclogdiff gc1.log gc2.log --format=json
 * </pre>
 */
public final class GcLogDiffCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name() { return "gclogdiff"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public boolean supportsTui() { return false; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gclogdiff.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length < 2) {
            System.err.println("Usage: argus gclogdiff <file1> <file2> [--format=json]");
            return;
        }

        String file1 = null;
        String file2 = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        for (String arg : args) {
            if (arg.equals("--format=json")) {
                json = true;
            } else if (!arg.startsWith("--")) {
                if (file1 == null) file1 = arg;
                else if (file2 == null) file2 = arg;
            }
        }

        if (file1 == null || file2 == null) {
            System.err.println("Usage: argus gclogdiff <file1> <file2> [--format=json]");
            return;
        }

        Path path1 = Path.of(file1);
        Path path2 = Path.of(file2);

        if (!Files.exists(path1)) { System.err.println("File not found: " + path1); return; }
        if (!Files.exists(path2)) { System.err.println("File not found: " + path2); return; }

        GcLogAnalysis a, b;
        try {
            a = GcLogAnalyzer.analyze(GcLogParser.parse(path1));
        } catch (IOException e) {
            System.err.println("Failed to read " + path1 + ": " + e.getMessage());
            return;
        }
        try {
            b = GcLogAnalyzer.analyze(GcLogParser.parse(path2));
        } catch (IOException e) {
            System.err.println("Failed to read " + path2 + ": " + e.getMessage());
            return;
        }

        if (json) {
            int code = printJson(a, b, path1, path2);
            if (code != 0) System.exit(code);
            return;
        }

        printRich(a, b, path1, path2, useColor);
    }

    private void printRich(GcLogAnalysis a, GcLogAnalysis b, Path path1, Path path2, boolean c) {
        System.out.print(RichRenderer.brandedHeader(c, "gclogdiff",
                "GC log comparison with regression detection"));
        System.out.println(RichRenderer.boxHeader(c, "GC Log Diff", WIDTH,
                path1.getFileName().toString(), "vs", path2.getFileName().toString()));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Column widths
        int metricW = 22;
        int valW = 16;
        int deltaW = WIDTH - metricW - valW * 2 - 6;

        // Header row
        String hdr = AnsiStyle.style(c, AnsiStyle.BOLD)
                + RichRenderer.padRight("Metric", metricW)
                + RichRenderer.padLeft(RichRenderer.truncate(path1.getFileName().toString(), valW - 1), valW)
                + RichRenderer.padLeft(RichRenderer.truncate(path2.getFileName().toString(), valW - 1), valW)
                + RichRenderer.padLeft("Delta", deltaW)
                + AnsiStyle.style(c, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine("  " + hdr, WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));

        // Track regressions and improvements
        int regressions = 0;
        int improvements = 0;
        int unchanged = 0;

        // Throughput (higher = better)
        DeltaResult throughput = deltaDouble(a.throughputPercent(), b.throughputPercent(), true);
        rowDouble(c, "Throughput %",
                String.format("%.1f%%", a.throughputPercent()),
                String.format("%.1f%%", b.throughputPercent()),
                throughput, metricW, valW, deltaW);
        regressions += throughput.regression ? 1 : 0;
        improvements += throughput.improvement ? 1 : 0;
        unchanged += throughput.unchanged ? 1 : 0;

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Pause metrics (lower = better)
        DeltaResult p50 = deltaLong(a.p50PauseMs(), b.p50PauseMs(), false);
        rowLong(c, "p50 Pause ms", a.p50PauseMs() + "ms", b.p50PauseMs() + "ms", p50, metricW, valW, deltaW);
        regressions += p50.regression ? 1 : 0; improvements += p50.improvement ? 1 : 0; unchanged += p50.unchanged ? 1 : 0;

        DeltaResult p95 = deltaLong(a.p95PauseMs(), b.p95PauseMs(), false);
        rowLong(c, "p95 Pause ms", a.p95PauseMs() + "ms", b.p95PauseMs() + "ms", p95, metricW, valW, deltaW);
        regressions += p95.regression ? 1 : 0; improvements += p95.improvement ? 1 : 0; unchanged += p95.unchanged ? 1 : 0;

        DeltaResult p99 = deltaLong(a.p99PauseMs(), b.p99PauseMs(), false);
        rowLong(c, "p99 Pause ms", a.p99PauseMs() + "ms", b.p99PauseMs() + "ms", p99, metricW, valW, deltaW);
        regressions += p99.regression ? 1 : 0; improvements += p99.improvement ? 1 : 0; unchanged += p99.unchanged ? 1 : 0;

        DeltaResult maxPause = deltaLong(a.maxPauseMs(), b.maxPauseMs(), false);
        rowLong(c, "Max Pause ms", a.maxPauseMs() + "ms", b.maxPauseMs() + "ms", maxPause, metricW, valW, deltaW);
        regressions += maxPause.regression ? 1 : 0; improvements += maxPause.improvement ? 1 : 0; unchanged += maxPause.unchanged ? 1 : 0;

        DeltaResult avgPause = deltaLong(a.avgPauseMs(), b.avgPauseMs(), false);
        rowLong(c, "Avg Pause ms", a.avgPauseMs() + "ms", b.avgPauseMs() + "ms", avgPause, metricW, valW, deltaW);
        regressions += avgPause.regression ? 1 : 0; improvements += avgPause.improvement ? 1 : 0; unchanged += avgPause.unchanged ? 1 : 0;

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // GC counts (lower = better)
        DeltaResult fullGc = deltaLong(a.fullGcEvents(), b.fullGcEvents(), false);
        rowLong(c, "Full GC Count", String.valueOf(a.fullGcEvents()), String.valueOf(b.fullGcEvents()), fullGc, metricW, valW, deltaW);
        regressions += fullGc.regression ? 1 : 0; improvements += fullGc.improvement ? 1 : 0; unchanged += fullGc.unchanged ? 1 : 0;

        DeltaResult totalPause = deltaLong(a.totalPauseMs(), b.totalPauseMs(), false);
        rowLong(c, "Total Pause ms", a.totalPauseMs() + "ms", b.totalPauseMs() + "ms", totalPause, metricW, valW, deltaW);
        regressions += totalPause.regression ? 1 : 0; improvements += totalPause.improvement ? 1 : 0; unchanged += totalPause.unchanged ? 1 : 0;

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Heap metrics (lower = better)
        DeltaResult peakHeap = deltaLong(a.peakHeapKB(), b.peakHeapKB(), false);
        rowLong(c, "Peak Heap KB", RichRenderer.formatKB(a.peakHeapKB()), RichRenderer.formatKB(b.peakHeapKB()), peakHeap, metricW, valW, deltaW);
        regressions += peakHeap.regression ? 1 : 0; improvements += peakHeap.improvement ? 1 : 0; unchanged += peakHeap.unchanged ? 1 : 0;

        DeltaResult avgHeap = deltaLong(a.avgHeapAfterKB(), b.avgHeapAfterKB(), false);
        rowLong(c, "Avg Heap After KB", RichRenderer.formatKB(a.avgHeapAfterKB()), RichRenderer.formatKB(b.avgHeapAfterKB()), avgHeap, metricW, valW, deltaW);
        regressions += avgHeap.regression ? 1 : 0; improvements += avgHeap.improvement ? 1 : 0; unchanged += avgHeap.unchanged ? 1 : 0;

        // Cause shift section
        if (!a.causeBreakdown().isEmpty() || !b.causeBreakdown().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxSeparator(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD, AnsiStyle.CYAN)
                            + "GC Cause Shifts" + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            Set<String> allCauses = new TreeSet<>();
            allCauses.addAll(a.causeBreakdown().keySet());
            allCauses.addAll(b.causeBreakdown().keySet());

            for (String cause : allCauses) {
                GcLogAnalysis.CauseStats sa = a.causeBreakdown().get(cause);
                GcLogAnalysis.CauseStats sb = b.causeBreakdown().get(cause);

                if (sa == null) {
                    // New cause in b
                    System.out.println(RichRenderer.boxLine(
                            "  " + AnsiStyle.style(c, AnsiStyle.YELLOW) + "+ NEW: "
                                    + AnsiStyle.style(c, AnsiStyle.RESET)
                                    + RichRenderer.padRight(RichRenderer.truncate(cause, 30), 32)
                                    + "  count=" + sb.count() + "  avg=" + sb.avgMs() + "ms", WIDTH));
                } else if (sb == null) {
                    // Cause removed in b
                    System.out.println(RichRenderer.boxLine(
                            "  " + AnsiStyle.style(c, AnsiStyle.GREEN) + "- REM: "
                                    + AnsiStyle.style(c, AnsiStyle.RESET)
                                    + RichRenderer.padRight(RichRenderer.truncate(cause, 30), 32)
                                    + "  count=" + sa.count() + "  avg=" + sa.avgMs() + "ms", WIDTH));
                } else {
                    // Changed
                    int countDiff = sb.count() - sa.count();
                    long avgDiff = sb.avgMs() - sa.avgMs();
                    String countStr = countDiff == 0 ? "=" : (countDiff > 0 ? "+" + countDiff : String.valueOf(countDiff));
                    String avgStr = avgDiff == 0 ? "=" : (avgDiff > 0 ? "+" + avgDiff + "ms" : avgDiff + "ms");
                    System.out.println(RichRenderer.boxLine(
                            "  " + RichRenderer.padRight(RichRenderer.truncate(cause, 30), 32)
                                    + "  count:" + countStr + "  avg:" + avgStr, WIDTH));
                }
            }
        }

        // Verdict
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));

        String verdict;
        String verdictColor;
        if (regressions == 0 && improvements > 0) {
            verdict = "\u2713 ALL METRICS IMPROVED";
            verdictColor = AnsiStyle.style(c, AnsiStyle.GREEN, AnsiStyle.BOLD);
        } else if (regressions == 0 && improvements == 0) {
            verdict = "\u2014 NO CHANGE";
            verdictColor = AnsiStyle.style(c, AnsiStyle.BOLD);
        } else if (regressions > 0 && improvements == 0) {
            verdict = "\u2717 REGRESSION DETECTED  (" + regressions + " worse, " + unchanged + " unchanged)";
            verdictColor = AnsiStyle.style(c, AnsiStyle.RED, AnsiStyle.BOLD);
        } else {
            verdict = "\u26a0 MIXED RESULTS  (" + improvements + " improved, " + regressions + " regressed, " + unchanged + " unchanged)";
            verdictColor = AnsiStyle.style(c, AnsiStyle.YELLOW, AnsiStyle.BOLD);
        }

        System.out.println(RichRenderer.boxLine(
                "  " + verdictColor + verdict + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c, null, WIDTH));
    }

    private void rowLong(boolean c, String metric, String valA, String valB,
                         DeltaResult dr, int metricW, int valW, int deltaW) {
        String deltaColor = dr.regression ? AnsiStyle.style(c, AnsiStyle.RED)
                : dr.improvement ? AnsiStyle.style(c, AnsiStyle.GREEN) : "";
        String line = "  " + RichRenderer.padRight(metric, metricW)
                + RichRenderer.padLeft(valA, valW)
                + RichRenderer.padLeft(valB, valW)
                + deltaColor + RichRenderer.padLeft(dr.text, deltaW)
                + AnsiStyle.style(c, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(line, WIDTH));
    }

    private void rowDouble(boolean c, String metric, String valA, String valB,
                           DeltaResult dr, int metricW, int valW, int deltaW) {
        rowLong(c, metric, valA, valB, dr, metricW, valW, deltaW);
    }

    // higherIsBetter: true=throughput, false=pause/heap
    private static DeltaResult deltaLong(long a, long b, boolean higherIsBetter) {
        if (a == b) return new DeltaResult("—", false, false, true);
        long diff = b - a;  // positive = b is higher
        double pct = a != 0 ? (double) diff / a * 100 : 0;
        String sign = diff > 0 ? "+" : "";
        String text = sign + diff + " (" + sign + String.format("%.0f%%", pct) + ")";
        boolean improved = higherIsBetter ? diff > 0 : diff < 0;
        boolean regressed = higherIsBetter ? diff < 0 : diff > 0;
        return new DeltaResult(text, regressed, improved, false);
    }

    private static DeltaResult deltaDouble(double a, double b, boolean higherIsBetter) {
        double diff = b - a;
        if (Math.abs(diff) < 0.01) return new DeltaResult("—", false, false, true);
        String sign = diff > 0 ? "+" : "";
        String text = sign + String.format("%.1f%%", diff);
        boolean improved = higherIsBetter ? diff > 0 : diff < 0;
        boolean regressed = higherIsBetter ? diff < 0 : diff > 0;
        return new DeltaResult(text, regressed, improved, false);
    }

    private static int printJson(GcLogAnalysis a, GcLogAnalysis b, Path path1, Path path2) {
        boolean anyMajorRegression = false;

        // Check >10% regression on key pause metrics (lower is better)
        anyMajorRegression |= regressionExceeds(a.p99PauseMs(), b.p99PauseMs(), false, 10);
        anyMajorRegression |= regressionExceeds(a.maxPauseMs(), b.maxPauseMs(), false, 10);
        anyMajorRegression |= regressionExceeds(a.throughputPercent(), b.throughputPercent(), true, 10);

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"file1\":\"").append(RichRenderer.escapeJson(path1.toString())).append('"');
        sb.append(",\"file2\":\"").append(RichRenderer.escapeJson(path2.toString())).append('"');
        sb.append(",\"regression\":").append(anyMajorRegression);
        sb.append(",\"metrics\":{");
        sb.append("\"throughputPct\":[").append(String.format("%.1f", a.throughputPercent()))
                .append(',').append(String.format("%.1f", b.throughputPercent())).append(']');
        sb.append(",\"p50PauseMs\":[").append(a.p50PauseMs()).append(',').append(b.p50PauseMs()).append(']');
        sb.append(",\"p95PauseMs\":[").append(a.p95PauseMs()).append(',').append(b.p95PauseMs()).append(']');
        sb.append(",\"p99PauseMs\":[").append(a.p99PauseMs()).append(',').append(b.p99PauseMs()).append(']');
        sb.append(",\"maxPauseMs\":[").append(a.maxPauseMs()).append(',').append(b.maxPauseMs()).append(']');
        sb.append(",\"avgPauseMs\":[").append(a.avgPauseMs()).append(',').append(b.avgPauseMs()).append(']');
        sb.append(",\"fullGcEvents\":[").append(a.fullGcEvents()).append(',').append(b.fullGcEvents()).append(']');
        sb.append(",\"totalPauseMs\":[").append(a.totalPauseMs()).append(',').append(b.totalPauseMs()).append(']');
        sb.append(",\"peakHeapKB\":[").append(a.peakHeapKB()).append(',').append(b.peakHeapKB()).append(']');
        sb.append(",\"avgHeapAfterKB\":[").append(a.avgHeapAfterKB()).append(',').append(b.avgHeapAfterKB()).append(']');
        sb.append("}}");
        System.out.println(sb);
        return anyMajorRegression ? 1 : 0;
    }

    private static boolean regressionExceeds(long a, long b, boolean higherIsBetter, double thresholdPct) {
        if (a == 0) return false;
        double diff = b - a;
        double pct = diff / a * 100;
        // regression: if higherIsBetter, diff<0 is bad; else diff>0 is bad
        return higherIsBetter ? pct < -thresholdPct : pct > thresholdPct;
    }

    private static boolean regressionExceeds(double a, double b, boolean higherIsBetter, double thresholdPct) {
        if (a == 0) return false;
        double diff = b - a;
        double pct = diff / a * 100;
        return higherIsBetter ? pct < -thresholdPct : pct > thresholdPct;
    }

    private record DeltaResult(String text, boolean regression, boolean improvement, boolean unchanged) {}
}
