package io.argus.spring;

import io.argus.spring.diagnostics.DoctorService;
import io.argus.spring.diagnostics.GcLogAnalyzerService;
import io.argus.spring.diagnostics.GcScoreService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Programmatic-API auto-configuration: exposes the diagnostics library
 * (doctor / GC log / GC score) as Spring beans so application code can
 * {@code @Autowired} them.
 *
 * <p>Active in both {@code argus.mode=full} and {@code argus.mode=diagnostics}
 * — only {@code argus.mode=off} or {@code argus.enabled=false} disables it.
 */
@AutoConfiguration(after = ArgusAutoConfiguration.class)
@ConditionalOnProperty(prefix = "argus", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${argus.mode:FULL}'.toUpperCase() != 'OFF'")
@EnableConfigurationProperties(ArgusProperties.class)
public class ArgusDiagnosticsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DoctorService argusDoctorService() {
        return new DoctorService();
    }

    @Bean
    @ConditionalOnMissingBean
    public GcLogAnalyzerService argusGcLogAnalyzerService() {
        return new GcLogAnalyzerService();
    }

    @Bean
    @ConditionalOnMissingBean
    public GcScoreService argusGcScoreService() {
        return new GcScoreService();
    }
}
