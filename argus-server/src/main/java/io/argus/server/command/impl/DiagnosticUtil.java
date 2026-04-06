package io.argus.server.command.impl;

import java.util.Set;

/**
 * Shared utility methods for diagnostic command implementations.
 * Extracted from ServerCommandExecutor to avoid duplication across SPI commands.
 */
public final class DiagnosticUtil {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "PASSWORD", "SECRET", "KEY", "TOKEN", "CREDENTIAL", "AUTH", "PRIVATE");

    private DiagnosticUtil() {}

    public static String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static String maskIfSensitive(String key, String value) {
        String upper = key.toUpperCase();
        for (String sensitive : SENSITIVE_KEYS) {
            if (upper.contains(sensitive)) {
                return value != null && value.length() > 4
                        ? value.substring(0, 2) + "****" + value.substring(value.length() - 2)
                        : "****";
            }
        }
        return value;
    }

    public static String executeJcmd(String command, String arg) {
        try {
            long pid = ProcessHandle.current().pid();
            String jcmd = System.getProperty("java.home") + "/bin/jcmd";
            var cmdList = new java.util.ArrayList<>(java.util.List.of(jcmd, String.valueOf(pid), command));
            if (arg != null && !arg.isBlank()) cmdList.add(arg);
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "Command timed out after 10 seconds: jcmd " + command;
            }
            return output;
        } catch (Exception e) {
            return "Failed to execute jcmd " + command + ": " + e.getMessage();
        }
    }

    public static String header(String title) {
        return "═══════════════════════════════════════════════════\n"
                + "  " + title + "\n"
                + "═══════════════════════════════════════════════════\n\n";
    }
}
