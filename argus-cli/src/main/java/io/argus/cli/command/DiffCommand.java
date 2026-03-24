package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.HistoResult;
import io.argus.cli.provider.HistoProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two heap histogram snapshots to detect memory growth (potential leaks).
 */
public final class DiffCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_INTERVAL_SECONDS = 10;
    private static final int DEFAULT_TOP = 20;
    private static final int SNAPSHOT_TOP_N = 500;

    /** Growth threshold percentages for coloring. */
    private static final double WARN_PCT = 10.0;
    private static final double CRIT_PCT = 25.0;

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.diff.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println(messages.get("error.pid.required"));
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[0]));
            return;
        }

        int intervalSeconds = DEFAULT_INTERVAL_SECONDS;
        int topN = DEFAULT_TOP;
        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--top=")) {
                try { topN = Integer.parseInt(arg.substring(6)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--top") && i + 1 < args.length) {
                try { topN = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else {
                // Positional: second arg after PID may be interval
                try {
                    intervalSeconds = Integer.parseInt(arg);
                } catch (NumberFormatException ignored) {}
            }
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        HistoProvider provider = registry.findHistoProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        System.out.println("Taking first snapshot... waiting " + intervalSeconds + "s for second snapshot");

        HistoResult snap1 = provider.getHistogram(pid, SNAPSHOT_TOP_N);

        try {
            Thread.sleep(intervalSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        HistoResult snap2 = provider.getHistogram(pid, SNAPSHOT_TOP_N);

        List<DiffEntry> diffs = computeDiff(snap1, snap2);

        if (json) {
            printJson(diffs, intervalSeconds);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "diff", messages.get("desc.diff")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.diff"),
                WIDTH, "pid:" + pid, "interval:" + intervalSeconds + "s"));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        int classWidth = WIDTH - 46;
        if (classWidth < 20) classWidth = 20;

        String headerLine = RichRenderer.padLeft("#", 3) + "  "
                + RichRenderer.padRight("Class", classWidth) + "  "
                + RichRenderer.padLeft("+Instances", 11) + "  "
                + RichRenderer.padLeft("+Bytes", 10) + "  "
                + RichRenderer.padLeft("Growth", 8);
        System.out.println(RichRenderer.boxLine(headerLine, WIDTH));

        String sep = "\u2500".repeat(3) + "  "
                + "\u2500".repeat(classWidth) + "  "
                + "\u2500".repeat(11) + "  "
                + "\u2500".repeat(10) + "  "
                + "\u2500".repeat(8);
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        int limit = Math.min(topN, diffs.size());
        for (int i = 0; i < limit; i++) {
            DiffEntry d = diffs.get(i);
            String rank = RichRenderer.padLeft(String.valueOf(i + 1), 3);
            String cls = RichRenderer.padRight(
                    RichRenderer.truncate(RichRenderer.humanClassName(d.className()), classWidth), classWidth);
            String inst = RichRenderer.padLeft("+" + formatCount(d.instancesDelta()), 11);
            String bytes = RichRenderer.padLeft(RichRenderer.formatBytes(d.bytesDelta()), 10);
            String growthStr = d.growthPercent() > 0
                    ? String.format("\u25b2 %4.1f%%", d.growthPercent())
                    : "     n/a";
            String growthColor = AnsiStyle.colorByThreshold(useColor, d.growthPercent(), WARN_PCT, CRIT_PCT);
            String growth = growthColor + RichRenderer.padLeft(growthStr, 8)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);

            System.out.println(RichRenderer.boxLine(rank + "  " + cls + "  " + inst + "  " + bytes + "  " + growth, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));

        long totalBytesDelta = diffs.stream().mapToLong(DiffEntry::bytesDelta).sum();
        String summary = diffs.size() + " classes grew \u00b7 +"
                + RichRenderer.formatBytes(totalBytesDelta)
                + " total \u00b7 " + intervalSeconds + "s interval";
        System.out.println(RichRenderer.boxLine(summary, WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static List<DiffEntry> computeDiff(HistoResult snap1, HistoResult snap2) {
        Map<String, HistoResult.Entry> map1 = new HashMap<>();
        for (HistoResult.Entry e : snap1.entries()) {
            map1.put(e.className(), e);
        }

        List<DiffEntry> results = new ArrayList<>();
        for (HistoResult.Entry e2 : snap2.entries()) {
            HistoResult.Entry e1 = map1.get(e2.className());
            long bytesBefore = e1 != null ? e1.bytes() : 0L;
            long instBefore = e1 != null ? e1.instances() : 0L;
            long bytesDelta = e2.bytes() - bytesBefore;
            long instDelta = e2.instances() - instBefore;

            if (bytesDelta > 0) {
                double growthPct = bytesBefore > 0 ? (bytesDelta * 100.0) / bytesBefore : 0.0;
                results.add(new DiffEntry(e2.className(), instBefore, e2.instances(), instDelta,
                        bytesBefore, e2.bytes(), bytesDelta, growthPct));
            }
        }

        results.sort(Comparator.comparingLong(DiffEntry::bytesDelta).reversed());
        return results;
    }

    private static void printJson(List<DiffEntry> diffs, int intervalSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"intervalSeconds\":").append(intervalSeconds)
          .append(",\"entries\":[");
        for (int i = 0; i < diffs.size(); i++) {
            DiffEntry d = diffs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"className\":\"").append(RichRenderer.escapeJson(d.className())).append('"')
              .append(",\"instancesBefore\":").append(d.instancesBefore())
              .append(",\"instancesAfter\":").append(d.instancesAfter())
              .append(",\"instancesDelta\":").append(d.instancesDelta())
              .append(",\"bytesBefore\":").append(d.bytesBefore())
              .append(",\"bytesAfter\":").append(d.bytesAfter())
              .append(",\"bytesDelta\":").append(d.bytesDelta())
              .append(",\"growthPercent\":").append(String.format("%.2f", d.growthPercent()))
              .append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private static String formatCount(long n) {
        if (n < 1_000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%,.0f", (double) n);
        return RichRenderer.formatNumber(n);
    }

    /**
     * Immutable record representing a single class's heap growth between two snapshots.
     */
    private record DiffEntry(
            String className,
            long instancesBefore,
            long instancesAfter,
            long instancesDelta,
            long bytesBefore,
            long bytesAfter,
            long bytesDelta,
            double growthPercent
    ) {}
}
