package io.argus.apm.model;

import java.util.List;
import java.util.Objects;

public record ApmEndpointSummary(
        ApmServiceId service,
        String method,
        String route,
        ApmEntityIdentity identity,
        ApmSignalStats signals,
        List<JvmFinding> findings,
        List<ApmBackendLink> backendLinks
) {
    public ApmEndpointSummary {
        Objects.requireNonNull(service, "service");
        method = ApmValidation.requireText(method, "method").toUpperCase();
        route = ApmValidation.requireText(route, "route");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(signals, "signals");
        findings = ApmValidation.copyList(findings, "findings");
        backendLinks = ApmValidation.copyList(backendLinks, "backendLinks");
    }
}
