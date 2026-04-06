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
    private final double processCpuLoad;
    private final double systemCpuLoad;
    private final int availableProcessors;
    private final double systemLoadAverage;

    public InfoResult(String vmName, String vmVersion, String vmVendor,
                      long uptimeMs, long pid, List<String> vmFlags,
                      Map<String, String> systemProperties) {
        this(vmName, vmVersion, vmVendor, uptimeMs, pid, vmFlags, systemProperties, -1, -1, 0, -1);
    }

    public InfoResult(String vmName, String vmVersion, String vmVendor,
                      long uptimeMs, long pid, List<String> vmFlags,
                      Map<String, String> systemProperties,
                      double processCpuLoad, double systemCpuLoad,
                      int availableProcessors, double systemLoadAverage) {
        this.vmName = vmName;
        this.vmVersion = vmVersion;
        this.vmVendor = vmVendor;
        this.uptimeMs = uptimeMs;
        this.pid = pid;
        this.vmFlags = vmFlags;
        this.systemProperties = systemProperties;
        this.processCpuLoad = processCpuLoad;
        this.systemCpuLoad = systemCpuLoad;
        this.availableProcessors = availableProcessors;
        this.systemLoadAverage = systemLoadAverage;
    }

    public String vmName() { return vmName; }
    public String vmVersion() { return vmVersion; }
    public String vmVendor() { return vmVendor; }
    public long uptimeMs() { return uptimeMs; }
    public long pid() { return pid; }
    public List<String> vmFlags() { return vmFlags; }
    public Map<String, String> systemProperties() { return systemProperties; }
    public double processCpuLoad() { return processCpuLoad; }
    public double systemCpuLoad() { return systemCpuLoad; }
    public int availableProcessors() { return availableProcessors; }
    public double systemLoadAverage() { return systemLoadAverage; }
}
