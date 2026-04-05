package io.argus.spring;

import io.argus.agent.jfr.JfrStreamingEngine;
import io.argus.core.buffer.RingBuffer;
import io.argus.core.config.AgentConfig;
import io.argus.core.event.AllocationEvent;
import io.argus.core.event.ContentionEvent;
import io.argus.core.event.CPUEvent;
import io.argus.core.event.ExecutionSampleEvent;
import io.argus.core.event.GCEvent;
import io.argus.core.event.MetaspaceEvent;
import io.argus.core.event.VirtualThreadEvent;
import io.argus.server.ArgusServer;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for Argus JVM Observability.
 *
 * <p>Automatically starts JFR streaming and the Argus analysis server
 * when added as a dependency. All configuration is available via
 * {@code argus.*} properties in application.yml.
 *
 * <p>If the application is already running with {@code -javaagent:argus-agent.jar},
 * this auto-configuration is skipped to avoid duplicate JFR streams.
 *
 * @see ArgusProperties
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "argus", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ArgusProperties.class)
public class ArgusAutoConfiguration implements DisposableBean {

    private volatile JfrStreamingEngine engine;
    private volatile ArgusServer server;

    @Bean
    @ConditionalOnMissingBean
    public AgentConfig argusAgentConfig(ArgusProperties props) {
        return AgentConfig.builder()
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
    }

    @Bean
    @ConditionalOnMissingBean
    public ArgusBuffers argusBuffers(AgentConfig config) {
        return new ArgusBuffers(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public JfrStreamingEngine argusJfrEngine(AgentConfig config, ArgusBuffers buffers) {
        engine = new JfrStreamingEngine(
                buffers.eventBuffer(),
                buffers.gcEventBuffer(),
                buffers.cpuEventBuffer(),
                buffers.allocationEventBuffer(),
                buffers.metaspaceEventBuffer(),
                buffers.executionSampleEventBuffer(),
                buffers.contentionEventBuffer(),
                config.isGcEnabled(),
                config.isCpuEnabled(),
                config.getCpuIntervalMs(),
                config.isAllocationEnabled(),
                config.getAllocationThreshold(),
                config.isMetaspaceEnabled(),
                config.isProfilingEnabled(),
                config.getProfilingIntervalMs(),
                config.isContentionEnabled(),
                config.getContentionThresholdMs()
        );
        engine.start();
        return engine;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "argus.server", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ArgusServer argusServer(AgentConfig config, ArgusBuffers buffers) {
        server = new ArgusServer(
                config.getServerPort(),
                buffers.eventBuffer(),
                buffers.gcEventBuffer(),
                buffers.cpuEventBuffer(),
                buffers.allocationEventBuffer(),
                buffers.metaspaceEventBuffer(),
                buffers.executionSampleEventBuffer(),
                buffers.contentionEventBuffer(),
                config.isCorrelationEnabled(),
                config
        );
        Thread.ofPlatform()
                .name("argus-server")
                .daemon(true)
                .start(() -> {
                    try {
                        server.start();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        System.getLogger(ArgusAutoConfiguration.class.getName())
                                .log(System.Logger.Level.ERROR, "Argus server failed to start", e);
                    }
                });
        return server;
    }

    @Override
    public void destroy() {
        if (server != null) {
            server.stop();
        }
        if (engine != null) {
            engine.stop();
        }
    }

    /**
     * Holder for conditionally-created RingBuffers shared between engine and server.
     */
    public static final class ArgusBuffers {

        private final RingBuffer<VirtualThreadEvent> eventBuffer;
        private final RingBuffer<GCEvent> gcEventBuffer;
        private final RingBuffer<CPUEvent> cpuEventBuffer;
        private final RingBuffer<AllocationEvent> allocationEventBuffer;
        private final RingBuffer<MetaspaceEvent> metaspaceEventBuffer;
        private final RingBuffer<ExecutionSampleEvent> executionSampleEventBuffer;
        private final RingBuffer<ContentionEvent> contentionEventBuffer;

        public ArgusBuffers(AgentConfig config) {
            this.eventBuffer = new RingBuffer<>(config.getBufferSize());
            this.gcEventBuffer = config.isGcEnabled() ? new RingBuffer<>(config.getBufferSize()) : null;
            this.cpuEventBuffer = config.isCpuEnabled() ? new RingBuffer<>(config.getBufferSize()) : null;
            this.allocationEventBuffer = config.isAllocationEnabled() ? new RingBuffer<>(config.getBufferSize()) : null;
            this.metaspaceEventBuffer = config.isMetaspaceEnabled() ? new RingBuffer<>(config.getBufferSize()) : null;
            this.executionSampleEventBuffer = config.isProfilingEnabled() ? new RingBuffer<>(config.getBufferSize()) : null;
            this.contentionEventBuffer = config.isContentionEnabled() ? new RingBuffer<>(config.getBufferSize()) : null;
        }

        public RingBuffer<VirtualThreadEvent> eventBuffer() { return eventBuffer; }
        public RingBuffer<GCEvent> gcEventBuffer() { return gcEventBuffer; }
        public RingBuffer<CPUEvent> cpuEventBuffer() { return cpuEventBuffer; }
        public RingBuffer<AllocationEvent> allocationEventBuffer() { return allocationEventBuffer; }
        public RingBuffer<MetaspaceEvent> metaspaceEventBuffer() { return metaspaceEventBuffer; }
        public RingBuffer<ExecutionSampleEvent> executionSampleEventBuffer() { return executionSampleEventBuffer; }
        public RingBuffer<ContentionEvent> contentionEventBuffer() { return contentionEventBuffer; }
    }
}
