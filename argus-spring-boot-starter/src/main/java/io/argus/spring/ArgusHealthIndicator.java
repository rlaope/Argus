package io.argus.spring;

import io.argus.agent.jfr.JfrStreamingEngine;
import io.argus.server.ArgusServer;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator health indicator for Argus.
 *
 * <p>Reports the status of the JFR streaming engine and analysis server
 * at {@code /actuator/health/argus}.
 */
@Component("argusHealthIndicator")
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(JfrStreamingEngine.class)
public class ArgusHealthIndicator implements HealthIndicator {

    private final JfrStreamingEngine engine;
    private final ArgusServer server;

    public ArgusHealthIndicator(JfrStreamingEngine engine, ArgusServer server) {
        this.engine = engine;
        this.server = server;
    }

    @Override
    public Health health() {
        boolean engineRunning = engine.isRunning();
        boolean serverRunning = server != null && server.isRunning();

        Health.Builder builder = engineRunning ? Health.up() : Health.down();

        builder.withDetail("jfrEngine", engineRunning ? "running" : "stopped")
               .withDetail("server", serverRunning ? "running" : "stopped");

        if (serverRunning) {
            builder.withDetail("connectedClients", server.getClientCount())
                   .withDetail("totalEvents", server.getMetrics().getTotalEvents())
                   .withDetail("activeVirtualThreads", server.getActiveThreads().size());
        }

        return builder.build();
    }
}
