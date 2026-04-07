package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.JfrResult;
import io.argus.cli.provider.JfrProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Controls JFR Flight Recorder on a target JVM process.
 * Subcommands: start | stop | check | dump
 */
public final class JfrCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "jfr";
    }

    @Override public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.jfr.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            printHelp(config.color(), messages);
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[0]));
            return;
        }

        if (args.length < 2) {
            printHelp(config.color(), messages);
            return;
        }

        String subcommand = args[1].toLowerCase();
        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        int durationSec = 60;
        String filename = null;

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--duration=")) {
                try { durationSec = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--file=")) {
                filename = arg.substring(7);
            }
        }

        JfrProvider provider = registry.findJfrProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        JfrResult result;
        switch (subcommand) {
            case "start":
                result = provider.startRecording(pid, durationSec, filename);
                break;
            case "stop":
                result = provider.stopRecording(pid);
                break;
            case "check":
                result = provider.checkRecording(pid);
                break;
            case "dump":
                result = provider.dumpRecording(pid, filename);
                break;
            default:
                System.err.println(messages.get("error.jfr.unknown.subcommand", subcommand));
                printHelp(config.color(), messages);
                result = null;
                break;
        }

        if (result == null) return;

        if (json) {
            printJson(result);
        } else {
            boolean useColor = config.color();
            System.out.print(RichRenderer.brandedHeader(useColor, "jfr " + subcommand,
                    messages.get("desc.jfr")));
            printResult(result, pid, subcommand, useColor, messages);
        }
    }

    private static void printResult(JfrResult result, long pid, String subcommand,
                                    boolean useColor, Messages messages) {
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.jfr"),
                WIDTH, "pid:" + pid, "cmd:" + subcommand));

        String statusColor = "error".equalsIgnoreCase(result.status())
                ? AnsiStyle.style(useColor, AnsiStyle.RED)
                : AnsiStyle.style(useColor, AnsiStyle.GREEN);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);

        String statusLine = RichRenderer.padRight("Status:", 10)
                + statusColor + result.status() + reset;
        System.out.println(RichRenderer.boxLine(statusLine, WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(result.message(), WIDTH));

        if (result.recordingInfo() != null && !result.recordingInfo().isBlank()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            for (String line : result.recordingInfo().split("\n")) {
                if (!line.trim().isEmpty()) {
                    System.out.println(RichRenderer.boxLine(
                            AnsiStyle.style(useColor, AnsiStyle.DIM) + line.trim() + reset,
                            WIDTH));
                }
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printHelp(boolean useColor, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(useColor, "jfr", messages.get("desc.jfr")));
        System.out.println(RichRenderer.boxHeader(useColor, "Usage", WIDTH));
        System.out.println(RichRenderer.boxLine("argus jfr <pid> <subcommand> [options]", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Subcommands:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  start", 12) + "Start a JFR recording", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  stop", 12) + "Stop the active JFR recording", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  check", 12) + "Show current recording status", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  dump", 12) + "Dump recording to file", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Options (start/dump):"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --duration=N", 20) + "Recording duration in seconds (default: 60)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --file=NAME", 20) + "Output filename (default: argus-recording.jfr)", WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(JfrResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"").append(RichRenderer.escapeJson(result.status())).append('"');
        sb.append(",\"message\":\"").append(RichRenderer.escapeJson(result.message())).append('"');
        if (result.recordingInfo() != null) {
            sb.append(",\"recordingInfo\":\"")
              .append(RichRenderer.escapeJson(result.recordingInfo())).append('"');
        } else {
            sb.append(",\"recordingInfo\":null");
        }
        sb.append('}');
        System.out.println(sb);
    }
}
