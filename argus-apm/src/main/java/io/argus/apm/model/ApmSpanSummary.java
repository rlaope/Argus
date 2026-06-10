package io.argus.apm.model;

import java.time.Instant;
import java.util.Objects;

public record ApmSpanSummary(
        String spanId,
        String parentSpanId,
        ApmServiceId service,
        String name,
        String endpointRoute,
        Instant startTime,
        long durationMillis
) {
    public ApmSpanSummary {
        spanId = ApmValidation.requireText(spanId, "spanId");
        parentSpanId = parentSpanId == null ? "" : parentSpanId;
        Objects.requireNonNull(service, "service");
        name = ApmValidation.requireText(name, "name");
        endpointRoute = endpointRoute == null ? "" : endpointRoute;
        Objects.requireNonNull(startTime, "startTime");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be non-negative");
        }
    }

    public Instant endTime() {
        return startTime.plusMillis(durationMillis);
    }
}
