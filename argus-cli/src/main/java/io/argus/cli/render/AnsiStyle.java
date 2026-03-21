package io.argus.cli.render;

/**
 * ANSI escape code constants and color utility methods.
 */
public final class AnsiStyle {

    public static final String RESET        = "\033[0m";
    public static final String BOLD         = "\033[1m";
    public static final String DIM          = "\033[2m";
    public static final String GREEN        = "\033[32m";
    public static final String YELLOW       = "\033[33m";
    public static final String RED          = "\033[31m";
    public static final String CYAN         = "\033[36m";
    public static final String WHITE        = "\033[37m";
    public static final String BLUE         = "\033[34m";
    public static final String MAGENTA      = "\033[35m";
    public static final String BG_RED       = "\033[41m";
    public static final String CLEAR_SCREEN = "\033[2J\033[H";
    public static final String HIDE_CURSOR  = "\033[?25l";
    public static final String SHOW_CURSOR  = "\033[?25h";

    private AnsiStyle() {}

    /**
     * Returns the joined ANSI codes when color is enabled, or an empty string otherwise.
     */
    public static String style(boolean useColor, String... codes) {
        if (!useColor) return "";
        return String.join("", codes);
    }

    /**
     * Returns the appropriate color code based on value thresholds.
     * Returns RED if value >= crit, YELLOW if value >= warn, GREEN otherwise.
     * Returns empty string when color is disabled.
     */
    public static String colorByThreshold(boolean useColor, double value, double warn, double crit) {
        if (!useColor) return "";
        if (value >= crit) return RED;
        if (value >= warn) return YELLOW;
        return GREEN;
    }
}
