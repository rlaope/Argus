package io.argus.cli.util;

/**
 * Shared utilities for CLI command argument parsing.
 */
public final class CommandUtils {

    private CommandUtils() {}

    /**
     * Returns true when the argument looks like a JVM process ID: a non-empty
     * string of digits with no file-separator characters ({@code /}, {@code \},
     * {@code .}). The stricter check prevents treating relative file paths such
     * as {@code ./gc.log} or {@code ../logs/gc.log} as PIDs.
     *
     * @param arg the command-line token to test
     * @return true if {@code arg} is a valid PID token
     */
    public static boolean isPid(String arg) {
        if (arg == null || arg.isEmpty()) return false;
        if (arg.contains("/") || arg.contains("\\") || arg.contains(".")) return false;
        for (int i = 0; i < arg.length(); i++) {
            if (!Character.isDigit(arg.charAt(i))) return false;
        }
        return true;
    }
}
