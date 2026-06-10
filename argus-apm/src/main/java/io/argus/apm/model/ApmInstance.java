package io.argus.apm.model;

import java.time.Instant;
import java.util.Objects;

public record ApmInstance(
        String instanceId,
        ApmServiceId service,
        String deploymentId,
        String namespace,
        String podName,
        String hostId,
        ApmInstanceStatus status,
        Instant lastSeen,
        ApmEntityIdentity identity
) {
    public ApmInstance {
        instanceId = ApmValidation.requireText(instanceId, "instanceId");
        Objects.requireNonNull(service, "service");
        deploymentId = ApmValidation.requireText(deploymentId, "deploymentId");
        namespace = namespace == null ? "" : namespace;
        podName = podName == null ? "" : podName;
        hostId = hostId == null ? "" : hostId;
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastSeen, "lastSeen");
        Objects.requireNonNull(identity, "identity");
    }
}
