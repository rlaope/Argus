package io.argus.apm.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ApmIncident(
        String incidentId,
        ApmIncidentStatus status,
        ApmSeverity severity,
        String title,
        ApmServiceId service,
        Instant startedAt,
        Instant updatedAt,
        List<JvmFinding> findings,
        List<ApmBackendLink> backendLinks
) {
    public ApmIncident {
        incidentId = ApmValidation.requireText(incidentId, "incidentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(severity, "severity");
        title = ApmValidation.requireText(title, "title");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("updatedAt must not be before startedAt");
        }
        findings = ApmValidation.copyList(findings, "findings");
        backendLinks = ApmValidation.copyList(backendLinks, "backendLinks");
    }
}
