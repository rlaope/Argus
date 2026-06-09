package io.argus.apm.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ApmTraceContext(
        String traceId,
        ApmServiceId rootService,
        String rootSpanId,
        Instant startTime,
        long durationMillis,
        ApmHealth health,
        List<ApmSpanSummary> spans,
        List<JvmFinding> findings,
        List<ApmProfileReference> profiles,
        List<ApmBackendLink> backendLinks
) {
    public ApmTraceContext {
        traceId = ApmValidation.requireText(traceId, "traceId");
        Objects.requireNonNull(rootService, "rootService");
        rootSpanId = ApmValidation.requireText(rootSpanId, "rootSpanId");
        Objects.requireNonNull(startTime, "startTime");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be non-negative");
        }
        Objects.requireNonNull(health, "health");
        spans = ApmValidation.copyList(spans, "spans");
        findings = ApmValidation.copyList(findings, "findings");
        profiles = ApmValidation.copyList(profiles, "profiles");
        backendLinks = ApmValidation.copyList(backendLinks, "backendLinks");
    }
}
