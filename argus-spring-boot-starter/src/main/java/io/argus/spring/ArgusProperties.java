package io.argus.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Argus JVM Observability.
 *
 * <p>Maps to {@code argus.*} in application.yml/properties.
 * All defaults mirror {@link io.argus.core.config.AgentConfig} for consistency.
 *
 * <p>Example configuration:
 * <pre>
 * argus:
 *   enabled: true
 *   buffer-size: 131072
 *   server:
 *     port: 9202
 *   gc:
 *     enabled: true
 *   cpu:
 *     enabled: true
 *     interval-ms: 500
 *   allocation:
 *     enabled: true
 *     threshold: 524288
 * </pre>
 */
@ConfigurationProperties(prefix = "argus")
public class ArgusProperties {

    private boolean enabled = true;
    private int bufferSize = 65536;
    private Server server = new Server();
    private Gc gc = new Gc();
    private Cpu cpu = new Cpu();
    private Allocation allocation = new Allocation();
    private Metaspace metaspace = new Metaspace();
    private Profiling profiling = new Profiling();
    private Contention contention = new Contention();
    private Correlation correlation = new Correlation();
    private Metrics metrics = new Metrics();

    // -- Top-level getters/setters --

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getBufferSize() { return bufferSize; }
    public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }

    public Gc getGc() { return gc; }
    public void setGc(Gc gc) { this.gc = gc; }

    public Cpu getCpu() { return cpu; }
    public void setCpu(Cpu cpu) { this.cpu = cpu; }

    public Allocation getAllocation() { return allocation; }
    public void setAllocation(Allocation allocation) { this.allocation = allocation; }

    public Metaspace getMetaspace() { return metaspace; }
    public void setMetaspace(Metaspace metaspace) { this.metaspace = metaspace; }

    public Profiling getProfiling() { return profiling; }
    public void setProfiling(Profiling profiling) { this.profiling = profiling; }

    public Contention getContention() { return contention; }
    public void setContention(Contention contention) { this.contention = contention; }

    public Correlation getCorrelation() { return correlation; }
    public void setCorrelation(Correlation correlation) { this.correlation = correlation; }

    public Metrics getMetrics() { return metrics; }
    public void setMetrics(Metrics metrics) { this.metrics = metrics; }

    // -- Nested classes --

    public static class Server {
        private boolean enabled = true;
        private int port = 9202;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class Gc {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Cpu {
        private boolean enabled = true;
        private int intervalMs = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getIntervalMs() { return intervalMs; }
        public void setIntervalMs(int intervalMs) { this.intervalMs = intervalMs; }
    }

    public static class Allocation {
        private boolean enabled = false;
        private int threshold = 1048576;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getThreshold() { return threshold; }
        public void setThreshold(int threshold) { this.threshold = threshold; }
    }

    public static class Metaspace {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Profiling {
        private boolean enabled = false;
        private int intervalMs = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getIntervalMs() { return intervalMs; }
        public void setIntervalMs(int intervalMs) { this.intervalMs = intervalMs; }
    }

    public static class Contention {
        private boolean enabled = false;
        private int thresholdMs = 50;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getThresholdMs() { return thresholdMs; }
        public void setThresholdMs(int thresholdMs) { this.thresholdMs = thresholdMs; }
    }

    public static class Correlation {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Metrics {
        private Prometheus prometheus = new Prometheus();
        private Otlp otlp = new Otlp();
        private Micrometer micrometer = new Micrometer();

        public Prometheus getPrometheus() { return prometheus; }
        public void setPrometheus(Prometheus prometheus) { this.prometheus = prometheus; }
        public Otlp getOtlp() { return otlp; }
        public void setOtlp(Otlp otlp) { this.otlp = otlp; }
        public Micrometer getMicrometer() { return micrometer; }
        public void setMicrometer(Micrometer micrometer) { this.micrometer = micrometer; }
    }

    public static class Prometheus {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Otlp {
        private boolean enabled = false;
        private String endpoint = "http://localhost:4318/v1/metrics";
        private int intervalMs = 15000;
        private String headers = "";
        private String serviceName = "argus";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public int getIntervalMs() { return intervalMs; }
        public void setIntervalMs(int intervalMs) { this.intervalMs = intervalMs; }
        public String getHeaders() { return headers; }
        public void setHeaders(String headers) { this.headers = headers; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    }

    public static class Micrometer {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
