package io.argus.apm.model;

public record ApmScope(
        String tenant,
        String project,
        String environment,
        ApmServiceId service,
        String deploymentId,
        String instanceId,
        String endpointRoute,
        ApmTimeRange timeRange
) {
    public ApmScope {
        tenant = ApmValidation.requireText(tenant, "tenant");
        project = ApmValidation.requireText(project, "project");
        environment = ApmValidation.requireText(environment, "environment");
        deploymentId = optionalText(deploymentId, "deploymentId");
        instanceId = optionalText(instanceId, "instanceId");
        endpointRoute = optionalText(endpointRoute, "endpointRoute");
    }

    public static ApmScope environment(String tenant, String project, String environment) {
        return new ApmScope(tenant, project, environment, null, null, null, null, null);
    }

    public ApmScope withService(ApmServiceId service) {
        return new ApmScope(tenant, project, environment, service, deploymentId, instanceId, endpointRoute, timeRange);
    }

    public ApmScope withDeployment(String deploymentId) {
        return new ApmScope(tenant, project, environment, service, deploymentId, instanceId, endpointRoute, timeRange);
    }

    public ApmScope withInstance(String instanceId) {
        return new ApmScope(tenant, project, environment, service, deploymentId, instanceId, endpointRoute, timeRange);
    }

    public ApmScope withEndpointRoute(String endpointRoute) {
        return new ApmScope(tenant, project, environment, service, deploymentId, instanceId, endpointRoute, timeRange);
    }

    public ApmScope withTimeRange(ApmTimeRange timeRange) {
        return new ApmScope(tenant, project, environment, service, deploymentId, instanceId, endpointRoute, timeRange);
    }

    private static String optionalText(String value, String name) {
        if (value == null) {
            return null;
        }
        return ApmValidation.requireText(value, name);
    }
}
