package io.argus.spring.actuate;

import io.argus.spring.ArgusDiagnosticsAutoConfiguration;
import io.argus.spring.ArgusProperties;
import io.argus.spring.diagnostics.DoctorService;
import io.argus.spring.diagnostics.GcLogAnalyzerService;
import io.argus.spring.diagnostics.GcScoreService;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers Spring Boot Actuator endpoints for the Argus diagnostics:
 *
 * <ul>
 *   <li>{@code /actuator/argus-doctor} — JVM health diagnosis</li>
 *   <li>{@code /actuator/argus-gc}     — GC log analysis + score</li>
 * </ul>
 *
 * <p>Activated when Spring Boot Actuator's {@code @Endpoint} annotation is
 * on the classpath and the {@link DoctorService} bean exists (which means
 * {@link ArgusDiagnosticsAutoConfiguration} ran first).
 *
 * <p>Users must opt in via {@code management.endpoints.web.exposure.include}
 * — the standard Actuator gate. The endpoints themselves are registered
 * unconditionally so {@code /actuator} discovery shows them.
 */
@AutoConfiguration(after = ArgusDiagnosticsAutoConfiguration.class)
@ConditionalOnClass(Endpoint.class)
@ConditionalOnBean(DoctorService.class)
public class ArgusActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ArgusDoctorEndpoint argusDoctorEndpoint(DoctorService doctorService) {
        return new ArgusDoctorEndpoint(doctorService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({GcLogAnalyzerService.class, GcScoreService.class})
    public ArgusGcLogEndpoint argusGcLogEndpoint(GcLogAnalyzerService analyzer,
                                                 GcScoreService scorer,
                                                 ArgusProperties properties) {
        return new ArgusGcLogEndpoint(analyzer, scorer, properties);
    }
}
