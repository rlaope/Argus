package io.argus.spring;

import io.argus.core.config.AgentConfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ArgusAutoConfiguration} properties-to-config mapping.
 */
class ArgusAutoConfigurationTest {

    @Test
    void propertiesToAgentConfigMapping() {
        ArgusProperties props = new ArgusProperties();
        props.setBufferSize(131072);
        props.getServer().setPort(8080);
        props.getServer().setEnabled(false);
        props.getGc().setEnabled(true);
        props.getCpu().setEnabled(true);
        props.getCpu().setIntervalMs(500);
        props.getAllocation().setEnabled(true);
        props.getAllocation().setThreshold(524288);
        props.getMetaspace().setEnabled(false);
        props.getProfiling().setEnabled(true);
        props.getProfiling().setIntervalMs(10);
        props.getContention().setEnabled(true);
        props.getContention().setThresholdMs(25);
        props.getCorrelation().setEnabled(false);
        props.getMetrics().getPrometheus().setEnabled(false);
        props.getMetrics().getOtlp().setEnabled(true);
        props.getMetrics().getOtlp().setEndpoint("http://otel:4318/v1/metrics");
        props.getMetrics().getOtlp().setIntervalMs(5000);
        props.getMetrics().getOtlp().setHeaders("Authorization=Bearer xxx");
        props.getMetrics().getOtlp().setServiceName("my-app");

        // Use the same mapping logic as ArgusAutoConfiguration.argusAgentConfig()
        AgentConfig config = AgentConfig.builder()
                .bufferSize(props.getBufferSize())
                .serverPort(props.getServer().getPort())
                .serverEnabled(props.getServer().isEnabled())
                .gcEnabled(props.getGc().isEnabled())
                .cpuEnabled(props.getCpu().isEnabled())
                .cpuIntervalMs(props.getCpu().getIntervalMs())
                .allocationEnabled(props.getAllocation().isEnabled())
                .allocationThreshold(props.getAllocation().getThreshold())
                .metaspaceEnabled(props.getMetaspace().isEnabled())
                .profilingEnabled(props.getProfiling().isEnabled())
                .profilingIntervalMs(props.getProfiling().getIntervalMs())
                .contentionEnabled(props.getContention().isEnabled())
                .contentionThresholdMs(props.getContention().getThresholdMs())
                .correlationEnabled(props.getCorrelation().isEnabled())
                .prometheusEnabled(props.getMetrics().getPrometheus().isEnabled())
                .otlpEnabled(props.getMetrics().getOtlp().isEnabled())
                .otlpEndpoint(props.getMetrics().getOtlp().getEndpoint())
                .otlpIntervalMs(props.getMetrics().getOtlp().getIntervalMs())
                .otlpHeaders(props.getMetrics().getOtlp().getHeaders())
                .otlpServiceName(props.getMetrics().getOtlp().getServiceName())
                .build();

        assertEquals(131072, config.getBufferSize());
        assertEquals(8080, config.getServerPort());
        assertFalse(config.isServerEnabled());
        assertTrue(config.isGcEnabled());
        assertTrue(config.isCpuEnabled());
        assertEquals(500, config.getCpuIntervalMs());
        assertTrue(config.isAllocationEnabled());
        assertEquals(524288, config.getAllocationThreshold());
        assertFalse(config.isMetaspaceEnabled());
        assertTrue(config.isProfilingEnabled());
        assertEquals(10, config.getProfilingIntervalMs());
        assertTrue(config.isContentionEnabled());
        assertEquals(25, config.getContentionThresholdMs());
        assertFalse(config.isCorrelationEnabled());
        assertFalse(config.isPrometheusEnabled());
        assertTrue(config.isOtlpEnabled());
        assertEquals("http://otel:4318/v1/metrics", config.getOtlpEndpoint());
        assertEquals(5000, config.getOtlpIntervalMs());
        assertEquals("Authorization=Bearer xxx", config.getOtlpHeaders());
        assertEquals("my-app", config.getOtlpServiceName());
    }

    @Test
    void defaultPropertiesMatchDefaultConfig() {
        ArgusProperties props = new ArgusProperties();
        AgentConfig defaults = AgentConfig.defaults();

        AgentConfig fromProps = AgentConfig.builder()
                .bufferSize(props.getBufferSize())
                .serverPort(props.getServer().getPort())
                .serverEnabled(props.getServer().isEnabled())
                .gcEnabled(props.getGc().isEnabled())
                .cpuEnabled(props.getCpu().isEnabled())
                .cpuIntervalMs(props.getCpu().getIntervalMs())
                .allocationEnabled(props.getAllocation().isEnabled())
                .allocationThreshold(props.getAllocation().getThreshold())
                .metaspaceEnabled(props.getMetaspace().isEnabled())
                .profilingEnabled(props.getProfiling().isEnabled())
                .profilingIntervalMs(props.getProfiling().getIntervalMs())
                .contentionEnabled(props.getContention().isEnabled())
                .contentionThresholdMs(props.getContention().getThresholdMs())
                .correlationEnabled(props.getCorrelation().isEnabled())
                .prometheusEnabled(props.getMetrics().getPrometheus().isEnabled())
                .otlpEnabled(props.getMetrics().getOtlp().isEnabled())
                .otlpEndpoint(props.getMetrics().getOtlp().getEndpoint())
                .otlpIntervalMs(props.getMetrics().getOtlp().getIntervalMs())
                .otlpHeaders(props.getMetrics().getOtlp().getHeaders())
                .otlpServiceName(props.getMetrics().getOtlp().getServiceName())
                .build();

        assertEquals(defaults.getBufferSize(), fromProps.getBufferSize());
        assertEquals(defaults.getServerPort(), fromProps.getServerPort());
        assertEquals(defaults.isServerEnabled(), fromProps.isServerEnabled());
        assertEquals(defaults.isGcEnabled(), fromProps.isGcEnabled());
        assertEquals(defaults.isCpuEnabled(), fromProps.isCpuEnabled());
        assertEquals(defaults.getCpuIntervalMs(), fromProps.getCpuIntervalMs());
        assertEquals(defaults.isAllocationEnabled(), fromProps.isAllocationEnabled());
        assertEquals(defaults.isMetaspaceEnabled(), fromProps.isMetaspaceEnabled());
        assertEquals(defaults.isProfilingEnabled(), fromProps.isProfilingEnabled());
        assertEquals(defaults.isContentionEnabled(), fromProps.isContentionEnabled());
        assertEquals(defaults.isPrometheusEnabled(), fromProps.isPrometheusEnabled());
        assertEquals(defaults.isOtlpEnabled(), fromProps.isOtlpEnabled());
    }
}
