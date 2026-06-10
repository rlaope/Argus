package io.argus.apm.dto;

import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmTraceContext;

import java.util.Objects;

public record ApmTraceResponse(ApmScope scope, ApmTraceContext trace) {
    public ApmTraceResponse {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(trace, "trace");
    }
}
