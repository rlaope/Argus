package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.GcResult;
import io.argus.cli.provider.GcProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.RichRenderer;

/**
 * Shows GC statistics for a given PID.
 */
public final class GcCommand implements Command {

    private static final int WIDTH = 60;
    private static final int BAR_WIDTH = 16;

    @Override
    public String name() {
        return "gc";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gc.desc");
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

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            }
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        GcProvider provider = registry.findGcProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        GcResult result = provider.getGcInfo(pid);

        if (json) {
            printJson(result);
            return;
        }

        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gc"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Heap usage bar
        double heapPct = result.heapCommitted() > 0
                ? (result.heapUsed() * 100.0) / result.heapCommitted()
                : 0.0;
        String heapBar = RichRenderer.progressBar(useColor, heapPct, BAR_WIDTH);
        String heapLine = "Heap     " + heapBar + "  "
                + RichRenderer.formatBytes(result.heapUsed()) + " / "
                + RichRenderer.formatBytes(result.heapCommitted())
                + "  (" + String.format("%.0f%%", heapPct) + ")";
        System.out.println(RichRenderer.boxLine(heapLine, WIDTH));

        // Overhead bar
        String overheadBar = RichRenderer.progressBar(useColor, result.overheadPercent(), BAR_WIDTH);
        String overheadLine = "Overhead " + overheadBar + "  "
                + String.format("%.1f%%", result.overheadPercent());
        System.out.println(RichRenderer.boxLine(overheadLine, WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        String eventsLine = "Total Events: " + RichRenderer.formatNumber(result.totalEvents())
                + "    Pause Time: " + String.format("%.0fms", result.totalPauseMs());
        System.out.println(RichRenderer.boxLine(eventsLine, WIDTH));

        if (result.lastCause() != null && !result.lastCause().isEmpty()) {
            System.out.println(RichRenderer.boxLine("Last Cause: " + result.lastCause(), WIDTH));
        }

        if (!result.collectors().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine("Collectors:", WIDTH));
            for (GcResult.CollectorInfo c : result.collectors()) {
                String line = "  " + RichRenderer.padRight(c.name(), 20)
                        + RichRenderer.padLeft(RichRenderer.formatNumber(c.count()), 8)
                        + " events    "
                        + String.format("%.0fms", c.totalMs());
                System.out.println(RichRenderer.boxLine(line, WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(GcResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalEvents\":").append(result.totalEvents())
          .append(",\"totalPauseMs\":").append(result.totalPauseMs())
          .append(",\"overheadPercent\":").append(result.overheadPercent())
          .append(",\"lastCause\":\"").append(escape(result.lastCause())).append('"')
          .append(",\"heapUsed\":").append(result.heapUsed())
          .append(",\"heapCommitted\":").append(result.heapCommitted())
          .append(",\"collectors\":[");
        for (int i = 0; i < result.collectors().size(); i++) {
            GcResult.CollectorInfo c = result.collectors().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(escape(c.name())).append('"')
              .append(",\"count\":").append(c.count())
              .append(",\"totalMs\":").append(c.totalMs())
              .append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
