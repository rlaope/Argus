package io.argus.cli.model;

import java.util.Map;

/**
 * Snapshot of JVM system properties from jcmd VM.system_properties.
 */
public final class SysPropsResult {
    private final Map<String, String> properties;

    public SysPropsResult(Map<String, String> properties) {
        this.properties = properties;
    }

    public Map<String, String> properties() { return properties; }
}
