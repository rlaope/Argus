package io.argus.apm.model;

import java.util.List;
import java.util.Objects;

public record ApmServiceSummary(
        ApmServiceId service,
        String displayName,
        ApmHealth health,
        ApmOwner owner,
        RunbookLink runbook,
        ApmSignalStats signals,
        ApmEntityIdentity identity,
        List<JvmFinding> findings,
        List<ApmBackendLink> backendLinks
) {
    public ApmServiceSummary {
        Objects.requireNonNull(service, "service");
        displayName = ApmValidation.requireText(displayName, "displayName");
        Objects.requireNonNull(health, "health");
        owner = owner == null ? ApmOwner.unassigned() : owner;
        Objects.requireNonNull(signals, "signals");
        Objects.requireNonNull(identity, "identity");
        findings = ApmValidation.copyList(findings, "findings");
        backendLinks = ApmValidation.copyList(backendLinks, "backendLinks");
    }
}
