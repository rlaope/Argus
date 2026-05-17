package io.argus.diagnostics.jcmd;

/**
 * Shared parsing utilities for JDK diagnostic providers.
 */
public final class JdkParseUtils {

    private JdkParseUtils() {}

    public static long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
