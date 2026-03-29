package io.argus.cli.model;

/**
 * JMX Management Agent status.
 */
public final class JmxResult {
    private final boolean enabled;
    private final String connectorUrl;
    private final String rawOutput;

    public JmxResult(boolean enabled, String connectorUrl, String rawOutput) {
        this.enabled = enabled;
        this.connectorUrl = connectorUrl;
        this.rawOutput = rawOutput;
    }

    public boolean enabled() { return enabled; }
    public String connectorUrl() { return connectorUrl; }
    public String rawOutput() { return rawOutput; }
}
