package io.argus.apm;

import io.argus.apm.link.ApmBackendLinkContext;
import io.argus.apm.link.ApmBackendLinkRouter;
import io.argus.apm.model.ApmBackendLink;
import io.argus.apm.model.ApmBackendSignal;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceId;
import io.argus.apm.model.ApmTimeRange;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApmBackendLinkRouterTest {
    @Test
    void mvpLinksPreserveScopeAndSignalContext() {
        Instant start = Instant.parse("2026-06-09T00:00:00Z");
        ApmScope scope = ApmScope.environment("tenant-a", "payments", "prod")
                .withService(new ApmServiceId("shop", "checkout"))
                .withDeployment("checkout-7f4c")
                .withInstance("pod-1")
                .withEndpointRoute("/checkout/{id}")
                .withTimeRange(new ApmTimeRange(start, start.plusSeconds(60)));

        ApmBackendLinkRouter router = ApmBackendLinkRouter.mvp(
                URI.create("https://grafana.example"),
                URI.create("https://tempo.example"),
                URI.create("https://loki.example"),
                URI.create("https://pyroscope.example")
        );

        List<ApmBackendLink> links = router.linksFor(new ApmBackendLinkContext(
                scope,
                scope.service(),
                null,
                null,
                null,
                "trace-1",
                "span-1"
        ));

        assertEquals(4, links.size());
        assertTrue(links.stream().anyMatch(link -> link.signal() == ApmBackendSignal.METRICS));
        assertTrue(links.stream().anyMatch(link -> link.label().equals("tempo-or-jaeger")));
        for (ApmBackendLink link : links) {
            String uri = link.uri().toString();
            assertTrue(uri.contains("tenant=tenant-a"), uri);
            assertTrue(uri.contains("project=payments"), uri);
            assertTrue(uri.contains("environment=prod"), uri);
            assertTrue(uri.contains("service=shop%2Fcheckout"), uri);
            assertTrue(uri.contains("traceId=trace-1"), uri);
            assertFalse(ApmFacadeRoutes.isForbiddenAggregatorRoute(link.uri().getPath()), uri);
        }
    }

    @Test
    void canFilterLinksBySignal() {
        ApmScope scope = ApmScope.environment("tenant-a", "payments", "prod");
        ApmBackendLinkRouter router = ApmBackendLinkRouter.mvp(
                URI.create("https://grafana.example"),
                URI.create("https://tempo.example"),
                URI.create("https://loki.example"),
                URI.create("https://pyroscope.example")
        );

        List<ApmBackendLink> traces = router.linksFor(
                ApmBackendSignal.TRACES,
                new ApmBackendLinkContext(scope, null, null, null, null, "trace-1", "span-1")
        );

        assertEquals(1, traces.size());
        assertEquals("tempo-or-jaeger", traces.get(0).label());
    }
}
