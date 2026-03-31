package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.ClassStatResult;
import io.argus.cli.provider.ClassStatProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

/**
 * Shows class loading statistics: loaded/unloaded counts, bytes, and time.
 */
public final class ClassStatCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "classstat"; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.classstat.desc");
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
        ClassStatProvider provider = registry.findClassStatProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        ClassStatResult result = provider.getClassStats(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "classstat", messages.get("desc.classstat")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.classstat"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String green = AnsiStyle.style(useColor, AnsiStyle.GREEN);
        String yellow = AnsiStyle.style(useColor, AnsiStyle.YELLOW);

        // Loaded
        String loadedLine = bold + messages.get("classstat.loaded") + reset + "    "
                + green + RichRenderer.formatNumber(result.loaded()) + reset
                + "  (" + String.format("%.1f", result.loadedBytes()) + " KB)";
        System.out.println(RichRenderer.boxLine(loadedLine, WIDTH));

        // Unloaded
        String unloadedLine = bold + messages.get("classstat.unloaded") + reset + "  "
                + yellow + RichRenderer.formatNumber(result.unloaded()) + reset
                + "  (" + String.format("%.1f", result.unloadedBytes()) + " KB)";
        System.out.println(RichRenderer.boxLine(unloadedLine, WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Net loaded
        long net = result.loaded() - result.unloaded();
        String netLine = bold + messages.get("classstat.net") + reset + "       "
                + RichRenderer.formatNumber(net);
        System.out.println(RichRenderer.boxLine(netLine, WIDTH));

        // Time
        String timeLine = bold + messages.get("classstat.time") + reset + "      "
                + String.format("%.3fs", result.timeMs());
        System.out.println(RichRenderer.boxLine(timeLine, WIDTH));

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(ClassStatResult r) {
        System.out.println("{\"loaded\":" + r.loaded()
                + ",\"loadedBytes\":" + r.loadedBytes()
                + ",\"unloaded\":" + r.unloaded()
                + ",\"unloadedBytes\":" + r.unloadedBytes()
                + ",\"timeMs\":" + r.timeMs() + "}");
    }
}
