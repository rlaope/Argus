package io.argus.agent.config;

/**
 * Configuration holder for the Argus agent.
 *
 * <p>This class encapsulates all configuration options for the agent,
 * parsed from system properties with sensible defaults.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code argus.buffer.size} - Ring buffer size (default: 65536)</li>
 *   <li>{@code argus.server.enabled} - Enable built-in server (default: true)</li>
 *   <li>{@code argus.server.port} - Server port (default: 9202)</li>
 *   <li>{@code argus.gc.enabled} - Enable GC monitoring (default: true)</li>
 *   <li>{@code argus.cpu.enabled} - Enable CPU monitoring (default: true)</li>
 *   <li>{@code argus.cpu.interval} - CPU sampling interval in ms (default: 1000)</li>
 *   <li>{@code argus.allocation.enabled} - Enable allocation tracking (default: true)</li>
 *   <li>{@code argus.allocation.threshold} - Minimum allocation size to track in bytes (default: 1024)</li>
 *   <li>{@code argus.metaspace.enabled} - Enable metaspace monitoring (default: true)</li>
 *   <li>{@code argus.profiling.enabled} - Enable method profiling (default: false)</li>
 *   <li>{@code argus.profiling.interval} - Profiling sampling interval in ms (default: 20)</li>
 *   <li>{@code argus.contention.enabled} - Enable lock contention tracking (default: true)</li>
 *   <li>{@code argus.contention.threshold} - Minimum contention time to track in ms (default: 10)</li>
 *   <li>{@code argus.correlation.enabled} - Enable correlation analysis (default: true)</li>
 * </ul>
 */
public final class AgentConfig {

    private static final int DEFAULT_BUFFER_SIZE = 65536;
    private static final int DEFAULT_SERVER_PORT = 9202;
    private static final boolean DEFAULT_SERVER_ENABLED = true;
    private static final boolean DEFAULT_GC_ENABLED = true;
    private static final boolean DEFAULT_CPU_ENABLED = true;
    private static final int DEFAULT_CPU_INTERVAL_MS = 1000;
    private static final boolean DEFAULT_ALLOCATION_ENABLED = true;
    private static final int DEFAULT_ALLOCATION_THRESHOLD = 1024;
    private static final boolean DEFAULT_METASPACE_ENABLED = true;
    private static final boolean DEFAULT_PROFILING_ENABLED = false;
    private static final int DEFAULT_PROFILING_INTERVAL_MS = 20;
    private static final boolean DEFAULT_CONTENTION_ENABLED = true;
    private static final int DEFAULT_CONTENTION_THRESHOLD_MS = 10;
    private static final boolean DEFAULT_CORRELATION_ENABLED = true;

    private final int bufferSize;
    private final int serverPort;
    private final boolean serverEnabled;
    private final boolean gcEnabled;
    private final boolean cpuEnabled;
    private final int cpuIntervalMs;
    private final boolean allocationEnabled;
    private final int allocationThreshold;
    private final boolean metaspaceEnabled;
    private final boolean profilingEnabled;
    private final int profilingIntervalMs;
    private final boolean contentionEnabled;
    private final int contentionThresholdMs;
    private final boolean correlationEnabled;

    private AgentConfig(int bufferSize, int serverPort, boolean serverEnabled,
                        boolean gcEnabled, boolean cpuEnabled, int cpuIntervalMs,
                        boolean allocationEnabled, int allocationThreshold,
                        boolean metaspaceEnabled, boolean profilingEnabled,
                        int profilingIntervalMs, boolean contentionEnabled,
                        int contentionThresholdMs, boolean correlationEnabled) {
        this.bufferSize = bufferSize;
        this.serverPort = serverPort;
        this.serverEnabled = serverEnabled;
        this.gcEnabled = gcEnabled;
        this.cpuEnabled = cpuEnabled;
        this.cpuIntervalMs = cpuIntervalMs;
        this.allocationEnabled = allocationEnabled;
        this.allocationThreshold = allocationThreshold;
        this.metaspaceEnabled = metaspaceEnabled;
        this.profilingEnabled = profilingEnabled;
        this.profilingIntervalMs = profilingIntervalMs;
        this.contentionEnabled = contentionEnabled;
        this.contentionThresholdMs = contentionThresholdMs;
        this.correlationEnabled = correlationEnabled;
    }

