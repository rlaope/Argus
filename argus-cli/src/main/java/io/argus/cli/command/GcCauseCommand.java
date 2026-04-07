package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.GcCauseResult;
import io.argus.cli.provider.GcCauseProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows GC cause information alongside gcutil stats.
 */
public final class GcCauseCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int BAR_WIDTH = 20;

    @Override
    public String name() { return "gccause"; }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gccause.desc");
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
        GcCauseProvider provider = registry.findGcCauseProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        GcCauseResult result = provider.getGcCause(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "gccause", messages.get("desc.gccause")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gccause"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // GC Cause
        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);

        String lgccColor = "No GC".equals(result.lastGcCause()) ? AnsiStyle.style(useColor, AnsiStyle.GREEN) : AnsiStyle.style(useColor, AnsiStyle.YELLOW);
        String lgccLine = bold + messages.get("gccause.last") + reset + "     " + lgccColor + result.lastGcCause() + reset;
        System.out.println(RichRenderer.boxLine(lgccLine, WIDTH));

        String gccColor = "No GC".equals(result.currentGcCause()) ? AnsiStyle.style(useColor, AnsiStyle.DIM) : AnsiStyle.style(useColor, AnsiStyle.RED);
        String gccLine = bold + messages.get("gccause.current") + reset + "  " + gccColor + result.currentGcCause() + reset;
        System.out.println(RichRenderer.boxLine(gccLine, WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Utilization bars
        printBar(useColor, "Eden", result.eden(), 80, 95);
        printBar(useColor, "Old ", result.old(), 80, 95);
        printBar(useColor, "Meta", result.meta(), 90, 97);

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // GC counts
        String gcLine = "YGC: " + result.ygc() + " (" + String.format("%.3fs", result.ygct()) + ")    "
                + "FGC: " + result.fgc() + " (" + String.format("%.3fs", result.fgct()) + ")    "
                + "GCT: " + String.format("%.3fs", result.gct());
        System.out.println(RichRenderer.boxLine(gcLine, WIDTH));

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printBar(boolean useColor, String label, double pct, double warn, double crit) {
        String bar = RichRenderer.progressBar(useColor, pct, BAR_WIDTH);
        String line = "  " + label + "  " + bar + "  " + String.format("%5.1f%%", pct);
        System.out.println(RichRenderer.boxLine(line, WIDTH));
    }

    private static void printJson(GcCauseResult r) {
        System.out.println("{\"s0\":" + r.s0() + ",\"s1\":" + r.s1()
                + ",\"eden\":" + r.eden() + ",\"old\":" + r.old()
                + ",\"meta\":" + r.meta() + ",\"ccs\":" + r.ccs()
                + ",\"ygc\":" + r.ygc() + ",\"ygct\":" + r.ygct()
                + ",\"fgc\":" + r.fgc() + ",\"fgct\":" + r.fgct()
                + ",\"gct\":" + r.gct()
                + ",\"lastGcCause\":\"" + RichRenderer.escapeJson(r.lastGcCause()) + "\""
                + ",\"currentGcCause\":\"" + RichRenderer.escapeJson(r.currentGcCause()) + "\"}");
    }
}
