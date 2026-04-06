package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.LoggerResult;
import io.argus.cli.model.LoggerResult.LoggerInfo;
import io.argus.cli.provider.LoggerProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

/**
 * View and change log levels at runtime.
 * Uses JVM unified logging (VM.log) and java.util.logging.
 *
 * Usage:
 *   argus logger <pid>                     — list all loggers
 *   argus logger <pid> --name=gc* --level=debug  — set log level
 *   argus logger <pid> --filter=gc          — filter by name
 */
public final class LoggerCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "logger";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.logger.desc");
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

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        String loggerName = null;
        String level = null;
        String filter = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--name=")) {
                loggerName = arg.substring(7);
            } else if (arg.startsWith("--level=")) {
                level = arg.substring(8);
            } else if (arg.startsWith("--filter=")) {
                filter = arg.substring(9).toLowerCase();
            }
        }

        LoggerProvider provider = registry.findLoggerProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        // Set mode: change log level
        if (loggerName != null && level != null) {
            String result = provider.setLogLevel(pid, loggerName, level);

            if (json) {
                System.out.println("{\"logger\":\"" + RichRenderer.escapeJson(loggerName)
                        + "\",\"level\":\"" + RichRenderer.escapeJson(level)
                        + "\",\"result\":\"" + RichRenderer.escapeJson(result) + "\"}");
                return;
            }

            System.out.print(RichRenderer.brandedHeader(useColor, "logger", messages.get("desc.logger")));
            System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.logger"),
                    WIDTH, "pid:" + pid, "SET"));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            String ok = "  " + AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u2714 "
                    + loggerName + " \u2192 " + level.toUpperCase()
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(ok, WIDTH));

            if (!result.isEmpty() && !result.startsWith("Error")) {
                System.out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(useColor, AnsiStyle.DIM) + result
                                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            }

            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
            return;
        }

        // List mode
        LoggerResult result = provider.listLoggers(pid);

        if (json) {
            printJson(result, filter);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "logger", messages.get("desc.logger")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.logger"),
                WIDTH, "pid:" + pid));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (result.loggers().isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM)
                            + messages.get("logger.none")
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        } else {
            // Table header
            String header = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                    + RichRenderer.padRight("Logger", 40)
                    + RichRenderer.padRight("Level", 12)
                    + RichRenderer.padRight("Source", 10)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(header, WIDTH));
            System.out.println(RichRenderer.boxSeparator(WIDTH));

            int count = 0;
            for (LoggerInfo info : result.loggers()) {
                if (filter != null && !info.name().toLowerCase().contains(filter)) {
                    continue;
                }

                String levelColor = levelColor(useColor, info.level());
                String line = RichRenderer.padRight(RichRenderer.truncate(info.name(), 38), 40)
                        + levelColor + RichRenderer.padRight(info.level().toUpperCase(), 12)
                        + AnsiStyle.style(useColor, AnsiStyle.RESET)
                        + AnsiStyle.style(useColor, AnsiStyle.DIM)
                        + RichRenderer.padRight(info.source(), 10)
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(line, WIDTH));
                count++;
            }

            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM)
                            + count + " logger(s)"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static String levelColor(boolean useColor, String level) {
        return switch (level.toUpperCase()) {
            case "ERROR", "SEVERE", "OFF" -> AnsiStyle.style(useColor, AnsiStyle.RED);
            case "WARNING", "WARN" -> AnsiStyle.style(useColor, AnsiStyle.YELLOW);
            case "DEBUG", "FINE", "FINER", "FINEST", "TRACE" -> AnsiStyle.style(useColor, AnsiStyle.CYAN);
            default -> AnsiStyle.style(useColor, AnsiStyle.GREEN);
        };
    }

    private static void printJson(LoggerResult result, String filter) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"loggers\":[");
        boolean first = true;
        for (LoggerInfo info : result.loggers()) {
            if (filter != null && !info.name().toLowerCase().contains(filter.toLowerCase())) {
                continue;
            }
            if (!first) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(info.name())).append('"');
            sb.append(",\"level\":\"").append(RichRenderer.escapeJson(info.level())).append('"');
            sb.append(",\"source\":\"").append(RichRenderer.escapeJson(info.source())).append('"');
            sb.append('}');
            first = false;
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
