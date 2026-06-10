package io.argus.apm.dto;

import io.argus.apm.model.ApmIncident;
import io.argus.apm.model.ApmScope;

import java.util.List;
import java.util.Objects;

public record ApmIncidentListResponse(ApmScope scope, List<ApmIncident> incidents) {
    public ApmIncidentListResponse {
        Objects.requireNonNull(scope, "scope");
        incidents = List.copyOf(Objects.requireNonNull(incidents, "incidents"));
    }
}
