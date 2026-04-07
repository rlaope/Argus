package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.FinalizerResult;
import io.argus.cli.provider.FinalizerProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows finalizer queue status.
 */
public final class FinalizerCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "finalizer"; }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.finalizer.desc");
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
        FinalizerProvider provider = registry.findFinalizerProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        FinalizerResult result = provider.getFinalizerInfo(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "finalizer", messages.get("desc.finalizer")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.finalizer"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Pending count
        if (result.pendingCount() == 0) {
            String ok = AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u2714 "
                    + messages.get("finalizer.none")
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(ok, WIDTH));
        } else {
            String warn = AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0 "
                    + messages.get("finalizer.pending", result.pendingCount())
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(warn, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Finalizer thread state
        String stateColor = "WAITING".equals(result.finalizerThreadState())
                ? AnsiStyle.style(useColor, AnsiStyle.GREEN)
                : AnsiStyle.style(useColor, AnsiStyle.YELLOW);
        String stateLine = messages.get("finalizer.thread") + "  "
                + stateColor + result.finalizerThreadState()
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(stateLine, WIDTH));

        // Warning for high pending count
        if (result.pendingCount() > 100) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            String warn = AnsiStyle.style(useColor, AnsiStyle.RED) + "\u26a0 "
                    + messages.get("finalizer.warn")
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(warn, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(FinalizerResult result) {
        System.out.println("{\"pendingCount\":" + result.pendingCount()
                + ",\"finalizerThreadState\":\"" + RichRenderer.escapeJson(result.finalizerThreadState()) + "\"}");
    }
}
