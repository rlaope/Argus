package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.json.JsonOutput;
import io.argus.cli.model.HistoResult;
import io.argus.cli.provider.HistoProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows heap object histogram for a given PID.
 */
public final class HistoCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_TOP = 20;

    @Override
    public String name() {
        return "histo";
    }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.histo.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        long pid = CommandUtils.parsePidOrExit(args, messages);

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
        HistoProvider provider = Providers.require(registry.find(HistoProvider.class, pid, sourceOverride), pid, messages);

        HistoResult result = provider.getHistogram(pid, topN);

        if (json) {
            JsonOutput.println(result);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "histo", messages.get("desc.histo")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.histo"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        int classWidth = WIDTH - 32;
        String header = RichRenderer.padLeft("#", 4) + "  "
                + RichRenderer.padRight("Class", classWidth) + "  "
                + RichRenderer.padLeft("Count", 10) + "  "
                + RichRenderer.padLeft("Size", 8);
        System.out.println(RichRenderer.boxLine(header, WIDTH));

        String sep = "\u2500".repeat(4) + "  "
                + "\u2500".repeat(classWidth) + "  "
                + "\u2500".repeat(10) + "  "
                + "\u2500".repeat(8);
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        for (HistoResult.Entry e : result.entries()) {
            String rank = RichRenderer.padLeft(String.valueOf(e.rank()), 4);
            String cls = RichRenderer.padRight(RichRenderer.truncate(RichRenderer.humanClassName(e.className()), classWidth), classWidth);
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

    private static String formatCount(long n) {
        if (n < 1_000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%,.0f", (double) n);
        return RichRenderer.formatNumber(n);
    }


}
