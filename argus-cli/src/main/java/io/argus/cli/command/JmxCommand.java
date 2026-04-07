package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Controls JMX Management Agent via jcmd ManagementAgent commands.
 * Subcommands: status (default), start, start-local, stop
 */
public final class JmxCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "jmx"; }

    @Override public CommandGroup group() { return CommandGroup.PROCESS; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.jmx.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) { System.err.println(messages.get("error.pid.required")); return; }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        boolean useColor = config.color();
        String subcommand = args.length > 1 ? args[1].toLowerCase() : "status";

        System.out.print(RichRenderer.brandedHeader(useColor, "jmx", messages.get("desc.jmx")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.jmx"), WIDTH, "pid:" + pid, "cmd:" + subcommand));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        try {
            String jcmdCommand = switch (subcommand) {
                case "start" -> buildStartCommand(args);
                case "start-local", "startlocal", "local" -> "ManagementAgent.start_local";
                case "stop" -> "ManagementAgent.stop";
                default -> "ManagementAgent.status";
            };

            String output = JcmdExecutor.execute(pid, jcmdCommand);

            if (subcommand.equals("status")) {
                renderStatus(output, useColor, messages);
            } else {
                // Show result
                String icon = subcommand.equals("stop") ? "\u25a0" : "\u25b6";
                String color = subcommand.equals("stop") ? AnsiStyle.style(useColor, AnsiStyle.YELLOW) : AnsiStyle.style(useColor, AnsiStyle.GREEN);
                String status = color + icon + " " + messages.get("jmx." + subcommand.replace("-", ""))
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(status, WIDTH));

                if (!output.isBlank()) {
                    System.out.println(RichRenderer.emptyLine(WIDTH));
                    for (String line : output.split("\n")) {
                        if (!line.trim().isEmpty()) {
                            System.out.println(RichRenderer.boxLine(line.trim(), WIDTH));
                        }
                    }
                }

                // Security warning for remote start
                if (subcommand.equals("start")) {
                    System.out.println(RichRenderer.emptyLine(WIDTH));
                    String warn = AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0 "
                            + messages.get("jmx.warn.remote")
                            + AnsiStyle.style(useColor, AnsiStyle.RESET);
                    System.out.println(RichRenderer.boxLine(warn, WIDTH));
                }
            }
        } catch (RuntimeException e) {
            String fail = AnsiStyle.style(useColor, AnsiStyle.RED) + "\u2718 " + e.getMessage()
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(fail, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private void renderStatus(String output, boolean useColor, Messages messages) {
        boolean enabled = false;
        String connectorUrl = "";

        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains("enabled") || trimmed.contains("Agent") && trimmed.contains("running")) {
                enabled = true;
            }
            if (trimmed.contains("://")) {
                connectorUrl = trimmed;
            }
        }

        if (enabled) {
            String on = AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u25cf "
                    + messages.get("jmx.enabled") + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(on, WIDTH));
            if (!connectorUrl.isEmpty()) {
                System.out.println(RichRenderer.boxLine("  URL: " + connectorUrl, WIDTH));
            }
        } else {
            String off = AnsiStyle.style(useColor, AnsiStyle.DIM) + "\u25cb "
                    + messages.get("jmx.disabled") + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(off, WIDTH));
        }

        // Show raw output
        if (!output.isBlank()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            for (String line : output.split("\n")) {
                if (!line.trim().isEmpty() && !line.trim().startsWith(String.valueOf(0))) {
                    System.out.println(RichRenderer.boxLine(line.trim(), WIDTH));
                }
            }
        }
    }

    private String buildStartCommand(String[] args) {
        StringBuilder cmd = new StringBuilder("ManagementAgent.start");
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--port=")) cmd.append(" jmxremote.port=").append(arg.substring(7));
            else if (arg.startsWith("--rmi-port=")) cmd.append(" jmxremote.rmi.port=").append(arg.substring(11));
            else if (arg.equals("--no-auth")) cmd.append(" jmxremote.authenticate=false");
            else if (arg.equals("--no-ssl")) cmd.append(" jmxremote.ssl=false");
            else cmd.append(' ').append(arg);
        }
        return cmd.toString();
    }
}
