package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.MetaspaceResult;
import io.argus.cli.provider.MetaspaceProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows detailed metaspace statistics.
 */
public final class MetaspaceCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int BAR_WIDTH = 20;

    @Override
    public String name() { return "metaspace"; }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.metaspace.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) { System.err.println(messages.get("error.pid.required")); return; }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--source=")) sourceOverride = args[i].substring(9);
            else if (args[i].equals("--format=json")) json = true;
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        MetaspaceProvider provider = registry.findMetaspaceProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        MetaspaceResult result = provider.getMetaspaceInfo(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "metaspace", messages.get("desc.metaspace")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.metaspace"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Total
        double usedPct = result.totalCommitted() > 0 ? (result.totalUsed() * 100.0) / result.totalCommitted() : 0;
        String bar = RichRenderer.progressBar(useColor, usedPct, BAR_WIDTH);
        String totalLine = messages.get("label.used") + "  " + bar + "  "
                + RichRenderer.formatBytes(result.totalUsed()) + " / " + RichRenderer.formatBytes(result.totalCommitted())
                + "  (" + String.format("%.0f%%", usedPct) + ")";
        System.out.println(RichRenderer.boxLine(totalLine, WIDTH));

        String reservedLine = "  " + messages.get("metaspace.reserved") + ": " + RichRenderer.formatBytes(result.totalReserved());
        System.out.println(RichRenderer.boxLine(reservedLine, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Per-space breakdown
        if (!result.spaces().isEmpty()) {
            String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
            String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);

            String header = bold + RichRenderer.padRight(messages.get("metaspace.space"), 16)
                    + RichRenderer.padLeft(messages.get("label.used"), 10)
                    + RichRenderer.padLeft(messages.get("label.committed"), 12)
                    + RichRenderer.padLeft(messages.get("metaspace.reserved"), 12) + reset;
            System.out.println(RichRenderer.boxLine(header, WIDTH));

            String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);
            System.out.println(RichRenderer.boxLine(dim + "\u2500".repeat(50) + reset, WIDTH));

            for (MetaspaceResult.SpaceInfo sp : result.spaces()) {
                String spLine = RichRenderer.padRight(sp.name(), 16)
                        + RichRenderer.padLeft(RichRenderer.formatBytes(sp.used()), 10)
                        + RichRenderer.padLeft(RichRenderer.formatBytes(sp.committed()), 12)
                        + RichRenderer.padLeft(RichRenderer.formatBytes(sp.reserved()), 12);
                System.out.println(RichRenderer.boxLine(spLine, WIDTH));
            }
        }

        // Warning
        if (usedPct > 90) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            String warn = AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0 "
                    + messages.get("metaspace.warn")
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(warn, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(MetaspaceResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalReserved\":").append(r.totalReserved());
        sb.append(",\"totalCommitted\":").append(r.totalCommitted());
        sb.append(",\"totalUsed\":").append(r.totalUsed());
        sb.append(",\"spaces\":[");
        for (int i = 0; i < r.spaces().size(); i++) {
            MetaspaceResult.SpaceInfo sp = r.spaces().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(sp.name()).append('"');
            sb.append(",\"reserved\":").append(sp.reserved());
            sb.append(",\"committed\":").append(sp.committed());
            sb.append(",\"used\":").append(sp.used()).append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
