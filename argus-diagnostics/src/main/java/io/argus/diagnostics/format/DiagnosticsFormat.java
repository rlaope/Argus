package io.argus.diagnostics.format;

/**
 * Internal formatting helpers used by diagnostic models and analyzers.
 *
 * <p>Extracted from the CLI's RichRenderer so the diagnostics library
 * has no dependency on terminal-rendering code.
 */
public final class DiagnosticsFormat {

    private DiagnosticsFormat() {}

    /** Human-readable byte size: 0B / 12K / 34M / 1.2G / 3.4T. */
    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format("%.0fK", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format("%.0fM", mb);
        double gb = mb / 1024.0;
        if (gb < 1024.0) return String.format("%.1fG", gb);
        double tb = gb / 1024.0;
        return String.format("%.1fT", tb);
    }

    /** Rate formatter accepting KB/sec, scaling to KB/MB/GB. */
    public static String formatRate(double kbPerSec) {
        if (kbPerSec < 1024) return String.format("%.0f KB", kbPerSec);
        if (kbPerSec < 1024 * 1024) return String.format("%.1f MB", kbPerSec / 1024);
        return String.format("%.2f GB", kbPerSec / (1024 * 1024));
    }

    /** Minimal JSON string escape (handles backslash, quote, control chars). */
    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
