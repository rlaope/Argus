package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

/**
 * Shows the current JIT compilation queue.
 * Uses jcmd Compiler.queue.
 */
public final class CompilerQueueCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "compilerqueue";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.compilerqueue.desc");
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
            output = JcmdExecutor.execute(pid, "Compiler.queue");
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            return;
        }

        if (json) {
            // Parse queue entries into JSON
            StringBuilder sb = new StringBuilder();
            sb.append("{\"queue\":[");
            boolean first = true;
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("Contents") || trimmed.startsWith("---")) continue;
                if (!first) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(trimmed)).append('"');
                first = false;
            }
            sb.append("]}");
            System.out.println(sb);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "compilerqueue",
                messages.get("desc.compilerqueue")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.compilerqueue"),
                WIDTH, "pid:" + pid));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        int queueSize = 0;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Section headers
            if (trimmed.startsWith("Contents of") || trimmed.startsWith("---")) {
                System.out.println(RichRenderer.boxLine(
                        AnsiStyle.style(useColor, AnsiStyle.BOLD) + trimmed
                                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
                continue;
            }

            // Queue entries - color by compilation tier
            String displayLine = trimmed;
            if (trimmed.contains("C2")) {
                displayLine = AnsiStyle.style(useColor, AnsiStyle.RED) + trimmed
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
            } else if (trimmed.contains("C1")) {
                displayLine = AnsiStyle.style(useColor, AnsiStyle.YELLOW) + trimmed
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
            }

            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.truncate(displayLine, WIDTH - 8), WIDTH));
            queueSize++;
        }

        if (queueSize == 0) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u2714 "
                            + messages.get("compilerqueue.empty")
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor,
                queueSize > 0 ? queueSize + " pending" : null, WIDTH));
    }
}
