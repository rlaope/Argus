package io.argus.cli.command;

import io.argus.cli.classleak.ClassLeakAnalyzer;
import io.argus.cli.classleak.ClassLeakDiff;
import io.argus.cli.classleak.ClassLeakSnapshot;
import io.argus.cli.classleak.ClassLoaderEntry;
import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Classloader-level metaspace leak attribution.
 *
 * <p>Uses {@code jcmd VM.classloader_stats} to attribute class counts and metaspace
 * usage per classloader instance.
 *
 * <p>Usage:
 * <pre>
 *   argus classleak &lt;pid&gt;                   Top-N classloaders by class count
 *   argus classleak &lt;pid&gt; --top=20           Adjustable top
 *   argus classleak &lt;pid&gt; --save=/tmp/cl.json  Persist snapshot
 *   argus classleak &lt;pid&gt; --diff=/tmp/cl.json  Diff vs saved baseline
 *   argus classleak &lt;pid&gt; --watch[=N]          Live mode (default 5s)
 * </pre>
 */
public final class ClassLeakCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_TOP = 10;
    private static final int DEFAULT_WATCH_SEC = 5;

    // ANSI escape sequences for watch mode
    private static final String CLEAR_SCREEN = "\033[2J\033[H";
    private static final String HIDE_CURSOR  = "\033[?25l";
    private static final String SHOW_CURSOR  = "\033[?25h";

    @Override
    public String name() { return "classleak"; }

    @Override
    public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.classleak.desc");
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

        if (!JcmdExecutor.isJcmdAvailable()) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        boolean useColor = config.color();
        int top = DEFAULT_TOP;
        Path saveTo = null;
        Path diffWith = null;
        int watchInterval = -1;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--top=")) {
                try { top = Math.max(1, Integer.parseInt(arg.substring(6))); }
                catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--save=")) {
                saveTo = Path.of(arg.substring("--save=".length()));
            } else if (arg.startsWith("--diff=")) {
                diffWith = Path.of(arg.substring("--diff=".length()));
            } else if (arg.equals("--watch")) {
                watchInterval = DEFAULT_WATCH_SEC;
            } else if (arg.startsWith("--watch=")) {
                try {
                    watchInterval = Integer.parseInt(arg.substring("--watch=".length()));
                    if (watchInterval < 1) watchInterval = DEFAULT_WATCH_SEC;
                } catch (NumberFormatException ignored) {
                    watchInterval = DEFAULT_WATCH_SEC;
                }
            }
        }

        // Watch mode short-circuits everything else
        if (watchInterval > 0) {
            runWatch(pid, top, watchInterval, useColor, messages);
            return;
        }

        List<ClassLoaderEntry> entries;
        try {
            entries = ClassLeakAnalyzer.collect(pid);
        } catch (RuntimeException e) {
            System.err.println("Failed to collect classloader stats: " + e.getMessage());
            return;
        }

        // Save snapshot
        if (saveTo != null) {
            try {
                ClassLeakSnapshot.save(saveTo, entries);
                System.out.println("Saved classleak snapshot to: " + saveTo.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("Failed to save snapshot: " + e.getMessage());
            }
        }

        // Diff mode
        if (diffWith != null) {
            try {
                ClassLeakSnapshot baseline = ClassLeakSnapshot.load(diffWith);
                long nowSec = System.currentTimeMillis() / 1000L;
                ClassLeakDiff diff = ClassLeakDiff.compute(baseline, nowSec, entries);
                printDiff(diff, pid, useColor, messages);
            } catch (IOException e) {
                System.err.println("Failed to load snapshot " + diffWith + ": " + e.getMessage());
            }
            return;
        }

        // Normal table view
        System.out.print(RichRenderer.brandedHeader(useColor, "classleak", messages.get("desc.classleak")));
        printTable(entries, pid, top, useColor, messages);
    }

    // ── Normal table ──────────────────────────────────────────────────────────

    private static void printTable(List<ClassLoaderEntry> entries, long pid,
                                   int top, boolean useColor, Messages messages) {
        List<ClassLoaderEntry> sorted = entries.stream()
                .sorted(Comparator.comparingLong(ClassLoaderEntry::classCount).reversed())
                .limit(top)
                .toList();

        long totalClasses = entries.stream().mapToLong(ClassLoaderEntry::classCount).sum();
        long totalChunk   = entries.stream().mapToLong(ClassLoaderEntry::chunkBytes).sum();

        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.classleak"),
                WIDTH, "pid:" + pid, "loaders:" + entries.size(), "top:" + top));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String hdr = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + RichRenderer.padRight("Type", 42)
                + RichRenderer.padLeft("Classes", 9)
                + RichRenderer.padLeft("ChunkSz", 10)
                + RichRenderer.padLeft("BlockSz", 10)
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(hdr, WIDTH));
        System.out.println(RichRenderer.boxLine("─".repeat(Math.min(73, WIDTH - 4)), WIDTH));

        String bold  = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String cyan  = AnsiStyle.style(useColor, AnsiStyle.CYAN);
        String dim   = AnsiStyle.style(useColor, AnsiStyle.DIM);

        for (ClassLoaderEntry e : sorted) {
            String typeName = humanType(e.type());
            String line = cyan + RichRenderer.padRight(RichRenderer.truncate(typeName, 40), 42) + reset
                    + RichRenderer.padLeft(String.valueOf(e.classCount()), 9)
                    + dim
                    + RichRenderer.padLeft(RichRenderer.formatBytes(e.chunkBytes()), 10)
                    + RichRenderer.padLeft(RichRenderer.formatBytes(e.blockBytes()), 10)
                    + reset;
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        String footer = bold + "Total: " + reset
                + entries.size() + " loaders, "
                + totalClasses + " classes, "
                + RichRenderer.formatBytes(totalChunk) + " metaspace";
        System.out.println(RichRenderer.boxLine(footer, WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, "top " + top + " by class count", WIDTH));
    }

    // ── Diff view ─────────────────────────────────────────────────────────────

    private static void printDiff(ClassLeakDiff diff, long pid,
                                  boolean useColor, Messages messages) {
        long elapsedSec = Math.max(1, diff.currentEpochSec() - diff.baseEpochSec());
        System.out.print(RichRenderer.brandedHeader(useColor, "classleak --diff",
                "Classloader growth vs. saved baseline"));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.classleak"),
                WIDTH, "pid:" + pid,
                "elapsed:" + RichRenderer.formatDuration(elapsedSec * 1000)));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String bold   = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset  = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim    = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String cyan   = AnsiStyle.style(useColor, AnsiStyle.CYAN);
        String yellow = AnsiStyle.style(useColor, AnsiStyle.YELLOW);
        String red    = AnsiStyle.style(useColor, AnsiStyle.RED, AnsiStyle.BOLD);
        String green  = AnsiStyle.style(useColor, AnsiStyle.GREEN);

        String hdr = bold
                + RichRenderer.padRight("Type", 38)
                + RichRenderer.padLeft("Base", 8)
                + RichRenderer.padLeft("Now", 8)
                + RichRenderer.padLeft("Δ", 8)
                + "  Status"
                + reset;
        System.out.println(RichRenderer.boxLine(hdr, WIDTH));
        System.out.println(RichRenderer.boxLine("─".repeat(Math.min(70, WIDTH - 4)), WIDTH));

        // Only show rows with a delta (positive or new)
        List<ClassLeakDiff.Row> changed = diff.rows().stream()
                .filter(r -> r.delta() != 0 || r.isNew())
                .sorted(Comparator.comparingLong(ClassLeakDiff.Row::delta).reversed())
                .toList();

        if (changed.isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    green + "  No classloader growth detected." + reset, WIDTH));
        } else {
            for (ClassLeakDiff.Row row : changed) {
                long delta = row.delta();
                String sev = switch (row.severity()) {
                    case CRITICAL -> red + "[CRITICAL]" + reset;
                    case WARNING  -> yellow + "[WARNING]" + reset;
                    case OK       -> green + "[OK]" + reset;
                };
                String deltaColor = delta > 0
                        ? (row.severity() == ClassLeakDiff.Severity.CRITICAL ? red : yellow)
                        : green;

                String typeName = humanType(row.type());
                String pctStr = row.isNew() ? "new"
                        : Double.isInfinite(row.deltaPct()) ? "new"
                        : String.format("%+.0f%%", row.deltaPct());

                String line = cyan + RichRenderer.padRight(RichRenderer.truncate(typeName, 36), 38) + reset
                        + dim + RichRenderer.padLeft(String.valueOf(row.baseCount()), 8) + reset
                        + RichRenderer.padLeft(String.valueOf(row.currentCount()), 8)
                        + deltaColor + RichRenderer.padLeft("+" + delta, 8) + reset
                        + "  " + sev + dim + " (" + pctStr + ")" + reset;
                System.out.println(RichRenderer.boxLine(line, WIDTH));
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor,
                changed.size() + " loaders changed, elapsed " + RichRenderer.formatDuration(elapsedSec * 1000),
                WIDTH));
    }

    // ── Watch mode ────────────────────────────────────────────────────────────

    private static void runWatch(long pid, int top, int intervalSec,
                                 boolean useColor, Messages messages) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        Thread shutdownHook = new Thread(() -> {
            System.out.print(SHOW_CURSOR);
            if (!isWindows) setRawMode(false);
            System.out.println();
        }, "argus-classleak-watch-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        System.out.print(HIDE_CURSOR);

        try {
            if (!isWindows) setRawMode(true);

            List<ClassLoaderEntry> previous = ClassLeakAnalyzer.collect(pid);

            StringBuilder frame = new StringBuilder();
            frame.append(CLEAR_SCREEN);
            renderWatchFrame(frame, pid, previous, previous, top, intervalSec, useColor);
            System.out.print(frame);
            System.out.flush();

            while (true) {
                long deadline = System.currentTimeMillis() + intervalSec * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    if (System.in.available() > 0) {
                        int key = System.in.read();
                        if (key == 'q' || key == 'Q' || key == 3) return;
                        if (key == 'r' || key == 'R') break;
                    }
                    Thread.sleep(100);
                }

                List<ClassLoaderEntry> current = ClassLeakAnalyzer.collect(pid);

                StringBuilder f = new StringBuilder();
                f.append(CLEAR_SCREEN);
                renderWatchFrame(f, pid, previous, current, top, intervalSec, useColor);
                System.out.print(f);
                System.out.flush();

                previous = current;
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

    private static void renderWatchFrame(StringBuilder sb, long pid,
                                         List<ClassLoaderEntry> previous,
                                         List<ClassLoaderEntry> current,
                                         int top, int intervalSec, boolean useColor) {
        String bold  = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim   = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String cyan  = AnsiStyle.style(useColor, AnsiStyle.CYAN);
        String yellow = AnsiStyle.style(useColor, AnsiStyle.YELLOW, AnsiStyle.BOLD);
        String green  = AnsiStyle.style(useColor, AnsiStyle.GREEN);

        sb.append(' ').append(bold).append(cyan).append("argus classleak --watch").append(reset)
          .append(dim).append("  pid:").append(pid)
          .append("  ").append(intervalSec).append("s refresh")
          .append("  q:quit  r:refresh").append(reset).append('\n');
        sb.append(' ').append("─".repeat(72)).append('\n');

        // Build address -> previous entry map
        java.util.Map<String, ClassLoaderEntry> prevMap = new java.util.LinkedHashMap<>();
        for (ClassLoaderEntry e : previous) prevMap.put(e.address(), e);

        // Sort current by class count descending, take top N
        List<ClassLoaderEntry> sorted = current.stream()
                .sorted(Comparator.comparingLong(ClassLoaderEntry::classCount).reversed())
                .limit(top)
                .toList();

        sb.append(bold)
          .append(RichRenderer.padRight("Type", 44))
          .append(RichRenderer.padLeft("Classes", 9))
          .append(RichRenderer.padLeft("Δ", 8))
          .append(reset).append('\n');
        sb.append(' ').append("─".repeat(63)).append('\n');

        for (ClassLoaderEntry e : sorted) {
            ClassLoaderEntry prev = prevMap.get(e.address());
            long delta = prev != null ? e.classCount() - prev.classCount() : 0;
            boolean changed = delta != 0;

            String nameStyle  = changed ? cyan : dim;
            String deltaStyle = delta > 0 ? yellow : (delta < 0 ? green : dim);
            String deltaStr   = delta == 0 ? "" : (delta > 0 ? "+" + delta : String.valueOf(delta));

            sb.append(nameStyle)
              .append(RichRenderer.padRight(RichRenderer.truncate(humanType(e.type()), 42), 44))
              .append(reset)
              .append(RichRenderer.padLeft(String.valueOf(e.classCount()), 9))
              .append(deltaStyle).append(RichRenderer.padLeft(deltaStr, 8)).append(reset)
              .append('\n');
        }

        long totalCurrent = current.stream().mapToLong(ClassLoaderEntry::classCount).sum();
        long totalPrev    = previous.stream().mapToLong(ClassLoaderEntry::classCount).sum();
        long totalDelta   = totalCurrent - totalPrev;
        String totalDeltaStr = totalDelta == 0 ? "" : (totalDelta > 0 ? "+" + totalDelta : String.valueOf(totalDelta));

        sb.append(' ').append("─".repeat(63)).append('\n');
        sb.append(bold)
          .append(RichRenderer.padRight("Total (" + current.size() + " loaders)", 44))
          .append(RichRenderer.padLeft(String.valueOf(totalCurrent), 9))
          .append(totalDelta > 0 ? yellow : green)
          .append(RichRenderer.padLeft(totalDeltaStr, 8))
          .append(reset).append('\n');
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Strips inner-class dollar signs and shortens package prefix for display. */
    private static String humanType(String type) {
        if (type == null || type.isBlank()) return "<unknown>";
        // Keep angle-bracket names like "<boot class loader>" as-is
        if (type.startsWith("<")) return type;
        String t = type.replace('$', '.');
        // Shorten: keep last 2 dot-separated segments
        String[] parts = t.split("\\.");
        if (parts.length > 3) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return t;
    }

    private static void setRawMode(boolean raw) {
        try {
            String cmd = raw ? "stty raw -echo < /dev/tty" : "stty cooked echo < /dev/tty";
            new ProcessBuilder("/bin/sh", "-c", cmd).inheritIO().start().waitFor();
        } catch (Exception ignored) {}
    }
}
