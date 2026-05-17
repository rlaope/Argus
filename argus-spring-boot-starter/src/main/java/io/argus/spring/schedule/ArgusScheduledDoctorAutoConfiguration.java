package io.argus.spring.schedule;

import io.argus.spring.ArgusDiagnosticsAutoConfiguration;
import io.argus.spring.diagnostics.DoctorService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration that enables Spring scheduling and registers the
 * {@link ArgusScheduledDoctor} bean.
 *
 * <p>Strictly opt-in via {@code argus.doctor.schedule.enabled=true}; when
 * absent or false, this class is inert and {@code @EnableScheduling} does
 * NOT pollute the rest of the application context.
 */
@AutoConfiguration(after = ArgusDiagnosticsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "argus.doctor.schedule", name = "enabled", havingValue = "true")
@ConditionalOnBean(DoctorService.class)
@EnableScheduling
public class ArgusScheduledDoctorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ArgusScheduledDoctor argusScheduledDoctor(DoctorService doctor) {
        return new ArgusScheduledDoctor(doctor);
    }
}
