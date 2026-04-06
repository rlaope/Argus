package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

/**
 * Displays VM internal event log (safepoints, deoptimizations, GC phases).
 * Uses jcmd VM.events.
 */
public final class EventsCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "events";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.events.desc");
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

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        if (!JcmdExecutor.isJcmdAvailable()) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        String output;
        try {
            output = JcmdExecutor.execute(pid, "VM.events");
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            return;
        }

        if (json) {
            System.out.println("{\"events\":\"" + RichRenderer.escapeJson(output) + "\"}");
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "events", messages.get("desc.events")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.events"),
                WIDTH, "pid:" + pid));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        int eventCount = 0;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Section headers like "Events (250 events):"
            if (trimmed.endsWith("events):") || trimmed.endsWith("event):")) {
                if (eventCount > 0) {
                    System.out.println(RichRenderer.emptyLine(WIDTH));
                }
                System.out.println(RichRenderer.boxLine(
                        AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.CYAN) + trimmed
                                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
                System.out.println(RichRenderer.boxSeparator(WIDTH));
                eventCount++;
                continue;
            }

            // Color-code event types
            String displayLine = trimmed;
            if (trimmed.contains("Safepoint") || trimmed.contains("safepoint")) {
                displayLine = AnsiStyle.style(useColor, AnsiStyle.YELLOW) + trimmed
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
            } else if (trimmed.contains("Deoptimization") || trimmed.contains("deopt")) {
                displayLine = AnsiStyle.style(useColor, AnsiStyle.RED) + trimmed
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
            } else if (trimmed.contains("GC") || trimmed.contains("gc")) {
                displayLine = AnsiStyle.style(useColor, AnsiStyle.GREEN) + trimmed
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
            }

            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.truncate(displayLine, WIDTH - 8), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }
}
