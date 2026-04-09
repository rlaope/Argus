package io.argus.cli.render;

import java.util.List;

/**
 * Utility class for modern CLI box-drawing output using Unicode box characters.
 * Default box width is 60 characters.
 */
public final class RichRenderer {

    public static int terminalWidth() {
        try {
            Process p = new ProcessBuilder("tput", "cols")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            int w = Integer.parseInt(out);
            return Math.max(60, Math.min(w, 200));
        } catch (Exception e) {
            return 80; // safe default
        }
    }

    private static volatile int cachedWidth;
    public static final int DEFAULT_WIDTH = terminalWidth();

    /** Re-query terminal width (useful for watch/top commands). */
    public static int currentWidth() {
        return terminalWidth();
    }

    // Box-drawing characters
    private static final char TL = '╭';
    private static final char TR = '╮';
    private static final char BL = '╰';
    private static final char BR = '╯';
    private static final char VERT = '│';
    private static final char HORIZ = '─';
    private static final char TEE_L = '├';
    private static final char TEE_R = '┤';

    private RichRenderer() {}

    /**
     * Renders a box header line: "╭─ Title ── meta1 ── meta2 ──...╮"
     * Total line width equals {@code width}.
     */
    public static String boxHeader(boolean useColor, String title, int width, String... meta) {
        StringBuilder sb = new StringBuilder(width + 32);
        sb.append(AnsiStyle.style(useColor, AnsiStyle.DIM));
        sb.append(TL);
        sb.append(HORIZ);
        sb.append(' ');
        sb.append(AnsiStyle.style(useColor, AnsiStyle.RESET, AnsiStyle.BOLD));
        sb.append(title);
        sb.append(AnsiStyle.style(useColor, AnsiStyle.RESET, AnsiStyle.DIM));
        sb.append(' ');

        // Build the meta suffix string
        StringBuilder metaSuffix = new StringBuilder();
        for (String m : meta) {
            metaSuffix.append(HORIZ).append(HORIZ).append(' ').append(m).append(' ');
        }

        // Fill with dashes up to width - 1 (for the closing corner)
        // Raw chars used so far: TL + HORIZ + space + title + space = 4 + title.length()
        int rawSoFar = 4 + title.length() + metaSuffix.length();
        int dashesNeeded = width - rawSoFar - 1; // -1 for TR
        if (dashesNeeded < 1) dashesNeeded = 1;

        sb.append(metaSuffix);
        sb.append(String.valueOf(HORIZ).repeat(dashesNeeded));
        sb.append(AnsiStyle.style(useColor, AnsiStyle.RESET, AnsiStyle.DIM));
        sb.append(TR);
        sb.append(AnsiStyle.style(useColor, AnsiStyle.RESET));
        return sb.toString();
    }

    /**
     * Renders a box footer line: "╰── summary ──...╯"
     */
    public static String boxFooter(boolean useColor, String summary, int width) {
        StringBuilder sb = new StringBuilder(width + 16);
        sb.append(AnsiStyle.style(useColor, AnsiStyle.DIM));
        sb.append(BL);

        if (summary == null || summary.isEmpty()) {
            int dashes = width - 2; // BL + BR
            sb.append(String.valueOf(HORIZ).repeat(Math.max(1, dashes)));
        } else {
            String segment = HORIZ + HORIZ + ' ' + summary + ' ';
            int rawUsed = 2 + segment.length(); // BL + BR + segment
            int dashes = width - rawUsed;
            sb.append(segment);
            sb.append(String.valueOf(HORIZ).repeat(Math.max(1, dashes)));
        }

        sb.append(BR);
        sb.append(AnsiStyle.style(useColor, AnsiStyle.RESET));
        return sb.toString();
    }

