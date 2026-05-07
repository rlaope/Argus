package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.NmtBaseline;
import io.argus.cli.model.NmtResult;
import io.argus.cli.provider.NmtProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Shows native memory usage by category via jcmd VM.native_memory summary.
 * Requires the target JVM to be started with -XX:NativeMemoryTracking=summary.
 */
public final class NmtCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "nmt";
    }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.nmt.desc");
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

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        Path saveTo = null;
        Path diffWith = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--save=")) {
                saveTo = Path.of(arg.substring("--save=".length()));
            } else if (arg.startsWith("--diff=")) {
                diffWith = Path.of(arg.substring("--diff=".length()));
            }
        }

        NmtProvider provider = registry.findNmtProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        NmtResult result = provider.getNativeMemory(pid);

        // Save baseline (write-through; still render the snapshot for the user)
        if (saveTo != null) {
            try {
                NmtBaseline.save(saveTo, result);
                System.out.println("Saved NMT baseline to: " + saveTo.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("Failed to save baseline: " + e.getMessage());
            }
        }

        // Diff mode short-circuits the normal render — the diff is the answer.
        if (diffWith != null) {
            try {
                NmtBaseline baseline = NmtBaseline.load(diffWith);
                List<NmtBaseline.DiffRow> rows = NmtBaseline.diff(baseline, result);
                if (json) printDiffJson(baseline, result, rows);
                else printDiff(baseline, result, rows, pid, provider.source(), useColor, messages);
            } catch (IOException e) {
                System.err.println("Failed to load baseline " + diffWith + ": " + e.getMessage());
            }
            return;
        }

        if (json) {
            printJson(result);
        } else {
            System.out.print(RichRenderer.brandedHeader(useColor, "nmt", messages.get("desc.nmt")));
            printTable(result, pid, provider.source(), useColor, messages);
        }
    }

    private static void printDiff(NmtBaseline baseline, NmtResult current,
                                  List<NmtBaseline.DiffRow> rows,
                                  long pid, String source, boolean useColor, Messages messages) {
        long elapsedSec = Math.max(1, System.currentTimeMillis() / 1000L - baseline.capturedAtEpochSec());
        System.out.print(RichRenderer.brandedHeader(useColor, "nmt --diff",
                "Native memory delta vs. saved baseline"));
        System.out.println(RichRenderer.boxHeader(useColor, "NMT Diff",
                WIDTH, "pid:" + pid, "source:" + source,
                "elapsed:" + RichRenderer.formatDuration(elapsedSec * 1000)));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        long totalDeltaKB = current.totalCommittedKB() - baseline.snapshot().totalCommittedKB();
        String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String deltaColor = totalDeltaKB > 0
                ? AnsiStyle.style(useColor, AnsiStyle.YELLOW, AnsiStyle.BOLD)
                : AnsiStyle.style(useColor, AnsiStyle.GREEN);
        System.out.println(RichRenderer.boxLine(
                "  Total committed: " + RichRenderer.formatKB(baseline.snapshot().totalCommittedKB())
                + dim + "  →  " + reset
                + RichRenderer.formatKB(current.totalCommittedKB())
                + "    " + deltaColor + signed(totalDeltaKB) + reset
                + dim + "  (" + perMinute(totalDeltaKB, elapsedSec) + ")" + reset, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String hdr = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + RichRenderer.padRight("Category", 22)
                + RichRenderer.padLeft("Baseline", 12) + "  "
                + RichRenderer.padLeft("Current", 12) + "  "
                + RichRenderer.padLeft("Δ committed", 14) + "  "
                + "  Δ %" + reset;
        System.out.println(RichRenderer.boxLine(hdr, WIDTH));
        System.out.println(RichRenderer.boxLine("─".repeat(Math.min(76, WIDTH - 4)), WIDTH));

        rows.sort((a, b) -> Long.compare(Math.abs(b.committedDeltaKB()), Math.abs(a.committedDeltaKB())));
        for (NmtBaseline.DiffRow row : rows) {
            long delta = row.committedDeltaKB();
            if (delta == 0) continue; // hide noise — only growth/shrink
            String dColor = delta > 0
                    ? AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                    : AnsiStyle.style(useColor, AnsiStyle.GREEN);
            String pctStr = Double.isInfinite(row.committedDeltaPct())
                    ? "  new"
                    : String.format("%+5.1f%%", row.committedDeltaPct());
            String line = AnsiStyle.style(useColor, AnsiStyle.CYAN)
                    + RichRenderer.padRight(RichRenderer.truncate(row.name(), 20), 22) + reset
                    + RichRenderer.padLeft(RichRenderer.formatKB(row.baseCommittedKB()), 12) + "  "
                    + RichRenderer.padLeft(RichRenderer.formatKB(row.curCommittedKB()), 12) + "  "
                    + dColor + RichRenderer.padLeft(signed(delta), 14) + reset + "  "
                    + dColor + pctStr + reset;
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor,
                "growth shown vs. baseline taken " + RichRenderer.formatDuration(elapsedSec * 1000) + " ago",
                WIDTH));
    }

    private static String signed(long deltaKB) {
        String mag = RichRenderer.formatKB(Math.abs(deltaKB));
        return (deltaKB >= 0 ? "+" : "-") + mag;
    }

    private static String perMinute(long deltaKB, long elapsedSec) {
        if (elapsedSec <= 0) return "?";
        double perMin = deltaKB * 60.0 / elapsedSec;
        return String.format("%+.1f KB/min", perMin);
    }

    private static void printDiffJson(NmtBaseline baseline, NmtResult current,
                                      List<NmtBaseline.DiffRow> rows) {
        long elapsed = Math.max(1, System.currentTimeMillis() / 1000L - baseline.capturedAtEpochSec());
        StringBuilder sb = new StringBuilder();
        sb.append("{\"elapsedSec\":").append(elapsed)
          .append(",\"baselineCommittedKB\":").append(baseline.snapshot().totalCommittedKB())
          .append(",\"currentCommittedKB\":").append(current.totalCommittedKB())
          .append(",\"totalDeltaKB\":").append(current.totalCommittedKB() - baseline.snapshot().totalCommittedKB())
          .append(",\"rows\":[");
        boolean first = true;
        for (NmtBaseline.DiffRow row : rows) {
            if (!first) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(row.name())).append('"')
              .append(",\"baseCommittedKB\":").append(row.baseCommittedKB())
              .append(",\"curCommittedKB\":").append(row.curCommittedKB())
              .append(",\"committedDeltaKB\":").append(row.committedDeltaKB())
              .append('}');
            first = false;
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private static void printTable(NmtResult result, long pid, String source,
                                   boolean useColor, Messages messages) {
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.nmt"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (result.totalReservedKB() == 0 && result.categories().isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                            + "NMT not enabled on this JVM."
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM)
                            + "Start the JVM with: -XX:NativeMemoryTracking=summary"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
            return;
        }

        // Total summary with progress bars
        long totalReserved = result.totalReservedKB();
        long totalCommitted = result.totalCommittedKB();

        String totalLabel = AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Total"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(totalLabel, WIDTH));

        double reservedGB = totalReserved / 1024.0 / 1024.0;
        double committedGB = totalCommitted / 1024.0 / 1024.0;
        String totalInfo = String.format("  Reserved: %s    Committed: %s",
                RichRenderer.formatKB(totalReserved), RichRenderer.formatKB(totalCommitted));
        System.out.println(RichRenderer.boxLine(totalInfo, WIDTH));

        if (totalReserved > 0) {
            double pct = (double) totalCommitted / totalReserved * 100.0;
            String bar = RichRenderer.progressBar(useColor, pct, 24);
            String barLine = "  " + bar + "  " + String.format("%.1f%%", pct) + " committed of reserved";
            System.out.println(RichRenderer.boxLine(barLine, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Column header
        String hdr = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + RichRenderer.padRight("Category", 20)
                + RichRenderer.padLeft("Reserved", 12) + "  "
                + RichRenderer.padLeft("Committed", 12) + "  "
                + "Usage"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(hdr, WIDTH));
        System.out.println(RichRenderer.boxLine("\u2500".repeat(Math.min(70, WIDTH - 4)), WIDTH));

        // Per-category rows
        List<NmtResult.NmtCategory> cats = result.categories();
        for (NmtResult.NmtCategory cat : cats) {
            double pct = cat.reservedKB() > 0
                    ? (double) cat.committedKB() / cat.reservedKB() * 100.0
                    : 0.0;

            String bar = RichRenderer.progressBar(useColor, pct, 16);
            String nameCell = AnsiStyle.style(useColor, AnsiStyle.CYAN)
                    + RichRenderer.padRight(RichRenderer.truncate(cat.name(), 18), 20)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            String line = nameCell
                    + RichRenderer.padLeft(RichRenderer.formatKB(cat.reservedKB()), 12) + "  "
                    + RichRenderer.padLeft(RichRenderer.formatKB(cat.committedKB()), 12) + "  "
                    + bar + "  " + String.format("%5.1f%%", pct);
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, cats.size() + " categories", WIDTH));
    }

    private static void printJson(NmtResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalReservedKB\":").append(result.totalReservedKB())
          .append(",\"totalCommittedKB\":").append(result.totalCommittedKB())
          .append(",\"categories\":[");
        boolean first = true;
        for (NmtResult.NmtCategory cat : result.categories()) {
            if (!first) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(cat.name())).append('"')
              .append(",\"reservedKB\":").append(cat.reservedKB())
              .append(",\"committedKB\":").append(cat.committedKB())
              .append('}');
            first = false;
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
