package io.argus.cli.model;

import java.util.List;
import java.util.Map;

/**
 * JVM information result.
 */
public final class InfoResult {
    private final String vmName;
    private final String vmVersion;
    private final String vmVendor;
    private final long uptimeMs;
    private final long pid;
    private final List<String> vmFlags;
    private final Map<String, String> systemProperties;

    public InfoResult(String vmName, String vmVersion, String vmVendor,
                      long uptimeMs, long pid, List<String> vmFlags,
                      Map<String, String> systemProperties) {
        this.vmName = vmName;
        this.vmVersion = vmVersion;
        this.vmVendor = vmVendor;
        this.uptimeMs = uptimeMs;
        this.pid = pid;
        this.vmFlags = vmFlags;
        this.systemProperties = systemProperties;
    }

    public String vmName() { return vmName; }
    public String vmVersion() { return vmVersion; }
    public String vmVendor() { return vmVendor; }
    public long uptimeMs() { return uptimeMs; }
    public long pid() { return pid; }
    public List<String> vmFlags() { return vmFlags; }
    public Map<String, String> systemProperties() { return systemProperties; }
}
