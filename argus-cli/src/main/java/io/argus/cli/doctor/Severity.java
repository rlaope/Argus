package io.argus.cli.doctor;

/**
 * Severity level for diagnostic findings.
 * Ordered from most severe to least.
 */
public enum Severity {
    CRITICAL("\uD83D\uDD34", "CRITICAL"),
    WARNING("\uD83D\uDFE1", "WARNING"),
    INFO("\uD83D\uDFE2", "INFO");

    private final String icon;
    private final String label;

    Severity(String icon, String label) {
        this.icon = icon;
        this.label = label;
    }

    public String icon() { return icon; }
    public String label() { return label; }
}
