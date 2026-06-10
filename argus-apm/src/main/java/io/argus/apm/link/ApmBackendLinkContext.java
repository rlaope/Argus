package io.argus.apm.link;

import io.argus.apm.dto.ApmBackendLinksRequest;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceId;

import java.util.Objects;

public record ApmBackendLinkContext(
        ApmScope scope,
        ApmServiceId service,
        String deploymentId,
        String instanceId,
        String endpointRoute,
        String traceId,
        String spanId
) {
    public ApmBackendLinkContext {
        Objects.requireNonNull(scope, "scope");
        service = service != null ? service : scope.service();
        deploymentId = firstNonBlank(deploymentId, scope.deploymentId());
        instanceId = firstNonBlank(instanceId, scope.instanceId());
        endpointRoute = firstNonBlank(endpointRoute, scope.endpointRoute());
        traceId = traceId == null ? "" : traceId;
        spanId = spanId == null ? "" : spanId;
    }

    public static ApmBackendLinkContext from(ApmBackendLinksRequest request) {
        Objects.requireNonNull(request, "request");
        return new ApmBackendLinkContext(
                request.scope(),
                request.service(),
                null,
                request.instanceId(),
                request.endpointRoute(),
                request.traceId(),
                request.spanId()
        );
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }
}
