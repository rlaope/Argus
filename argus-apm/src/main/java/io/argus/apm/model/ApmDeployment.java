package io.argus.apm.model;

import java.time.Instant;
import java.util.Objects;

public record ApmDeployment(
        String deploymentId,
        ApmServiceId service,
        String version,
        String environment,
        Instant firstSeen,
        Instant lastSeen,
        ApmHealth health,
        ApmEntityIdentity identity
) {
    public ApmDeployment {
        deploymentId = ApmValidation.requireText(deploymentId, "deploymentId");
        Objects.requireNonNull(service, "service");
        version = version == null ? "" : version;
        environment = ApmValidation.requireText(environment, "environment");
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen, "lastSeen");
        if (lastSeen.isBefore(firstSeen)) {
            throw new IllegalArgumentException("lastSeen must not be before firstSeen");
        }
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(identity, "identity");
    }
}
