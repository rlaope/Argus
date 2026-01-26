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
 * </ul>
 */
public final class AgentConfig {

    private static final int DEFAULT_BUFFER_SIZE = 65536;
    private static final int DEFAULT_SERVER_PORT = 9202;
    private static final boolean DEFAULT_SERVER_ENABLED = true;
    private static final boolean DEFAULT_GC_ENABLED = true;
    private static final boolean DEFAULT_CPU_ENABLED = true;
    private static final int DEFAULT_CPU_INTERVAL_MS = 1000;

    private final int bufferSize;
    private final int serverPort;
    private final boolean serverEnabled;
    private final boolean gcEnabled;
    private final boolean cpuEnabled;
    private final int cpuIntervalMs;

    private AgentConfig(int bufferSize, int serverPort, boolean serverEnabled,
                        boolean gcEnabled, boolean cpuEnabled, int cpuIntervalMs) {
        this.bufferSize = bufferSize;
        this.serverPort = serverPort;
        this.serverEnabled = serverEnabled;
        this.gcEnabled = gcEnabled;
        this.cpuEnabled = cpuEnabled;
        this.cpuIntervalMs = cpuIntervalMs;
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

        return new AgentConfig(bufferSize, serverPort, serverEnabled, gcEnabled, cpuEnabled, cpuIntervalMs);
    }

    /**
     * Creates a configuration with default values.
     *
     * @return default configuration
     */
    public static AgentConfig defaults() {
        return new AgentConfig(DEFAULT_BUFFER_SIZE, DEFAULT_SERVER_PORT, DEFAULT_SERVER_ENABLED,
                DEFAULT_GC_ENABLED, DEFAULT_CPU_ENABLED, DEFAULT_CPU_INTERVAL_MS);
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

    @Override
    public String toString() {
        return "AgentConfig{" +
                "bufferSize=" + bufferSize +
                ", serverPort=" + serverPort +
                ", serverEnabled=" + serverEnabled +
                ", gcEnabled=" + gcEnabled +
                ", cpuEnabled=" + cpuEnabled +
                ", cpuIntervalMs=" + cpuIntervalMs +
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

        public AgentConfig build() {
            return new AgentConfig(bufferSize, serverPort, serverEnabled,
                    gcEnabled, cpuEnabled, cpuIntervalMs);
        }
    }
}
