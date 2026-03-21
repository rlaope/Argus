package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.HeapResult;
import io.argus.cli.provider.HeapProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.util.Map;

/**
 * Shows heap memory usage for a given PID.
 */
public final class HeapCommand implements Command {

    private static final int WIDTH = 60;
    private static final int BAR_WIDTH = 16;

    @Override
    public String name() {
        return "heap";
    }

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

        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.heap"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Total heap bar
        double totalPct = result.committed() > 0
                ? (result.used() * 100.0) / result.committed()
                : 0.0;
        String totalBar = RichRenderer.progressBar(useColor, totalPct, BAR_WIDTH);
        String totalLine = "Total    " + totalBar + "  "
                + RichRenderer.formatBytes(result.used()) + " / "
                + RichRenderer.formatBytes(result.committed())
                + "  (" + String.format("%.0f%%", totalPct) + ")";
        System.out.println(RichRenderer.boxLine(totalLine, WIDTH));

        if (!result.spaces().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine("Spaces:", WIDTH));

            for (Map.Entry<String, HeapResult.SpaceInfo> entry : result.spaces().entrySet()) {
                HeapResult.SpaceInfo space = entry.getValue();
                double spacePct = space.committed() > 0
                        ? (space.used() * 100.0) / space.committed()
                        : 0.0;

                // Warn if metaspace is above 85%
                boolean isMetaspace = entry.getKey().toLowerCase().contains("meta");
                String warn = (isMetaspace && spacePct >= 85.0)
                        ? "  " + AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0"
                          + AnsiStyle.style(useColor, AnsiStyle.RESET)
                        : "";

                String label = RichRenderer.padRight("  " + space.name(), 14);
                String usage = RichRenderer.formatBytes(space.used()) + " / "
                        + RichRenderer.formatBytes(space.committed())
                        + "  (" + String.format("%.0f%%", spacePct) + ")";
                System.out.println(RichRenderer.boxLine(label + "  " + usage + warn, WIDTH));
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
            sb.append('"').append(escape(e.getKey())).append("\":")
              .append("{\"name\":\"").append(escape(s.name())).append('"')
              .append(",\"used\":").append(s.used())
              .append(",\"committed\":").append(s.committed())
              .append(",\"max\":").append(s.max())
              .append('}');
            first = false;
        }
        sb.append("}}");
        System.out.println(sb);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
