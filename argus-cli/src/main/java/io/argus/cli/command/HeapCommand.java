package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.GcResult;
import io.argus.cli.model.HeapResult;
import io.argus.cli.provider.GcProvider;
import io.argus.cli.provider.HeapProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.Map;

/**
 * Shows heap memory usage for a given PID.
 */
public final class HeapCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int BAR_WIDTH = 16;

    @Override
    public String name() {
        return "heap";
    }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.heap.desc");
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
        HeapProvider provider = registry.findHeapProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        HeapResult result = provider.getHeapInfo(pid);

        if (json) {
            printJson(result);
            return;
        }

        // Optionally fetch GC data for enriched output
        GcResult gcResult = null;
        GcProvider gcProvider = registry.findGcProvider(pid, sourceOverride);
        if (gcProvider != null) {
            try { gcResult = gcProvider.getGcInfo(pid); } catch (Exception ignored) {}
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "heap", messages.get("desc.heap")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.heap"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // --- Total heap section ---
        long usedBytes = result.used();
        long committedBytes = result.committed();
        long maxBytes = result.max() > 0 ? result.max() : committedBytes;
        long freeBytes = committedBytes - usedBytes;
        double totalPct = committedBytes > 0 ? (usedBytes * 100.0) / committedBytes : 0.0;

        String totalBar = RichRenderer.progressBar(useColor, totalPct, BAR_WIDTH);
        String totalLine = messages.get("label.used") + "     " + totalBar + "  "
                + RichRenderer.formatBytes(usedBytes) + " / "
                + RichRenderer.formatBytes(committedBytes)
                + "  (" + String.format("%.0f%%", totalPct) + ")";
        System.out.println(RichRenderer.boxLine(totalLine, WIDTH));

        // Numeric details
        System.out.println(RichRenderer.emptyLine(WIDTH));
        String col1 = RichRenderer.padRight("  " + messages.get("label.used") + ":", 16)
                + RichRenderer.padLeft(RichRenderer.formatBytes(usedBytes), 10)
                + RichRenderer.padLeft(String.format("(%,d bytes)", usedBytes), 24);
        System.out.println(RichRenderer.boxLine(col1, WIDTH));

        String col2 = RichRenderer.padRight("  " + messages.get("label.committed") + ":", 16)
                + RichRenderer.padLeft(RichRenderer.formatBytes(committedBytes), 10)
                + RichRenderer.padLeft(String.format("(%,d bytes)", committedBytes), 24);
        System.out.println(RichRenderer.boxLine(col2, WIDTH));

        if (result.max() > 0) {
            double maxPct = (usedBytes * 100.0) / maxBytes;
            String col3 = RichRenderer.padRight("  " + messages.get("label.max") + ":", 16)
                    + RichRenderer.padLeft(RichRenderer.formatBytes(maxBytes), 10)
                    + RichRenderer.padLeft(String.format("(%.1f%% used)", maxPct), 24);
            System.out.println(RichRenderer.boxLine(col3, WIDTH));
        }

        String freeColor = AnsiStyle.colorByThreshold(useColor, totalPct, 70, 90);
        String col4 = RichRenderer.padRight("  " + messages.get("label.free") + ":", 16)
                + freeColor
                + RichRenderer.padLeft(RichRenderer.formatBytes(freeBytes), 10)
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(col4, WIDTH));

        // --- GC summary (if available) ---
        if (gcResult != null && gcResult.totalEvents() > 0) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD) + "  GC"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            String gcLine1 = "  " + messages.get("label.events") + ": "
                    + RichRenderer.formatNumber(gcResult.totalEvents())
                    + "    " + messages.get("label.pause") + ": "
                    + String.format("%.0fms", gcResult.totalPauseMs());
            System.out.println(RichRenderer.boxLine(gcLine1, WIDTH));

            if (gcResult.overheadPercent() > 0) {
                String ohColor = AnsiStyle.colorByThreshold(useColor, gcResult.overheadPercent(), 2, 5);
                String gcLine2 = "  " + messages.get("label.overhead") + ": "
                        + ohColor + String.format("%.2f%%", gcResult.overheadPercent())
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(gcLine2, WIDTH));
            }

            if (gcResult.lastCause() != null && !gcResult.lastCause().isEmpty()) {
                System.out.println(RichRenderer.boxLine(
                        "  " + messages.get("label.cause") + ": " + gcResult.lastCause(), WIDTH));
            }
        }

        // --- Spaces breakdown ---
        if (!result.spaces().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD)
                    + "  " + messages.get("label.spaces")
                    + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

            for (Map.Entry<String, HeapResult.SpaceInfo> entry : result.spaces().entrySet()) {
                HeapResult.SpaceInfo space = entry.getValue();
                double spacePct = space.committed() > 0
                        ? (space.used() * 100.0) / space.committed()
                        : 0.0;

                boolean isMetaspace = entry.getKey().toLowerCase().contains("meta");
                String warn = "";
                if (isMetaspace && spacePct >= 85.0) {
                    warn = " " + AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET);
                } else if (spacePct >= 90.0) {
                    warn = " " + AnsiStyle.style(useColor, AnsiStyle.RED) + "\u26a0"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET);
                }

                String spaceBar = RichRenderer.progressBar(useColor, spacePct, 10);
                String label = RichRenderer.padRight("  " + space.name(), 16);
                String usage = spaceBar + "  "
                        + RichRenderer.padLeft(RichRenderer.formatBytes(space.used()), 6) + " / "
                        + RichRenderer.padLeft(RichRenderer.formatBytes(space.committed()), 6)
                        + "  " + String.format("(%3.0f%%)", spacePct) + warn;
                System.out.println(RichRenderer.boxLine(label + usage, WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(HeapResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"used\":").append(result.used())
          .append(",\"committed\":").append(result.committed())
          .append(",\"max\":").append(result.max())
          .append(",\"spaces\":{");
        boolean first = true;
        for (Map.Entry<String, HeapResult.SpaceInfo> e : result.spaces().entrySet()) {
            if (!first) sb.append(',');
            HeapResult.SpaceInfo s = e.getValue();
            sb.append('"').append(RichRenderer.escapeJson(e.getKey())).append("\":")
              .append("{\"name\":\"").append(RichRenderer.escapeJson(s.name())).append('"')
              .append(",\"used\":").append(s.used())
              .append(",\"committed\":").append(s.committed())
              .append(",\"max\":").append(s.max())
              .append('}');
            first = false;
        }
        sb.append("}}");
        System.out.println(sb);
    }


}
