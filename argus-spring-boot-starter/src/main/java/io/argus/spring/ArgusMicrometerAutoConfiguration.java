package io.argus.spring;

import io.argus.micrometer.ArgusMeterBinder;
import io.argus.micrometer.ArgusMetricsConfig;
import io.argus.server.ArgusServer;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Auto-configuration for Argus Micrometer integration.
 *
 * <p>Automatically registers {@link ArgusMeterBinder} when Micrometer is
 * on the classpath and Argus server is active. Exposes ~25 JVM diagnostic
 * metrics to any configured {@link MeterRegistry} (Prometheus, Datadog, etc.).
 *
 * <p>Disable via {@code argus.metrics.micrometer.enabled=false}.
 */
@AutoConfiguration(after = ArgusAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(ArgusServer.class)
@ConditionalOnProperty(prefix = "argus.metrics.micrometer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArgusMicrometerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ArgusMetricsConfig argusMetricsConfig(ArgusProperties props) {
        return ArgusMetricsConfig.builder()
                .gcMetrics(props.getGc().isEnabled())
                .cpuMetrics(props.getCpu().isEnabled())
                .metaspaceMetrics(props.getMetaspace().isEnabled())
                .contentionMetrics(props.getContention().isEnabled())
                .allocationMetrics(props.getAllocation().isEnabled())
                .profilingMetrics(props.getProfiling().isEnabled())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ArgusMeterBinder argusMeterBinder(ArgusServer server, ArgusMetricsConfig metricsConfig) {
        return new ArgusMeterBinder(
                server.getMetrics(),
                server.getActiveThreads(),
                server.getPinningAnalyzer(),
                server.getCarrierAnalyzer(),
                server.getGcAnalyzer(),
                server.getCpuAnalyzer(),
                server.getAllocationAnalyzer(),
                server.getMetaspaceAnalyzer(),
                server.getMethodProfilingAnalyzer(),
                server.getContentionAnalyzer(),
                server.getConfig(),
                metricsConfig
        );
    }
}