    /**
     * Parses configuration from system properties.
     *
     * @return parsed configuration
     */
    public static AgentConfig fromSystemProperties() {
        int bufferSize = Integer.getInteger("argus.buffer.size", DEFAULT_BUFFER_SIZE);
        int serverPort = Integer.getInteger("argus.server.port", DEFAULT_SERVER_PORT);
        boolean serverEnabled = Boolean.parseBoolean(
                System.getProperty("argus.server.enabled", String.valueOf(DEFAULT_SERVER_ENABLED)));
        boolean gcEnabled = Boolean.parseBoolean(
                System.getProperty("argus.gc.enabled", String.valueOf(DEFAULT_GC_ENABLED)));
        boolean cpuEnabled = Boolean.parseBoolean(
                System.getProperty("argus.cpu.enabled", String.valueOf(DEFAULT_CPU_ENABLED)));
        int cpuIntervalMs = Integer.getInteger("argus.cpu.interval", DEFAULT_CPU_INTERVAL_MS);
        boolean allocationEnabled = Boolean.parseBoolean(
                System.getProperty("argus.allocation.enabled", String.valueOf(DEFAULT_ALLOCATION_ENABLED)));
        int allocationThreshold = Integer.getInteger("argus.allocation.threshold", DEFAULT_ALLOCATION_THRESHOLD);
        boolean metaspaceEnabled = Boolean.parseBoolean(
                System.getProperty("argus.metaspace.enabled", String.valueOf(DEFAULT_METASPACE_ENABLED)));
        boolean profilingEnabled = Boolean.parseBoolean(
                System.getProperty("argus.profiling.enabled", String.valueOf(DEFAULT_PROFILING_ENABLED)));
        int profilingIntervalMs = Integer.getInteger("argus.profiling.interval", DEFAULT_PROFILING_INTERVAL_MS);
        boolean contentionEnabled = Boolean.parseBoolean(
                System.getProperty("argus.contention.enabled", String.valueOf(DEFAULT_CONTENTION_ENABLED)));
        int contentionThresholdMs = Integer.getInteger("argus.contention.threshold", DEFAULT_CONTENTION_THRESHOLD_MS);
        boolean correlationEnabled = Boolean.parseBoolean(
                System.getProperty("argus.correlation.enabled", String.valueOf(DEFAULT_CORRELATION_ENABLED)));

        return new AgentConfig(bufferSize, serverPort, serverEnabled, gcEnabled, cpuEnabled, cpuIntervalMs,
                allocationEnabled, allocationThreshold, metaspaceEnabled, profilingEnabled, profilingIntervalMs,
                contentionEnabled, contentionThresholdMs, correlationEnabled);
    }

    /**
     * Creates a configuration with default values.
     *
     * @return default configuration
     */
    public static AgentConfig defaults() {
        return new AgentConfig(DEFAULT_BUFFER_SIZE, DEFAULT_SERVER_PORT, DEFAULT_SERVER_ENABLED,
                DEFAULT_GC_ENABLED, DEFAULT_CPU_ENABLED, DEFAULT_CPU_INTERVAL_MS,
                DEFAULT_ALLOCATION_ENABLED, DEFAULT_ALLOCATION_THRESHOLD, DEFAULT_METASPACE_ENABLED,
                DEFAULT_PROFILING_ENABLED, DEFAULT_PROFILING_INTERVAL_MS, DEFAULT_CONTENTION_ENABLED,
                DEFAULT_CONTENTION_THRESHOLD_MS, DEFAULT_CORRELATION_ENABLED);
    }

