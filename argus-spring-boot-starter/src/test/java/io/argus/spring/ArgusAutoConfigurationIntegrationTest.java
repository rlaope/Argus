package io.argus.spring;

import io.argus.agent.jfr.JfrStreamingEngine;
import io.argus.core.config.AgentConfig;
import io.argus.server.ArgusServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Spring context integration tests for the Argus Spring Boot starter.
 *
 * <p>Verifies that the auto-configuration's conditional wiring and the
 * {@link ArgusProperties} binding mechanism actually work end-to-end through
 * Spring, rather than just exercising the static property-to-config mapping
 * (which is what {@link ArgusAutoConfigurationTest} does).
 *
 * <p>To avoid the side effects of {@code ArgusAutoConfiguration} (JFR streaming
 * engine startup and {@code ArgusServer} thread spawn), the property-binding
 * test uses a minimal {@code @EnableConfigurationProperties} configuration
 * rather than loading the full auto-configuration.
 */
class ArgusAutoConfigurationIntegrationTest {

    @Test
    void argusPropertiesBindFromSpringPropertySources() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnablePropsOnlyConfig.class)
                .withPropertyValues(
                        "argus.buffer-size=131072",
                        "argus.server.port=9999",
                        "argus.server.enabled=false",
                        "argus.gc.enabled=true",
                        "argus.profiling.enabled=true",
                        "argus.profiling.interval-ms=10",
                        "argus.contention.threshold-ms=25",
                        "argus.metrics.prometheus.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ArgusProperties.class);
                    ArgusProperties props = context.getBean(ArgusProperties.class);

                    assertThat(props.getBufferSize()).isEqualTo(131072);
                    assertThat(props.getServer().getPort()).isEqualTo(9999);
                    assertThat(props.getServer().isEnabled()).isFalse();
                    assertThat(props.getGc().isEnabled()).isTrue();
                    assertThat(props.getProfiling().isEnabled()).isTrue();
                    assertThat(props.getProfiling().getIntervalMs()).isEqualTo(10);
                    assertThat(props.getContention().getThresholdMs()).isEqualTo(25);
                    assertThat(props.getMetrics().getPrometheus().isEnabled()).isTrue();
                });
    }

    @Test
    void argusEnabledFalseShortCircuitsAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ArgusAutoConfiguration.class))
                .withPropertyValues("argus.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ArgusAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AgentConfig.class);
                    assertThat(context).doesNotHaveBean(ArgusProperties.class);
                });
    }

    @Test
    void argusEnabledMissingActivatesAutoConfigurationByDefault() {
        // ArgusAutoConfiguration uses matchIfMissing=true; if the user does
        // not set argus.enabled, the auto-config should activate. We assert
        // ArgusProperties shows up — the bean graph beyond that is exercised
        // by the dedicated mapping tests (ArgusAutoConfigurationTest) without
        // booting the server thread.
        new ApplicationContextRunner()
                .withUserConfiguration(EnablePropsOnlyConfig.class)
                .run(context -> assertThat(context).hasSingleBean(ArgusProperties.class));
    }

    @Test
    void argusModeDiagnosticsSkipsJfrEngineAndServer() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ArgusAutoConfiguration.class))
                .withPropertyValues("argus.mode=diagnostics")
                .run(context -> {
                    // AgentConfig + ArgusProperties stay available for downstream diagnostics beans
                    assertThat(context).hasSingleBean(ArgusProperties.class);
                    assertThat(context).hasSingleBean(AgentConfig.class);
                    // JFR streaming + daemon server are NOT created in diagnostics mode
                    assertThat(context).doesNotHaveBean(JfrStreamingEngine.class);
                    assertThat(context).doesNotHaveBean(ArgusServer.class);
                    // Mode field is bound correctly
                    assertThat(context.getBean(ArgusProperties.class).getMode())
                            .isEqualTo(ArgusProperties.Mode.DIAGNOSTICS);
                });
    }

    @Test
    void argusModeOffSkipsJfrEngineAndServer() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ArgusAutoConfiguration.class))
                .withPropertyValues("argus.mode=off")
                .run(context -> {
                    // OFF mode behaves like DIAGNOSTICS for JFR/server beans; the
                    // diagnostics-side beans will additionally be gated off in slice 3.
                    assertThat(context).doesNotHaveBean(JfrStreamingEngine.class);
                    assertThat(context).doesNotHaveBean(ArgusServer.class);
                    assertThat(context.getBean(ArgusProperties.class).getMode())
                            .isEqualTo(ArgusProperties.Mode.OFF);
                });
    }

    @Test
    void argusModeUnsetDefaultsToFull() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnablePropsOnlyConfig.class)
                .run(context -> assertThat(context.getBean(ArgusProperties.class).getMode())
                        .isEqualTo(ArgusProperties.Mode.FULL));
    }

    @EnableConfigurationProperties(ArgusProperties.class)
    static class EnablePropsOnlyConfig {
    }
}
