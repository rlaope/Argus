package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.GcUtilResult;
import io.argus.cli.provider.GcUtilProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;

/**
 * Shows GC generation utilization (similar to jstat -gcutil).
 * Supports one-shot and --watch mode for continuous monitoring.
 */
public final class GcUtilCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "gcutil";
    }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gcutil.desc");
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
        int watchInterval = 0; // 0 = one-shot

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--watch=")) {
                try { watchInterval = Integer.parseInt(arg.substring(8)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--watch") && i + 1 < args.length) {
                try { watchInterval = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            }
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        GcUtilProvider provider = registry.findGcUtilProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        if (watchInterval > 0) {
            runWatchMode(pid, provider, source, useColor, watchInterval, messages);
        } else {
            GcUtilResult result = provider.getGcUtil(pid);
            if (json) {
                printJson(result);
            } else {
                System.out.print(RichRenderer.brandedHeader(useColor, "gcutil", messages.get("desc.gcutil")));
                printTable(result, pid, source, useColor, messages, true);
            }
        }
    }

    private static void runWatchMode(long pid, GcUtilProvider provider, String source,
                                     boolean useColor, int intervalSec, Messages messages) {
        System.out.print("\033[?25l"); // hide cursor
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.print("\033[?25h\n")));

        System.out.print(RichRenderer.brandedHeader(useColor, "gcutil --watch",
                messages.get("desc.gcutil") + " (" + intervalSec + "s)"));

        boolean first = true;
        while (!Thread.currentThread().isInterrupted()) {
            GcUtilResult result = provider.getGcUtil(pid);
            printTable(result, pid, source, useColor, messages, first);
            first = false;

            try {
                if (System.in.available() > 0) {
                    int ch = System.in.read();
                    if (ch == 'q' || ch == 'Q') break;
                }
                Thread.sleep(intervalSec * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException ignored) {}
        }
        System.out.print("\033[?25h");
    }

    private static void printTable(GcUtilResult r, long pid, String source,
                                   boolean useColor, Messages messages, boolean showHeader) {
        if (showHeader) {
            System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gcutil"),
                    WIDTH, "pid:" + pid, "source:" + source));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            // Column headers
            String hdr = RichRenderer.padRight("S0", 8)
                    + RichRenderer.padRight("S1", 8)
                    + RichRenderer.padRight("Eden", 8)
                    + RichRenderer.padRight("Old", 8)
                    + RichRenderer.padRight("Meta", 8)
                    + RichRenderer.padRight("CCS", 8)
                    + RichRenderer.padRight("YGC", 7)
                    + RichRenderer.padRight("FGC", 7)
                    + "GCT";
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD) + hdr + AnsiStyle.style(useColor, AnsiStyle.RESET),
                    WIDTH));
            System.out.println(RichRenderer.boxLine("\u2500".repeat(Math.min(70, WIDTH - 4)), WIDTH));
        }

        // Data row with color-coded percentages
        String row = colorPct(useColor, r.s0(), 8)
                + colorPct(useColor, r.s1(), 8)
                + colorPct(useColor, r.eden(), 8)
                + colorPct(useColor, r.old(), 8)
                + colorPct(useColor, r.meta(), 8)
                + colorPct(useColor, r.ccs(), 8)
                + RichRenderer.padRight(String.valueOf(r.ygc()), 7)
                + RichRenderer.padRight(String.valueOf(r.fgc()), 7)
                + String.format("%.3f", r.gct());
        System.out.println(RichRenderer.boxLine(row, WIDTH));

        if (showHeader) {
            // Progress bars for visual representation
            System.out.println(RichRenderer.emptyLine(WIDTH));
            printSpaceBar(useColor, "S0", r.s0());
            printSpaceBar(useColor, "S1", r.s1());
            printSpaceBar(useColor, "Eden", r.eden());
            printSpaceBar(useColor, "Old", r.old());
            printSpaceBar(useColor, "Meta", r.meta());
            printSpaceBar(useColor, "CCS", r.ccs());

            System.out.println(RichRenderer.emptyLine(WIDTH));
            String summary = "YGC: " + r.ygc() + " (" + String.format("%.3fs", r.ygct()) + ")"
                    + "    FGC: " + r.fgc() + " (" + String.format("%.3fs", r.fgct()) + ")"
                    + "    Total: " + String.format("%.3fs", r.gct());
            System.out.println(RichRenderer.boxLine(summary, WIDTH));
            System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
        }
    }

    private static void printSpaceBar(boolean useColor, String label, double pct) {
        String bar = RichRenderer.progressBar(useColor, pct, 20);
        String line = RichRenderer.padRight("  " + label, 8) + bar + "  " + String.format("%5.1f%%", pct);
        System.out.println(RichRenderer.boxLine(line, WIDTH));
    }

    private static String colorPct(boolean useColor, double pct, int width) {
        String color = AnsiStyle.colorByThreshold(useColor, pct, 70, 90);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String formatted = String.format("%.1f%%", pct);
        return color + RichRenderer.padRight(formatted, width) + reset;
    }

    private static void printJson(GcUtilResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"s0\":").append(r.s0())
          .append(",\"s1\":").append(r.s1())
          .append(",\"eden\":").append(r.eden())
          .append(",\"old\":").append(r.old())
          .append(",\"meta\":").append(r.meta())
          .append(",\"ccs\":").append(r.ccs())
          .append(",\"ygc\":").append(r.ygc())
          .append(",\"ygct\":").append(r.ygct())
          .append(",\"fgc\":").append(r.fgc())
          .append(",\"fgct\":").append(r.fgct())
          .append(",\"gct\":").append(r.gct())
          .append('}');
        System.out.println(sb);
    }
}
