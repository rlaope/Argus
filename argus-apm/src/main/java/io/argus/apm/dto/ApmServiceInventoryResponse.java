package io.argus.apm.dto;

import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceSummary;

import java.util.List;
import java.util.Objects;

public record ApmServiceInventoryResponse(ApmScope scope, List<ApmServiceSummary> services) {
    public ApmServiceInventoryResponse {
        Objects.requireNonNull(scope, "scope");
        services = List.copyOf(Objects.requireNonNull(services, "services"));
    }
}
