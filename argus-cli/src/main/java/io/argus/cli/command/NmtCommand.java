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
        int watchInterval = -1; // -1 means not in watch mode

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--save=")) {
                saveTo = Path.of(arg.substring("--save=".length()));
            } else if (arg.equals("--save") && i + 1 < args.length) {
                saveTo = Path.of(args[++i]);
            } else if (arg.startsWith("--diff=")) {
                diffWith = Path.of(arg.substring("--diff=".length()));
            } else if (arg.equals("--diff") && i + 1 < args.length) {
                diffWith = Path.of(args[++i]);
            } else if (arg.equals("--watch")) {
                watchInterval = 2;
            } else if (arg.startsWith("--watch=")) {
                try {
                    watchInterval = Integer.parseInt(arg.substring("--watch=".length()));
                    if (watchInterval < 1) watchInterval = 2;
                } catch (NumberFormatException ignored) {
                    watchInterval = 2;
                }
            }
        }

        NmtProvider provider = registry.findNmtProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        // Watch mode — live delta loop, short-circuits everything else
        if (watchInterval > 0) {
            runWatch(pid, provider, watchInterval, useColor, messages);
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

    private static final String CLEAR_SCREEN = "\033[2J\033[H";
    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";

    private static void runWatch(long pid, NmtProvider provider, int intervalSec,
                                 boolean useColor, Messages messages) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        Thread shutdownHook = new Thread(() -> {
            System.out.print(SHOW_CURSOR);
            if (!isWindows) setRawMode(false);
            System.out.println();
        }, "argus-nmt-watch-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        System.out.print(HIDE_CURSOR);

        try {
            if (!isWindows) setRawMode(true);

            // Initial snapshot
            NmtResult previous = provider.getNativeMemory(pid);
            long previousAtSec = System.currentTimeMillis() / 1000L;

            // Check NMT enabled
            if (previous.totalReservedKB() == 0 && previous.categories().isEmpty()) {
                System.out.print(SHOW_CURSOR);
                if (!isWindows) setRawMode(false);
                System.err.println(AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                        + "NMT not enabled on this JVM."
                        + AnsiStyle.style(useColor, AnsiStyle.RESET));
                System.err.println(AnsiStyle.style(useColor, AnsiStyle.DIM)
                        + "Start the JVM with: -XX:NativeMemoryTracking=summary"
                        + AnsiStyle.style(useColor, AnsiStyle.RESET));
                System.exit(1);
            }

            // Render initial snapshot with zero deltas
            StringBuilder out = new StringBuilder();
            out.append(CLEAR_SCREEN);
            printWatchFrame(out, pid, provider.source(), previous, previous, 0, intervalSec, useColor, messages);
            System.out.print(out);
            System.out.flush();

            while (true) {
                // Wait intervalSec with key polling
                long deadline = System.currentTimeMillis() + intervalSec * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    if (System.in.available() > 0) {
                        int key = System.in.read();
                        if (key == 'q' || key == 'Q' || key == 3) {
                            return;
                        }
                        if (key == 'r' || key == 'R') break;
                    }
                    Thread.sleep(100);
                }

                NmtResult current = provider.getNativeMemory(pid);
                long currentAtSec = System.currentTimeMillis() / 1000L;
                long elapsedSec = Math.max(1, currentAtSec - previousAtSec);

                StringBuilder frame = new StringBuilder();
                frame.append(CLEAR_SCREEN);
                printWatchFrame(frame, pid, provider.source(), previous, current, elapsedSec, intervalSec, useColor, messages);
                System.out.print(frame);
                System.out.flush();

                previous = current;
                previousAtSec = currentAtSec;
            }
        } catch (InterruptedException | IOException e) {
            // Normal exit
        } finally {
            System.out.print(SHOW_CURSOR);
            if (!isWindows) setRawMode(false);
            System.out.println();
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) {}
        }
    }

    private static void printWatchFrame(StringBuilder sb, long pid, String source,
                                        NmtResult previous, NmtResult current,
                                        long elapsedSec, int intervalSec,
                                        boolean useColor, Messages messages) {
        String bold  = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim   = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String cyan  = AnsiStyle.style(useColor, AnsiStyle.CYAN);
        String yellow = AnsiStyle.style(useColor, AnsiStyle.YELLOW, AnsiStyle.BOLD);
        String green  = AnsiStyle.style(useColor, AnsiStyle.GREEN);

        // Header
        sb.append(" ").append(bold).append(cyan).append("argus nmt --watch").append(reset)
          .append(dim).append("  pid:").append(pid)
          .append("  source:").append(source)
          .append("  ").append(intervalSec).append("s refresh")
          .append("  q:quit").append(reset).append("\n");
        sb.append(" ").append("─".repeat(72)).append("\n");

        // Total row
        long totalDeltaKB = current.totalCommittedKB() - previous.totalCommittedKB();
        String totalDeltaColor = totalDeltaKB > 0 ? yellow : green;
        sb.append(bold)
          .append(RichRenderer.padRight("Total committed", 22)).append(reset)
          .append(RichRenderer.padLeft(RichRenderer.formatKB(previous.totalCommittedKB()), 12))
          .append(dim).append("  →  ").append(reset)
          .append(RichRenderer.padLeft(RichRenderer.formatKB(current.totalCommittedKB()), 12))
          .append("  ")
          .append(totalDeltaColor).append(RichRenderer.padLeft(signed(totalDeltaKB), 12)).append(reset)
          .append(dim).append("  ").append(perSec(totalDeltaKB, elapsedSec)).append(reset)
          .append("\n");
        sb.append(" ").append("─".repeat(72)).append("\n");

        // Column header
        sb.append(bold)
          .append(RichRenderer.padRight("Category", 22))
          .append(RichRenderer.padLeft("Prev", 12)).append("  ")
          .append(RichRenderer.padLeft("Now", 12)).append("  ")
          .append(RichRenderer.padLeft("Δ committed", 14)).append("  ")
          .append("  KB/s").append(reset).append("\n");
        sb.append(" ").append("─".repeat(72)).append("\n");

        // Per-category rows — all shown; zero-delta rows are dimmed
        List<NmtBaseline.DiffRow> rows = NmtBaseline.diff(
                new NmtBaseline(previousAtSec(elapsedSec), previous), current);
        rows.sort((a, b) -> Long.compare(Math.abs(b.committedDeltaKB()), Math.abs(a.committedDeltaKB())));

        for (NmtBaseline.DiffRow row : rows) {
            long delta = row.committedDeltaKB();
            boolean zeroDelta = delta == 0;
            String nameStyle = zeroDelta ? dim : cyan;
            String deltaStyle = zeroDelta ? dim
                    : delta > 0 ? AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                    : green;

            sb.append(nameStyle)
              .append(RichRenderer.padRight(RichRenderer.truncate(row.name(), 20), 22)).append(reset)
              .append(zeroDelta ? dim : "")
              .append(RichRenderer.padLeft(RichRenderer.formatKB(row.baseCommittedKB()), 12)).append("  ")
              .append(RichRenderer.padLeft(RichRenderer.formatKB(row.curCommittedKB()), 12)).append(reset)
              .append("  ")
              .append(deltaStyle).append(RichRenderer.padLeft(signed(delta), 14)).append(reset)
              .append("  ")
              .append(zeroDelta ? dim : deltaStyle)
              .append(RichRenderer.padLeft(perSec(delta, elapsedSec), 8)).append(reset)
              .append("\n");
        }

        sb.append(" ").append("─".repeat(72)).append("\n");
        sb.append(dim).append(" q:quit  r:force refresh").append(reset).append("\n");
    }

    /** Returns the epoch-sec of the previous snapshot, derived from elapsed. */
    private static long previousAtSec(long elapsedSec) {
        return System.currentTimeMillis() / 1000L - Math.max(1, elapsedSec);
    }

    private static String perSec(long deltaKB, long elapsedSec) {
        if (elapsedSec <= 0) return "?";
        double rate = deltaKB / (double) elapsedSec;
        return String.format("%+.1f KB/s", rate);
    }

    private static void setRawMode(boolean raw) {
        try {
            String sttyCmd = raw ? "stty raw -echo < /dev/tty" : "stty cooked echo < /dev/tty";
            new ProcessBuilder("/bin/sh", "-c", sttyCmd)
                    .inheritIO()
                    .start()
                    .waitFor();
        } catch (Exception ignored) {}
    }

    private static void printDiff(NmtBaseline baseline, NmtResult current,
                                  List<NmtBaseline.DiffRow> rows,
                                  long pid, String source, boolean useColor, Messages messages) {
        long elapsedSec = Math.max(1, System.currentTimeMillis() / 1000L - baseline.capturedAtEpochSec());
        System.out.print(RichRenderer.brandedHeader(useColor, "nmt --diff",
                messages.get("nmt.diff.subtitle")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.nmt.diff"),
                WIDTH, "pid:" + pid, "source:" + source,
                "elapsed:" + RichRenderer.formatDuration(elapsedSec * 1000)));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Banner line: growth summary since baseline
        long totalCommittedDeltaKB = current.totalCommittedKB() - baseline.snapshot().totalCommittedKB();
        long totalReservedDeltaKB  = current.totalReservedKB()  - baseline.snapshot().totalReservedKB();
        String savedAt = java.time.Instant.ofEpochSecond(baseline.capturedAtEpochSec())
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String bannerColor = totalCommittedDeltaKB > 0
                ? AnsiStyle.style(useColor, AnsiStyle.YELLOW, AnsiStyle.BOLD)
                : AnsiStyle.style(useColor, AnsiStyle.GREEN);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim   = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String banner = bannerColor
                + messages.get("nmt.diff.banner", savedAt,
                        signed(totalCommittedDeltaKB), signed(totalReservedDeltaKB))
                + reset;
        System.out.println(RichRenderer.boxLine(banner, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Filter rows: only show where |reservedDelta| > 0 OR |committedDelta| > 0
        List<NmtBaseline.DiffRow> nonZero = new java.util.ArrayList<>();
        for (NmtBaseline.DiffRow row : rows) {
            if (Math.abs(row.reservedDeltaKB()) > 0 || Math.abs(row.committedDeltaKB()) > 0) {
                nonZero.add(row);
            }
        }

        if (nonZero.isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.GREEN)
                    + messages.get("nmt.diff.no.growth")
                    + reset, WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxFooter(useColor,
                    messages.get("nmt.diff.footer", RichRenderer.formatDuration(elapsedSec * 1000)),
                    WIDTH));
            return;
        }

        // Sort descending by committed delta (largest growth first)
        nonZero.sort((a, b) -> Long.compare(b.committedDeltaKB(), a.committedDeltaKB()));

        // Column header: Category | Reserved Δ | Committed Δ | Reserved (now) | Committed (now)
        String hdr = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + RichRenderer.padRight(messages.get("nmt.diff.col.category"), 22)
                + RichRenderer.padLeft(messages.get("nmt.diff.col.reserved.delta"), 13) + "  "
                + RichRenderer.padLeft(messages.get("nmt.diff.col.committed.delta"), 13) + "  "
                + RichRenderer.padLeft(messages.get("nmt.diff.col.reserved.now"), 12) + "  "
                + RichRenderer.padLeft(messages.get("nmt.diff.col.committed.now"), 12)
                + reset;
        System.out.println(RichRenderer.boxLine(hdr, WIDTH));
        System.out.println(RichRenderer.boxLine("─".repeat(Math.min(82, WIDTH - 4)), WIDTH));

        for (NmtBaseline.DiffRow row : nonZero) {
            long cDelta = row.committedDeltaKB();
            long rDelta = row.reservedDeltaKB();
            // Red highlight: committed delta >= 5% of original committed
            boolean isHot = row.baseCommittedKB() > 0
                    && row.committedDeltaPct() >= 5.0;
            boolean isNew = row.baseCommittedKB() == 0 && row.curCommittedKB() > 0;
            String nameStyle = (isHot || isNew)
                    ? AnsiStyle.style(useColor, AnsiStyle.RED, AnsiStyle.BOLD)
                    : AnsiStyle.style(useColor, AnsiStyle.CYAN);
            String cDeltaStyle = cDelta > 0
                    ? ((isHot || isNew)
                            ? AnsiStyle.style(useColor, AnsiStyle.RED, AnsiStyle.BOLD)
                            : AnsiStyle.style(useColor, AnsiStyle.YELLOW))
                    : AnsiStyle.style(useColor, AnsiStyle.GREEN);
            String rDeltaStyle = rDelta > 0
                    ? AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                    : AnsiStyle.style(useColor, AnsiStyle.GREEN);
            String line = nameStyle
                    + RichRenderer.padRight(RichRenderer.truncate(row.name(), 20), 22) + reset
                    + rDeltaStyle + RichRenderer.padLeft(signed(rDelta), 13) + reset + "  "
                    + cDeltaStyle + RichRenderer.padLeft(signed(cDelta), 13) + reset + "  "
                    + dim + RichRenderer.padLeft(RichRenderer.formatKB(row.curReservedKB()), 12) + reset + "  "
                    + dim + RichRenderer.padLeft(RichRenderer.formatKB(row.curCommittedKB()), 12) + reset;
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor,
                messages.get("nmt.diff.footer", RichRenderer.formatDuration(elapsedSec * 1000)),
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
        long currentAtSec = System.currentTimeMillis() / 1000L;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"baseline_at\":").append(baseline.capturedAtEpochSec())
          .append(",\"current_at\":").append(currentAtSec)
          .append(",\"categories\":[");
        boolean first = true;
        for (NmtBaseline.DiffRow row : rows) {
            if (!first) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(row.name())).append('"')
              .append(",\"reservedDelta\":").append(row.reservedDeltaKB())
              .append(",\"committedDelta\":").append(row.committedDeltaKB())
              .append(",\"reservedNow\":").append(row.curReservedKB())
              .append(",\"committedNow\":").append(row.curCommittedKB())
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
