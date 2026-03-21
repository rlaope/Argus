package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.HistoResult;
import io.argus.cli.provider.HistoProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.RichRenderer;

/**
 * Shows heap object histogram for a given PID.
 */
public final class HistoCommand implements Command {

    private static final int WIDTH = 60;
    private static final int DEFAULT_TOP = 20;

    @Override
    public String name() {
        return "histo";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.histo.desc");
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
            }
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        HistoProvider provider = registry.findHistoProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        HistoResult result = provider.getHistogram(pid, topN);

        if (json) {
            printJson(result);
            return;
        }

        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.histo"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String header = RichRenderer.padLeft("#", 4) + "  "
                + RichRenderer.padRight("Class", 28) + "  "
                + RichRenderer.padLeft("Count", 10) + "  "
                + RichRenderer.padLeft("Size", 8);
        System.out.println(RichRenderer.boxLine(header, WIDTH));

        String sep = "\u2500".repeat(4) + "  "
                + "\u2500".repeat(28) + "  "
                + "\u2500".repeat(10) + "  "
                + "\u2500".repeat(8);
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        for (HistoResult.Entry e : result.entries()) {
            String rank = RichRenderer.padLeft(String.valueOf(e.rank()), 4);
            String cls = RichRenderer.padRight(truncate(e.className(), 28), 28);
            String count = RichRenderer.padLeft(formatCount(e.instances()), 10);
            String size = RichRenderer.padLeft(RichRenderer.formatBytes(e.bytes()), 8);
            System.out.println(RichRenderer.boxLine(rank + "  " + cls + "  " + count + "  " + size, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        String summary = "Total: " + RichRenderer.formatNumber(result.totalInstances())
                + " objects \u00b7 " + RichRenderer.formatBytes(result.totalBytes());
        System.out.println(RichRenderer.boxLine(summary, WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(HistoResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalInstances\":").append(result.totalInstances())
          .append(",\"totalBytes\":").append(result.totalBytes())
          .append(",\"entries\":[");
        for (int i = 0; i < result.entries().size(); i++) {
            HistoResult.Entry e = result.entries().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"rank\":").append(e.rank())
              .append(",\"className\":\"").append(escape(e.className())).append('"')
              .append(",\"instances\":").append(e.instances())
              .append(",\"bytes\":").append(e.bytes())
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
