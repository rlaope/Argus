package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.GcNewResult;
import io.argus.cli.provider.GcNewProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows young generation GC detail: survivor spaces, tenuring threshold, eden.
 */
public final class GcNewCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int BAR_WIDTH = 16;

    @Override
    public String name() { return "gcnew"; }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gcnew.desc");
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
        GcNewProvider provider = registry.findGcNewProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        GcNewResult result = provider.getGcNew(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "gcnew", messages.get("desc.gcnew")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gcnew"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Eden
        double edenPct = result.ec() > 0 ? (result.eu() / result.ec()) * 100 : 0;
        String edenBar = RichRenderer.progressBar(useColor, edenPct, BAR_WIDTH);
        System.out.println(RichRenderer.boxLine("  Eden  " + edenBar + "  "
                + RichRenderer.formatKB((long) result.eu()) + " / " + RichRenderer.formatKB((long) result.ec())
                + "  (" + String.format("%.0f%%", edenPct) + ")", WIDTH));

        // S0
        double s0Pct = result.s0c() > 0 ? (result.s0u() / result.s0c()) * 100 : 0;
        String s0Bar = RichRenderer.progressBar(useColor, s0Pct, BAR_WIDTH);
        System.out.println(RichRenderer.boxLine("  S0    " + s0Bar + "  "
                + RichRenderer.formatKB((long) result.s0u()) + " / " + RichRenderer.formatKB((long) result.s0c())
                + "  (" + String.format("%.0f%%", s0Pct) + ")", WIDTH));

        // S1
        double s1Pct = result.s1c() > 0 ? (result.s1u() / result.s1c()) * 100 : 0;
        String s1Bar = RichRenderer.progressBar(useColor, s1Pct, BAR_WIDTH);
        System.out.println(RichRenderer.boxLine("  S1    " + s1Bar + "  "
                + RichRenderer.formatKB((long) result.s1u()) + " / " + RichRenderer.formatKB((long) result.s1c())
                + "  (" + String.format("%.0f%%", s1Pct) + ")", WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Tenuring
        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);

        String ttLine = bold + messages.get("gcnew.tenuring") + reset + "  "
                + result.tt() + " / " + result.mtt();
        System.out.println(RichRenderer.boxLine(ttLine, WIDTH));

        String dssLine = bold + messages.get("gcnew.dss") + reset + "        "
                + RichRenderer.formatKB((long) result.dss());
        System.out.println(RichRenderer.boxLine(dssLine, WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // YGC
        String gcLine = "YGC: " + result.ygc() + "  (" + String.format("%.3fs", result.ygct()) + ")";
        System.out.println(RichRenderer.boxLine(gcLine, WIDTH));

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(GcNewResult r) {
        System.out.println("{\"s0c\":" + r.s0c() + ",\"s1c\":" + r.s1c()
                + ",\"s0u\":" + r.s0u() + ",\"s1u\":" + r.s1u()
                + ",\"tt\":" + r.tt() + ",\"mtt\":" + r.mtt()
                + ",\"dss\":" + r.dss() + ",\"ec\":" + r.ec() + ",\"eu\":" + r.eu()
                + ",\"ygc\":" + r.ygc() + ",\"ygct\":" + r.ygct() + "}");
    }
}
