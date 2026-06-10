package io.argus.apm.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record JvmFinding(
        String findingId,
        ApmFindingKind kind,
        ApmSeverity severity,
        String title,
        String detail,
        ApmServiceId service,
        String instanceId,
        String traceId,
        String spanId,
        Instant observedAt,
        List<ApmBackendLink> backendLinks
) {
    public JvmFinding {
        findingId = ApmValidation.requireText(findingId, "findingId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        title = ApmValidation.requireText(title, "title");
        detail = detail == null ? "" : detail;
        Objects.requireNonNull(service, "service");
        instanceId = instanceId == null ? "" : instanceId;
        traceId = traceId == null ? "" : traceId;
        spanId = spanId == null ? "" : spanId;
        Objects.requireNonNull(observedAt, "observedAt");
        backendLinks = ApmValidation.copyList(backendLinks, "backendLinks");
    }
}
