package io.argus.apm.model;

import java.time.Instant;
import java.util.Objects;

public record ApmProfileReference(
        String profileId,
        ApmServiceId service,
        String instanceId,
        String profileType,
        Instant startTime,
        Instant endTime,
        ApmBackendLink backendLink
) {
    public ApmProfileReference {
        profileId = ApmValidation.requireText(profileId, "profileId");
        Objects.requireNonNull(service, "service");
        instanceId = ApmValidation.requireText(instanceId, "instanceId");
        profileType = ApmValidation.requireText(profileType, "profileType");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        if (endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("endTime must not be before startTime");
        }
        Objects.requireNonNull(backendLink, "backendLink");
    }
}
