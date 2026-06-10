package io.argus.apm.demo;

import io.argus.apm.model.ApmBackendLink;
import io.argus.apm.model.ApmIncident;
import io.argus.apm.model.ApmServiceId;
import io.argus.apm.model.ApmTraceContext;
import io.argus.apm.model.JvmFinding;

import java.util.List;
import java.util.Objects;

public record ApmDemoScenario(
        ApmDemoScenarioType type,
        String title,
        ApmServiceId service,
        String method,
        String endpointRoute,
        ApmTraceContext trace,
        ApmIncident incident,
        List<JvmFinding> findings,
        List<ApmBackendLink> backendLinks,
        String localDashboardPath,
        String grafanaPath
) {
    public ApmDemoScenario {
        Objects.requireNonNull(type, "type");
        title = requireText(title, "title");
        Objects.requireNonNull(service, "service");
        method = requireText(method, "method").toUpperCase(java.util.Locale.ROOT);
        endpointRoute = requireText(endpointRoute, "endpointRoute");
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(incident, "incident");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        backendLinks = List.copyOf(Objects.requireNonNull(backendLinks, "backendLinks"));
        localDashboardPath = requireText(localDashboardPath, "localDashboardPath");
        grafanaPath = requireText(grafanaPath, "grafanaPath");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
