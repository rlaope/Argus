package io.argus.apm.model;

import java.util.List;
import java.util.Objects;

public record ApmServiceDetail(
        ApmServiceSummary summary,
        List<ApmDeployment> deployments,
        List<ApmInstance> instances,
        List<ApmEndpointSummary> endpoints,
        List<ApmProfileReference> profiles
) {
    public ApmServiceDetail {
        Objects.requireNonNull(summary, "summary");
        deployments = ApmValidation.copyList(deployments, "deployments");
        instances = ApmValidation.copyList(instances, "instances");
        endpoints = ApmValidation.copyList(endpoints, "endpoints");
        profiles = ApmValidation.copyList(profiles, "profiles");
    }
}
