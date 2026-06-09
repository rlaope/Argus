package io.argus.apm.model;

import java.time.Instant;
import java.util.Objects;

public record ApmTimeRange(Instant start, Instant end) {
    public ApmTimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end must not be before start");
        }
    }
}
