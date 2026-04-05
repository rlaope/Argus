package io.argus.micrometer;

/**
 * Configuration for which Argus metric categories to register with Micrometer.
 *
 * <p>By default, all categories are enabled. Disable individual categories
 * to reduce metric cardinality in high-volume environments.
 */
public final class ArgusMetricsConfig {

    private boolean virtualThreadMetrics = true;
    private boolean gcMetrics = true;
    private boolean cpuMetrics = true;
    private boolean metaspaceMetrics = true;
    private boolean contentionMetrics = true;
    private boolean allocationMetrics = true;
    private boolean profilingMetrics = true;

    public ArgusMetricsConfig() {
    }

    private ArgusMetricsConfig(Builder builder) {
        this.virtualThreadMetrics = builder.virtualThreadMetrics;
        this.gcMetrics = builder.gcMetrics;
        this.cpuMetrics = builder.cpuMetrics;
        this.metaspaceMetrics = builder.metaspaceMetrics;
        this.contentionMetrics = builder.contentionMetrics;
        this.allocationMetrics = builder.allocationMetrics;
        this.profilingMetrics = builder.profilingMetrics;
    }

    public boolean isVirtualThreadMetrics() { return virtualThreadMetrics; }
    public boolean isGcMetrics() { return gcMetrics; }
    public boolean isCpuMetrics() { return cpuMetrics; }
    public boolean isMetaspaceMetrics() { return metaspaceMetrics; }
    public boolean isContentionMetrics() { return contentionMetrics; }
    public boolean isAllocationMetrics() { return allocationMetrics; }
    public boolean isProfilingMetrics() { return profilingMetrics; }

    public void setVirtualThreadMetrics(boolean virtualThreadMetrics) { this.virtualThreadMetrics = virtualThreadMetrics; }
    public void setGcMetrics(boolean gcMetrics) { this.gcMetrics = gcMetrics; }
    public void setCpuMetrics(boolean cpuMetrics) { this.cpuMetrics = cpuMetrics; }
    public void setMetaspaceMetrics(boolean metaspaceMetrics) { this.metaspaceMetrics = metaspaceMetrics; }
    public void setContentionMetrics(boolean contentionMetrics) { this.contentionMetrics = contentionMetrics; }
    public void setAllocationMetrics(boolean allocationMetrics) { this.allocationMetrics = allocationMetrics; }
    public void setProfilingMetrics(boolean profilingMetrics) { this.profilingMetrics = profilingMetrics; }

    public static Builder builder() {
        return new Builder();
    }

    public static ArgusMetricsConfig defaults() {
        return new ArgusMetricsConfig();
    }

    public static final class Builder {
        private boolean virtualThreadMetrics = true;
        private boolean gcMetrics = true;
        private boolean cpuMetrics = true;
        private boolean metaspaceMetrics = true;
        private boolean contentionMetrics = true;
        private boolean allocationMetrics = true;
        private boolean profilingMetrics = true;

        private Builder() {}

        public Builder virtualThreadMetrics(boolean val) { this.virtualThreadMetrics = val; return this; }
        public Builder gcMetrics(boolean val) { this.gcMetrics = val; return this; }
        public Builder cpuMetrics(boolean val) { this.cpuMetrics = val; return this; }
        public Builder metaspaceMetrics(boolean val) { this.metaspaceMetrics = val; return this; }
        public Builder contentionMetrics(boolean val) { this.contentionMetrics = val; return this; }
        public Builder allocationMetrics(boolean val) { this.allocationMetrics = val; return this; }
        public Builder profilingMetrics(boolean val) { this.profilingMetrics = val; return this; }

        public ArgusMetricsConfig build() {
            return new ArgusMetricsConfig(this);
        }
    }
}
