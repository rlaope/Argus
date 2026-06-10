package io.argus.apm.dto;

import io.argus.apm.model.ApmBackendSignal;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceId;

import java.util.Objects;

public record ApmBackendLinksRequest(
        ApmScope scope,
        ApmBackendSignal signal,
        ApmServiceId service,
        String instanceId,
        String endpointRoute,
        String traceId,
        String spanId
) {
    public ApmBackendLinksRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(signal, "signal");
    }
}
