package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.util.List;
import java.util.logging.LogManager;
import java.util.logging.LoggingMXBean;

@SuppressWarnings("deprecation")
public final class LoggerDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "logger"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public String description() { return "View java.util.logging logger levels"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        LoggingMXBean loggingMXBean = LogManager.getLoggingMXBean();
        List<String> loggerNames = loggingMXBean.getLoggerNames();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-50s %s\n", "Logger", "Level"));
        sb.append("─".repeat(65)).append('\n');

        int count = 0;
        for (String name : loggerNames.stream().sorted().toList()) {
            String level = loggingMXBean.getLoggerLevel(name);
            if (level == null || level.isEmpty()) continue;
            sb.append(String.format("%-50s %s\n",
                    name.isEmpty() ? "ROOT" : truncate(name, 48), level));
            count++;
        }
        sb.append("\n").append(count).append(" logger(s) with explicit levels");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : "..." + s.substring(s.length() - max + 3);
    }
}
