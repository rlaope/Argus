package io.argus.apm.dto;

import io.argus.apm.model.ApmEndpointSummary;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceId;

import java.util.List;
import java.util.Objects;

public record ApmEndpointListResponse(
        ApmScope scope,
        ApmServiceId service,
        List<ApmEndpointSummary> endpoints
) {
    public ApmEndpointListResponse {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(service, "service");
        endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
    }
}
