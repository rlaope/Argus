package io.argus.apm;

import io.argus.apm.dto.ApmBackendLinksRequest;
import io.argus.apm.dto.ApmBackendLinksResponse;
import io.argus.apm.dto.ApmEndpointListResponse;
import io.argus.apm.dto.ApmIncidentListResponse;
import io.argus.apm.dto.ApmServiceDetailResponse;
import io.argus.apm.dto.ApmServiceInventoryResponse;
import io.argus.apm.dto.ApmTraceResponse;
import io.argus.apm.model.ApmPrincipal;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceId;

/**
 * Public APM product facade.
 *
 * <p>Implementations authorize through {@link ApmPrincipal} and {@link ApmScope}
 * before loading data from metrics, trace, log, profile, or internal Argus
 * caches. This interface intentionally exposes APM product DTOs only; raw
 * aggregator v1alpha1 route and model types stay internal.
 */
public interface ApmFacade {
    ApmServiceInventoryResponse listServices(ApmPrincipal principal, ApmScope scope);

    ApmServiceDetailResponse getService(ApmPrincipal principal, ApmScope scope, ApmServiceId service);

    ApmEndpointListResponse listEndpoints(ApmPrincipal principal, ApmScope scope, ApmServiceId service);

    ApmTraceResponse getTrace(ApmPrincipal principal, ApmScope scope, String traceId);

    ApmIncidentListResponse listIncidents(ApmPrincipal principal, ApmScope scope);

    ApmBackendLinksResponse getBackendLinks(ApmPrincipal principal, ApmBackendLinksRequest request);
}
