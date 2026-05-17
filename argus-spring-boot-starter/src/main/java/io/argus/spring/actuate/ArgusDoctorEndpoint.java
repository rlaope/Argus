package io.argus.spring.actuate;

import io.argus.diagnostics.doctor.DoctorEngine;
import io.argus.diagnostics.doctor.Finding;
import io.argus.spring.diagnostics.DoctorService;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot Actuator endpoint exposing the Argus doctor diagnosis.
 *
 * <p>HTTP path: {@code /actuator/argus-doctor}. Expose via:
 * <pre>
 * management:
 *   endpoints:
 *     web:
 *       exposure:
 *         include: argus-doctor
 * </pre>
 *
 * <p>The response payload is built as an explicit {@link Map} rather than
 * letting Jackson serialize {@link Finding} directly, because {@code Finding}
 * implements {@code JsonWritable} (hand-built JSON) and does not follow
 * Jackson bean conventions — handing it to Jackson would produce a surprising
 * shape (or fail outright on older Jackson versions).
 */
@Endpoint(id = "argus-doctor")
public class ArgusDoctorEndpoint {

    private final DoctorService doctor;

    public ArgusDoctorEndpoint(DoctorService doctor) {
        this.doctor = doctor;
    }

    /**
     * Diagnose the local JVM (the one hosting this Spring application) and
     * return findings grouped with severity histogram + suggested flags.
     *
     * <p>HTTP: {@code GET /actuator/argus-doctor}
     */
    @ReadOperation
    public Map<String, Object> diagnoseLocal() {
        List<Finding> findings = doctor.diagnoseLocal();
        return buildResponse(findings, "local");
    }

    /**
     * Diagnose a remote JVM by PID via {@code jcmd}.
     *
     * <p>HTTP: {@code GET /actuator/argus-doctor/{pid}}
     */
    @ReadOperation
    public Map<String, Object> diagnoseRemote(@Selector long pid) {
        List<Finding> findings = doctor.diagnoseRemote(pid);
        return buildResponse(findings, Long.toString(pid));
    }

    private Map<String, Object> buildResponse(List<Finding> findings, String target) {
        EnumMap<io.argus.diagnostics.doctor.Severity, Integer> severityCount =
                new EnumMap<>(io.argus.diagnostics.doctor.Severity.class);
        for (Finding f : findings) {
            severityCount.merge(f.severity(), 1, Integer::sum);
        }

        List<Map<String, Object>> findingsJson = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("severity", f.severity().name());
            entry.put("category", f.category());
            entry.put("title", f.title());
            entry.put("detail", f.detail());
            entry.put("recommendations", f.recommendations());
            entry.put("suggestedFlags", f.suggestedFlags());
            findingsJson.add(entry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("target", target);
        response.put("exitCode", DoctorEngine.exitCode(findings));
        response.put("findingCount", findings.size());
        response.put("severityCount", severityCount);
        response.put("suggestedFlags", DoctorEngine.collectSuggestedFlags(findings));
        response.put("findings", findingsJson);
        return response;
    }
}