    /**
     * Creates a builder for custom configuration.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getServerPort() {
        return serverPort;
    }

    public boolean isServerEnabled() {
        return serverEnabled;
    }

    public boolean isGcEnabled() {
        return gcEnabled;
    }

    public boolean isCpuEnabled() {
        return cpuEnabled;
    }

    public int getCpuIntervalMs() {
        return cpuIntervalMs;
    }

    public boolean isAllocationEnabled() {
        return allocationEnabled;
    }

    public int getAllocationThreshold() {
        return allocationThreshold;
    }

    public boolean isMetaspaceEnabled() {
        return metaspaceEnabled;
    }

    public boolean isProfilingEnabled() {
        return profilingEnabled;
    }

    public int getProfilingIntervalMs() {
        return profilingIntervalMs;
    }

    public boolean isContentionEnabled() {
        return contentionEnabled;
    }

    public int getContentionThresholdMs() {
        return contentionThresholdMs;
    }

    public boolean isCorrelationEnabled() {
        return correlationEnabled;
    }

    @Override
    public String toString() {
        return "AgentConfig{" +
                "bufferSize=" + bufferSize +
                ", serverPort=" + serverPort +
                ", serverEnabled=" + serverEnabled +
                ", gcEnabled=" + gcEnabled +
                ", cpuEnabled=" + cpuEnabled +
                ", cpuIntervalMs=" + cpuIntervalMs +
                ", allocationEnabled=" + allocationEnabled +
                ", allocationThreshold=" + allocationThreshold +
                ", metaspaceEnabled=" + metaspaceEnabled +
                ", profilingEnabled=" + profilingEnabled +
                ", profilingIntervalMs=" + profilingIntervalMs +
                ", contentionEnabled=" + contentionEnabled +
                ", contentionThresholdMs=" + contentionThresholdMs +
                ", correlationEnabled=" + correlationEnabled +
                '}';
    }

    /**
     * Builder for AgentConfig.
     */
    public static final class Builder {
        private int bufferSize = DEFAULT_BUFFER_SIZE;
        private int serverPort = DEFAULT_SERVER_PORT;
        private boolean serverEnabled = DEFAULT_SERVER_ENABLED;
        private boolean gcEnabled = DEFAULT_GC_ENABLED;
        private boolean cpuEnabled = DEFAULT_CPU_ENABLED;
        private int cpuIntervalMs = DEFAULT_CPU_INTERVAL_MS;
        private boolean allocationEnabled = DEFAULT_ALLOCATION_ENABLED;
        private int allocationThreshold = DEFAULT_ALLOCATION_THRESHOLD;
        private boolean metaspaceEnabled = DEFAULT_METASPACE_ENABLED;
        private boolean profilingEnabled = DEFAULT_PROFILING_ENABLED;
        private int profilingIntervalMs = DEFAULT_PROFILING_INTERVAL_MS;
        private boolean contentionEnabled = DEFAULT_CONTENTION_ENABLED;
        private int contentionThresholdMs = DEFAULT_CONTENTION_THRESHOLD_MS;
        private boolean correlationEnabled = DEFAULT_CORRELATION_ENABLED;

        private Builder() {
        }

        public Builder bufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public Builder serverPort(int serverPort) {
            this.serverPort = serverPort;
            return this;
        }

        public Builder serverEnabled(boolean serverEnabled) {
            this.serverEnabled = serverEnabled;
            return this;
        }

        public Builder gcEnabled(boolean gcEnabled) {
            this.gcEnabled = gcEnabled;
            return this;
        }

        public Builder cpuEnabled(boolean cpuEnabled) {
            this.cpuEnabled = cpuEnabled;
            return this;
        }

        public Builder cpuIntervalMs(int cpuIntervalMs) {
            this.cpuIntervalMs = cpuIntervalMs;
            return this;
        }

        public Builder allocationEnabled(boolean allocationEnabled) {
            this.allocationEnabled = allocationEnabled;
            return this;
        }

        public Builder allocationThreshold(int allocationThreshold) {
            this.allocationThreshold = allocationThreshold;
            return this;
        }

        public Builder metaspaceEnabled(boolean metaspaceEnabled) {
            this.metaspaceEnabled = metaspaceEnabled;
            return this;
        }

        public Builder profilingEnabled(boolean profilingEnabled) {
            this.profilingEnabled = profilingEnabled;
            return this;
        }

        public Builder profilingIntervalMs(int profilingIntervalMs) {
            this.profilingIntervalMs = profilingIntervalMs;
            return this;
        }

        public Builder contentionEnabled(boolean contentionEnabled) {
            this.contentionEnabled = contentionEnabled;
            return this;
        }

        public Builder contentionThresholdMs(int contentionThresholdMs) {
            this.contentionThresholdMs = contentionThresholdMs;
            return this;
        }

        public Builder correlationEnabled(boolean correlationEnabled) {
            this.correlationEnabled = correlationEnabled;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(bufferSize, serverPort, serverEnabled,
                    gcEnabled, cpuEnabled, cpuIntervalMs, allocationEnabled,
                    allocationThreshold, metaspaceEnabled, profilingEnabled,
                    profilingIntervalMs, contentionEnabled, contentionThresholdMs,
                    correlationEnabled);
        }
    }
}
