package io.argus.cli.provider.jdk;

import io.argus.cli.model.LoggerResult;
import io.argus.cli.model.LoggerResult.LoggerInfo;
import io.argus.cli.provider.LoggerProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.LoggingMXBean;
import java.util.logging.LogManager;

/**
 * LoggerProvider that uses jcmd VM.log for JVM unified logging
 * and java.util.logging LoggingMXBean for application loggers.
 */
public final class JdkLoggerProvider implements LoggerProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String source() {
        return "jdk";
    }

    @Override
    public LoggerResult listLoggers(long pid) {
        List<LoggerInfo> loggers = new ArrayList<>();
        String rawOutput = "";

        // 1. JVM Unified Logging via jcmd VM.log list
        try {
            rawOutput = JcmdExecutor.execute(pid, "VM.log list");
            parseVmLogOutput(rawOutput, loggers);
        } catch (RuntimeException ignored) {}

        // 2. java.util.logging loggers (only for current process)
        if (pid == ProcessHandle.current().pid()) {
            try {
                LoggingMXBean loggingMXBean = LogManager.getLoggingMXBean();
                List<String> loggerNames = loggingMXBean.getLoggerNames();
                for (String name : loggerNames) {
                    String level = loggingMXBean.getLoggerLevel(name);
                    if (level != null && !level.isEmpty()) {
                        loggers.add(new LoggerInfo(
                                name.isEmpty() ? "ROOT" : name,
                                level,
                                "j.u.l"));
                    }
                }
            } catch (Exception ignored) {}
        }

        return new LoggerResult(List.copyOf(loggers), rawOutput);
    }

    @Override
    public String setLogLevel(long pid, String loggerName, String level) {
        // VM.log supports: output, what, decorators
        // "what" controls which tags are logged at which level
        // Example: jcmd <pid> VM.log what=gc*=debug
        try {
            String command = "VM.log what=" + loggerName + "=" + level;
            return JcmdExecutor.execute(pid, command);
        } catch (RuntimeException e) {
            return "Error: " + (e.getMessage() != null ? e.getMessage() : "unknown");
        }
    }

    private void parseVmLogOutput(String output, List<LoggerInfo> loggers) {
        if (output == null || output.isEmpty()) return;

        // VM.log list output format:
        // Available log levels: off, trace, debug, info, warning, error
        // Available log decorators: ...
        // Available log tags: ...
        // Log output configuration:
        // #0: stdout all=info ...
        // #1: file=gc.log gc*=debug ...
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                // Parse output lines like "#0: stdout all=warning uptime,level,tags"
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx < 0) continue;
                String rest = trimmed.substring(colonIdx + 1).trim();
                String[] parts = rest.split("\\s+");
                if (parts.length >= 2) {
                    String output_name = parts[0]; // stdout, stderr, file=xxx
                    for (int i = 1; i < parts.length; i++) {
                        if (parts[i].contains("=") && !parts[i].startsWith("file=")) {
                            String[] kv = parts[i].split("=", 2);
                            loggers.add(new LoggerInfo(
                                    output_name + ":" + kv[0],
                                    kv.length > 1 ? kv[1] : "info",
                                    "vm.log"));
                        }
                    }
                }
            }
        }
    }
}
