package io.argus.apm;

import io.argus.apm.dto.ApmBackendLinksRequest;
import io.argus.apm.dto.ApmEndpointListResponse;
import io.argus.apm.dto.ApmIncidentListResponse;
import io.argus.apm.dto.ApmServiceDetailResponse;
import io.argus.apm.dto.ApmServiceInventoryResponse;
import io.argus.apm.dto.ApmTraceResponse;
import io.argus.apm.model.ApmBackendLink;
import io.argus.apm.model.ApmBackendSignal;
import io.argus.apm.model.ApmDeployment;
import io.argus.apm.model.ApmEndpointSummary;
import io.argus.apm.model.ApmEntityIdentity;
import io.argus.apm.model.ApmFindingKind;
import io.argus.apm.model.ApmHealth;
import io.argus.apm.model.ApmIncident;
import io.argus.apm.model.ApmIncidentStatus;
import io.argus.apm.model.ApmInstance;
import io.argus.apm.model.ApmInstanceStatus;
import io.argus.apm.model.ApmMetadataConflict;
import io.argus.apm.model.ApmMetadataSource;
import io.argus.apm.model.ApmOwner;
import io.argus.apm.model.ApmPrincipal;
import io.argus.apm.model.ApmProfileReference;
import io.argus.apm.model.ApmRole;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceDetail;
import io.argus.apm.model.ApmServiceId;
import io.argus.apm.model.ApmServiceSummary;
import io.argus.apm.model.ApmSeverity;
import io.argus.apm.model.ApmSignalStats;
import io.argus.apm.model.ApmSpanSummary;
import io.argus.apm.model.ApmTimeRange;
import io.argus.apm.model.ApmTraceContext;
import io.argus.apm.model.JvmFinding;
import io.argus.apm.model.RunbookLink;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApmModelTest {
    @Test
    void principalScopesTenantProjectEnvironmentAndServiceAllowlist() {
        ApmServiceId checkout = new ApmServiceId("shop", "checkout");
        ApmPrincipal principal = new ApmPrincipal(
                "user-1",
                "tenant-a",
                "payments",
                Set.of("prod"),
                Set.of(ApmRole.VIEWER),
                Set.of(checkout)
        );

        assertTrue(principal.canRead(ApmScope.environment("tenant-a", "payments", "prod").withService(checkout)));
        assertFalse(principal.canRead(ApmScope.environment("tenant-a", "payments", "dev").withService(checkout)));
        assertFalse(principal.canRead(ApmScope.environment("tenant-a", "payments", "prod")
                .withService(new ApmServiceId("shop", "catalog"))));
        assertThrows(IllegalArgumentException.class,
                () -> new ApmPrincipal("user-1", "tenant-a", "payments", Set.of(), Set.of(ApmRole.VIEWER), Set.of()));
    }

    @Test
    void facadeDtoGraphCoversApmEntities() {
        Instant now = Instant.parse("2026-06-09T00:00:00Z");
        ApmServiceId serviceId = new ApmServiceId("shop", "checkout");
        ApmScope scope = ApmScope.environment("tenant-a", "payments", "prod")
                .withService(serviceId)
                .withTimeRange(new ApmTimeRange(now.minusSeconds(300), now));
        ApmBackendLink metricsLink = new ApmBackendLink(
                ApmBackendSignal.METRICS,
                "prometheus",
                URI.create("https://grafana.example/d/checkout?var-service=checkout"),
                false,
                scope
        );
        ApmEntityIdentity identity = new ApmEntityIdentity(
                "otel:shop/checkout",
                ApmMetadataSource.OPEN_TELEMETRY_RESOURCE,
                Map.of("service.name", "checkout"),
                List.of(new ApmMetadataConflict(
                        "service",
                        "name",
                        ApmMetadataSource.OPEN_TELEMETRY_RESOURCE,
                        "checkout",
                        ApmMetadataSource.KUBERNETES_METADATA,
                        "checkout-v2"
                ))
        );
        JvmFinding finding = new JvmFinding(
                "finding-1",
                ApmFindingKind.GC_PAUSE,
                ApmSeverity.WARNING,
                "GC pause regression",
                "p95 pause exceeded SLO",
                serviceId,
                "pod-1",
                "trace-1",
                "span-1",
                now,
                List.of(metricsLink)
        );
        ApmServiceSummary summary = new ApmServiceSummary(
                serviceId,
                serviceId.displayName(),
                ApmHealth.DEGRADED,
                new ApmOwner("payments-platform", "payments@example.com", "pagerduty/payments"),
                new RunbookLink("GC latency", URI.create("https://runbooks.example/gc")),
                new ApmSignalStats(120.0, 0.02, 20.0, 180.0, 420.0, 95.0, 0.71, 0.64),
                identity,
                List.of(finding),
                List.of(metricsLink)
        );
        ApmDeployment deployment = new ApmDeployment(
                "checkout-7f4c",
                serviceId,
                "2026.06.09",
                "prod",
                now.minusSeconds(3600),
                now,
                ApmHealth.DEGRADED,
                identity
        );
        ApmInstance instance = new ApmInstance(
                "pod-1",
                serviceId,
                "checkout-7f4c",
                "shop",
                "checkout-7f4c-abc",
                "node-a",
                ApmInstanceStatus.DEGRADED,
                now,
                identity
        );
        ApmEndpointSummary endpoint = new ApmEndpointSummary(
                serviceId,
                "get",
                "/checkout/{id}",
                identity,
                ApmSignalStats.empty(),
                List.of(finding),
                List.of(metricsLink)
        );
        ApmProfileReference profile = new ApmProfileReference(
                "profile-1",
                serviceId,
                "pod-1",
                "allocation",
                now.minusSeconds(60),
                now,
                metricsLink
        );
        ApmServiceDetail detail = new ApmServiceDetail(
                summary,
                List.of(deployment),
                List.of(instance),
                List.of(endpoint),
                List.of(profile)
        );
        ApmTraceContext trace = new ApmTraceContext(
                "trace-1",
                serviceId,
                "span-1",
                now.minusSeconds(5),
                120,
                ApmHealth.DEGRADED,
                List.of(new ApmSpanSummary("span-1", "", serviceId, "GET /checkout/{id}", "/checkout/{id}", now, 120)),
                List.of(finding),
                List.of(profile),
                List.of(metricsLink)
        );
        ApmIncident incident = new ApmIncident(
                "incident-1",
                ApmIncidentStatus.OPEN,
                ApmSeverity.WARNING,
                "Checkout latency regression",
                serviceId,
                now.minusSeconds(120),
                now,
                List.of(finding),
                List.of(metricsLink)
        );

        assertEquals(1, new ApmServiceInventoryResponse(scope, List.of(summary)).services().size());
        assertEquals(serviceId, new ApmServiceDetailResponse(scope, detail).service().summary().service());
        assertEquals("/checkout/{id}", new ApmEndpointListResponse(scope, serviceId, List.of(endpoint)).endpoints().get(0).route());
        assertEquals("trace-1", new ApmTraceResponse(scope, trace).trace().traceId());
        assertEquals(ApmIncidentStatus.OPEN, new ApmIncidentListResponse(scope, List.of(incident)).incidents().get(0).status());
        assertEquals(ApmBackendSignal.METRICS, new ApmBackendLinksRequest(scope, ApmBackendSignal.METRICS,
                serviceId, "pod-1", "/checkout/{id}", "trace-1", "span-1").signal());
    }
}