    /**
     * Renders a box content line: "│ content ...│" padded to {@code width}.
     * Content is truncated if too long.
     */
    public static String boxLine(String content, int width) {
        int innerWidth = width - 4;
        String text = content == null ? "" : content;
        int visibleLen = stripAnsi(text).length();
        if (visibleLen > innerWidth) {
            // Truncate respecting ANSI codes
            StringBuilder sb = new StringBuilder();
            int visible = 0;
            boolean inEscape = false;
            for (int i = 0; i < text.length() && visible < innerWidth - 1; i++) {
                char c = text.charAt(i);
                if (c == '\033') { inEscape = true; sb.append(c); continue; }
                if (inEscape) { sb.append(c); if (c == 'm') inEscape = false; continue; }
                sb.append(c);
                visible++;
            }
            sb.append(AnsiStyle.RESET).append("\u2026");
            text = sb.toString();
            visibleLen = innerWidth;
        }
        int padding = innerWidth - visibleLen;
        return VERT + " " + text + " ".repeat(Math.max(0, padding)) + " " + VERT;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;]*m", "");
    }

    /**
     * Renders a horizontal box separator: "├───...┤"
     */
    public static String boxSeparator(int width) {
        return TEE_L + String.valueOf(HORIZ).repeat(Math.max(1, width - 2)) + TEE_R;
    }

    /**
     * Renders an empty content line: "│          │"
     */
    public static String emptyLine(int width) {
        return boxLine("", width);
    }

    /**
     * Renders a progress bar with color thresholds.
     * Green below warn, yellow between warn and crit, red at or above crit.
     * Format: "████████░░░░" with surrounding brackets.
     *
     * @param percent value 0-100
     * @param width   number of fill characters (not counting brackets)
     */
    public static String progressBar(boolean useColor, double percent, int width) {
        int filled = (int) Math.round(Math.min(100.0, Math.max(0.0, percent)) / 100.0 * width);
        String color = AnsiStyle.colorByThreshold(useColor, percent, 70.0, 90.0);
        StringBuilder sb = new StringBuilder(width + 16);
        sb.append('[');
        sb.append(color);
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '\u2588' : '\u2591');
        }
        sb.append(AnsiStyle.style(useColor, AnsiStyle.RESET));
        sb.append(']');
        return sb.toString();
    }

    /**
     * Formats a byte count as a human-readable string.
     * Examples: "0B", "128M", "2.5G", "1.2T"
     */
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

    /**
     * Formats a KB value to a compact human-readable string.
     * Examples: "512K", "256M", "2.5G"
     */
    public static String formatKB(long kb) {
        return formatBytes(kb * 1024);
    }

    /**
     * Formats a long number with K/M suffix for large values.
     * Examples: "999", "1.2K", "5.3M"
     */
    public static String formatNumber(long n) {
        if (n < 1_000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1_000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }

    /**
     * Formats a duration in milliseconds to a human-readable string.
     * Examples: "10s", "2m 30s", "1h 15m"
     */
    public static String formatDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        sec %= 60;
        if (min < 60) return min + "m " + sec + "s";
        long hr = min / 60;
        min %= 60;
        return hr + "h " + min + "m";
    }

    /**
     * Shortens a fully-qualified class name to the last 2 segments.
     * Example: "io.argus.server.Foo" -> "server.Foo"
     */
    public static String shortenClassName(String name) {
        if (name == null) return "?";
        String[] parts = name.split("\\.");
        if (parts.length <= 2) return name;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    /**
     * Converts JVM internal type descriptors to human-readable class names.
     * Examples: "[B" -> "byte[]", "[Ljava.lang.String;" -> "String[]",
     * "java.util.HashMap$Node" -> "HashMap.Node"
     */
    public static String humanClassName(String name) {
        if (name == null) return "?";
        String s = name.trim();

        // Count array dimensions
        int dims = 0;
        while (s.startsWith("[")) {
            dims++;
            s = s.substring(1);
        }

        // Primitive type descriptors
        String base;
        if (s.equals("B")) {
            base = "byte";
        } else if (s.equals("C")) {
            base = "char";
        } else if (s.equals("D")) {
            base = "double";
        } else if (s.equals("F")) {
            base = "float";
        } else if (s.equals("I")) {
            base = "int";
        } else if (s.equals("J")) {
            base = "long";
        } else if (s.equals("S")) {
            base = "short";
        } else if (s.equals("Z")) {
            base = "boolean";
        } else {
            // Object type: "Ljava.lang.String;" -> "java.lang.String"
            if (s.startsWith("L") && s.endsWith(";")) {
                s = s.substring(1, s.length() - 1);
            }
            // Strip module info: "java.lang.String (java.base@21)" -> "java.lang.String"
            int paren = s.indexOf(" (");
            if (paren > 0) s = s.substring(0, paren);
            // Inner class: "$" -> "."
            s = s.replace('$', '.');
            // Simplify: keep last 2 segments for long names
            String[] parts = s.split("\\.");
            if (parts.length > 3) {
                base = parts[parts.length - 2] + "." + parts[parts.length - 1];
            } else {
                base = s;
            }
        }

        return base + "[]".repeat(dims);
    }

    /**
     * Right-pads the string with spaces to the given width.
     */
    public static String padRight(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /**
     * Escapes backslashes and double-quotes for JSON string values.
     */
    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Truncates a string to {@code max} characters, appending an ellipsis if shortened.
     */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    /**
     * Renders a branded header with the Argus label, command name, and description.
     * Format:
     *   (blank line)
     *    argus commandName
     *    description
     *   (blank line)
     */
    public static String brandedHeader(boolean useColor, String commandName, String description) {
        StringBuilder sb = new StringBuilder();
        String argusLabel = AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.CYAN)
                + "argus"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        sb.append('\n');
        sb.append(" ").append(argusLabel).append(" ").append(commandName).append('\n');
        sb.append(AnsiStyle.style(useColor, AnsiStyle.DIM))
          .append(" ").append(description)
          .append(AnsiStyle.style(useColor, AnsiStyle.RESET)).append('\n');
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Left-pads the string with spaces to the given width.
     */
    public static String padLeft(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    /**
     * Renders aligned table rows without box decoration (for use inside a box).
     * Each row is formatted as aligned columns according to {@code widths}.
     *
     * @param useColor whether to apply color to the header row
     * @param headers  column header labels
     * @param rows     data rows; each row must have the same number of cells as headers
     * @param widths   column widths in characters
     * @return a list of rendered lines (header + data rows)
     */
    public static List<String> table(boolean useColor, String[] headers, List<String[]> rows, int[] widths) {
        java.util.List<String> lines = new java.util.ArrayList<>();

        // Header row
        StringBuilder header = new StringBuilder();
        header.append(AnsiStyle.style(useColor, AnsiStyle.BOLD));
        for (int i = 0; i < headers.length; i++) {
            int w = i < widths.length ? widths[i] : 10;
            header.append(padRight(headers[i], w));
            if (i < headers.length - 1) header.append(' ');
        }
        header.append(AnsiStyle.style(useColor, AnsiStyle.RESET));
        lines.add(header.toString());

        // Data rows
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < headers.length; i++) {
                int w = i < widths.length ? widths[i] : 10;
                String cell = (row != null && i < row.length && row[i] != null) ? row[i] : "";
                line.append(padRight(cell, w));
                if (i < headers.length - 1) line.append(' ');
            }
            lines.add(line.toString());
        }

        return lines;
    }
}
