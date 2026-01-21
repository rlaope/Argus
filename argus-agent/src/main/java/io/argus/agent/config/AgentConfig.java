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
 *   <li>{@code argus.server.port} - Server port (default: 8080)</li>
 * </ul>
 */
public final class AgentConfig {

    private static final int DEFAULT_BUFFER_SIZE = 65536;
    private static final int DEFAULT_SERVER_PORT = 8080;
    private static final boolean DEFAULT_SERVER_ENABLED = true;

    private final int bufferSize;
    private final int serverPort;
    private final boolean serverEnabled;

    private AgentConfig(int bufferSize, int serverPort, boolean serverEnabled) {
        this.bufferSize = bufferSize;
        this.serverPort = serverPort;
        this.serverEnabled = serverEnabled;
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

        return new AgentConfig(bufferSize, serverPort, serverEnabled);
    }

    /**
     * Creates a configuration with default values.
     *
     * @return default configuration
     */
    public static AgentConfig defaults() {
        return new AgentConfig(DEFAULT_BUFFER_SIZE, DEFAULT_SERVER_PORT, DEFAULT_SERVER_ENABLED);
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

    @Override
    public String toString() {
        return "AgentConfig{" +
                "bufferSize=" + bufferSize +
                ", serverPort=" + serverPort +
                ", serverEnabled=" + serverEnabled +
                '}';
    }

    /**
     * Builder for AgentConfig.
     */
    public static final class Builder {
        private int bufferSize = DEFAULT_BUFFER_SIZE;
        private int serverPort = DEFAULT_SERVER_PORT;
        private boolean serverEnabled = DEFAULT_SERVER_ENABLED;

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

        public AgentConfig build() {
            return new AgentConfig(bufferSize, serverPort, serverEnabled);
        }
    }
}
