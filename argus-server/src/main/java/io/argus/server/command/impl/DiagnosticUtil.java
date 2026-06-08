package io.argus.server.command.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Shared utility methods for diagnostic command implementations.
 * Extracted from ServerCommandExecutor to avoid duplication across SPI commands.
 */
public final class DiagnosticUtil {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            // Common credential markers
            "PASSWORD", "PASSWD", "PASSPHRASE",
            "SECRET", "KEY", "TOKEN", "CREDENTIAL", "AUTH", "PRIVATE",
            "BEARER", "SESSION", "DSN",
            "PEM", "CERT", "SIGNATURE",
            // Connection-string envs that commonly carry user:password inline
            "DATABASE_URL", "JDBC_URL", "MONGODB_URI", "REDIS_URL",
            "AMQP_URL", "RABBITMQ_URL", "POSTGRES_URL", "MYSQL_URL");

    // Connection strings like "scheme://user:password@host[:port]/path" leak
    // credentials in the value even when the key name is generic (e.g.
    // "url", "endpoint"). Mask any value whose shape matches.
    private static final java.util.regex.Pattern CONN_STRING_WITH_USERINFO =
            java.util.regex.Pattern.compile(
                    "^[a-zA-Z][a-zA-Z0-9+.-]*://[^/@\\s]*:[^/@\\s]+@.*");

    private DiagnosticUtil() {}

    public static String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static String maskIfSensitive(String key, String value) {
        String upper = key == null ? "" : key.toUpperCase();
        for (String sensitive : SENSITIVE_KEYS) {
            if (upper.contains(sensitive)) {
                return maskValue(value);
            }
        }
        // Value-shape masking: catch credentials in values whose key name
        // alone would not raise a flag. Important now that diagnostic
        // commands like sysprops/env are reachable cross-pod via the
        // aggregator proxy — the blast radius for a leaked secret is wider.
        if (value != null && CONN_STRING_WITH_USERINFO.matcher(value).matches()) {
            return maskValue(value);
        }
        return value;
    }

    private static String maskValue(String value) {
        if (value == null) return "****";
        if (value.length() <= 4) return "****";
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    public static String executeJcmd(String command, String arg) {
        return executeJcmd(ProcessHandle.current().pid(), command, arg);
    }

    static String executeJcmd(long pid, String command, String arg) {
        try {
            String jcmd = System.getProperty("java.home") + "/bin/jcmd";
            var cmdList = new ArrayList<>(List.of(jcmd, String.valueOf(pid), command));
            if (arg != null && !arg.isBlank()) cmdList.add(arg);
            return executeProcess(cmdList, 10, "Command timed out after 10 seconds: jcmd " + command);
        } catch (Exception e) {
            return "Failed to execute jcmd " + command + ": " + e.getMessage();
        }
    }

    static String executeProcess(List<String> command, long timeoutSeconds, String timeoutMessage)
            throws InterruptedException, IOException {
        List<String> outputLines = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (outputLines) {
                        outputLines.add(line);
                    }
                }
            } catch (Exception ignored) {
                // Process termination closes the stream; the timeout path handles the result.
            }
        }, "argus-diagnostic-output");
        outputThread.setDaemon(true);
        outputThread.start();

        boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            proc.waitFor(2, TimeUnit.SECONDS);
            outputThread.join(2000);
            return timeoutMessage;
        }

        outputThread.join(2000);
        synchronized (outputLines) {
            return String.join("\n", outputLines);
        }
    }

    public static String header(String title) {
        return "═══════════════════════════════════════════════════\n"
                + "  " + title + "\n"
                + "═══════════════════════════════════════════════════\n\n";
    }
}
