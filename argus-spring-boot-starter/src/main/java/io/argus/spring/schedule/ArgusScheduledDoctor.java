package io.argus.spring.schedule;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.Severity;
import io.argus.spring.diagnostics.DoctorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * Background doctor that periodically runs {@link DoctorService#diagnoseLocal()}
 * and emits one structured slf4j log line per finding.
 *
 * <p>Opt-in via {@code argus.doctor.schedule.enabled=true}. Default interval
 * is 60 s, configurable via {@code argus.doctor.schedule.interval-ms}.
 *
 * <p>Severity is mapped to log level:
 * <ul>
 *   <li>{@link Severity#CRITICAL} → {@code ERROR}</li>
 *   <li>{@link Severity#WARNING}  → {@code WARN}</li>
 *   <li>{@link Severity#INFO}     → {@code INFO}</li>
 * </ul>
 *
 * <p>Log format uses {@code key=value} pairs so it is parseable by
 * Loki / Datadog / Vector / Logstash without custom regex per field:
 *
 * <pre>
 * argus.doctor severity=CRITICAL category=GC title="..." flag1=... flag2=...
 * </pre>
 */
public class ArgusScheduledDoctor {

    private static final Logger LOG = LoggerFactory.getLogger("argus.doctor");

    private final DoctorService doctor;

    public ArgusScheduledDoctor(DoctorService doctor) {
        this.doctor = doctor;
    }

    /**
     * Scheduled entry point. Delay is read from
     * {@code argus.doctor.schedule.interval-ms} (defaults to 60 000 ms).
     *
     * <p>Spring's {@code @Scheduled} swallows {@link Throwable}s so a failing
     * diagnosis run won't kill the scheduler — but we still log the failure
     * so it doesn't go silent.
     */
    @Scheduled(fixedDelayString = "${argus.doctor.schedule.interval-ms:60000}")
    public void runOnce() {
        try {
            List<Finding> findings = doctor.diagnoseLocal();
            emit(findings);
        } catch (RuntimeException e) {
            LOG.error("argus.doctor run failed: {}", e.toString(), e);
        }
    }

    /** Package-private for direct invocation from tests. */
    void emit(List<Finding> findings) {
        for (Finding f : findings) {
            String line = "argus.doctor"
                    + " severity=" + f.severity().name()
                    + " category=" + f.category()
                    + " title=\"" + escape(f.title()) + "\"";

            switch (f.severity()) {
                case CRITICAL -> LOG.error(line);
                case WARNING  -> LOG.warn(line);
                case INFO     -> LOG.info(line);
            }
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
