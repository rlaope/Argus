package io.argus.apm.dto;

import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceDetail;

import java.util.Objects;

public record ApmServiceDetailResponse(ApmScope scope, ApmServiceDetail service) {
    public ApmServiceDetailResponse {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(service, "service");
    }
}
