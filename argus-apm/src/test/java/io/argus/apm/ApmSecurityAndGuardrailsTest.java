package io.argus.apm;

import io.argus.apm.dto.ApmBackendLinksRequest;
import io.argus.apm.guard.ApmEndpointCardinalityGuard;
import io.argus.apm.guard.ApmOverheadBudget;
import io.argus.apm.guard.ApmRouteNormalization;
import io.argus.apm.metrics.ApmSelfMetrics;
import io.argus.apm.model.ApmBackendSignal;
import io.argus.apm.model.ApmPrincipal;
import io.argus.apm.model.ApmRole;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceId;
import io.argus.apm.security.ApmAuthorizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApmSecurityAndGuardrailsTest {
    @Test
    void authorizerFailsClosedAcrossTenantProjectEnvironmentAndService() {
        ApmServiceId checkout = new ApmServiceId("shop", "checkout");
        ApmPrincipal principal = new ApmPrincipal(
                "user-1", "tenant-a", "payments", Set.of("prod"),
                Set.of(ApmRole.VIEWER), Set.of(checkout));

        assertTrue(ApmAuthorizer.authorizeRead(principal,
                ApmScope.environment("tenant-a", "payments", "prod").withService(checkout)).allowed());
        assertFalse(ApmAuthorizer.authorizeRead(null,
                ApmScope.environment("tenant-a", "payments", "prod")).allowed());
        assertFalse(ApmAuthorizer.authorizeRead(principal,
                ApmScope.environment("tenant-a", "payments", "staging").withService(checkout)).allowed());
        assertFalse(ApmAuthorizer.authorizeRead(principal,
                ApmScope.environment("tenant-a", "payments", "prod")
                        .withService(new ApmServiceId("shop", "catalog"))).allowed());
    }

    @Test
    void backendLinkAuthorizationBindsRequestServiceToScope() {
        ApmServiceId checkout = new ApmServiceId("shop", "checkout");
        ApmServiceId catalog = new ApmServiceId("shop", "catalog");
        ApmPrincipal principal = new ApmPrincipal(
                "user-1", "tenant-a", "payments", Set.of("prod"),
                Set.of(ApmRole.VIEWER), Set.of(checkout));
        ApmScope environmentScope = ApmScope.environment("tenant-a", "payments", "prod");

        assertTrue(ApmAuthorizer.authorizeBackendLinks(principal,
                new ApmBackendLinksRequest(
                        environmentScope, ApmBackendSignal.METRICS, checkout,
                        null, null, null, null)).allowed());
        assertFalse(ApmAuthorizer.authorizeBackendLinks(principal,
                new ApmBackendLinksRequest(
                        environmentScope, ApmBackendSignal.METRICS, catalog,
                        null, null, null, null)).allowed());
        assertFalse(ApmAuthorizer.authorizeBackendLinks(principal,
                new ApmBackendLinksRequest(
                        environmentScope.withService(checkout), ApmBackendSignal.METRICS, catalog,
                        null, null, null, null)).allowed());
        assertFalse(ApmAuthorizer.authorizeBackendLinks(principal, null).allowed());
    }

    @Test
    void endpointCardinalityGuardNormalizesIdsAndRejectsRunawayRoutes() {
        ApmRouteNormalization normalized = ApmEndpointCardinalityGuard.normalize(
                "GET", "/orders/1234567890/items/8f14e45fceea167a5a36dedd4bea2543?debug=true");

        assertEquals("GET", normalized.method());
        assertEquals("/orders/{id}/items/{id}", normalized.route());
        assertTrue(normalized.normalized());
        assertThrows(IllegalArgumentException.class,
                () -> ApmEndpointCardinalityGuard.normalize("GET", "/a/b/c/d/e/f/g/h/i/j/k/l/m"));
    }

    @Test
    void overheadBudgetStopsCardinalityAndFanoutBlowups() {
        ApmOverheadBudget budget = new ApmOverheadBudget(10, 20, 5, 4);

        assertTrue(budget.validate(10, 20, 5, 4).allowed());
        assertEquals("endpoints_limit", budget.validate(10, 21, 5, 4).code());
        assertEquals("backend_links_limit", budget.validate(10, 20, 5, 5).code());
    }

    @Test
    void selfMetricsExposeFacadeGuardrailCounters() {
        ApmSelfMetrics metrics = new ApmSelfMetrics();
        metrics.recordFacadeRequest(20, false);
        metrics.recordFacadeRequest(40, true);
        metrics.recordAuthorizationDenied();
        metrics.recordRouteRejected();
        metrics.recordBackendLinksGenerated(5);

        ApmSelfMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.facadeRequests());
        assertEquals(1, snapshot.facadeErrors());
        assertEquals(30.0, snapshot.averageLatencyMillis());
        assertTrue(metrics.toPrometheusText().contains("argus_apm_authorization_denied_total 1"));
        assertTrue(metrics.toPrometheusText().contains("argus_apm_backend_links_generated_total 5"));
    }

    @Test
    void apmModuleHasNoAggregatorDependencyOrImports() throws Exception {
        assertFalse(readRepoFile("argus-apm/build.gradle.kts").contains("argus-aggregator"));
        String source = allJavaSource("argus-apm/src/main/java");
        assertFalse(source.contains("import io.argus.aggregator"));
        assertFalse(source.contains("project(\":argus-aggregator\")"));
    }

    private static String allJavaSource(String relativeDir) throws IOException {
        Path dir = repoRoot().resolve(relativeDir);
        StringBuilder out = new StringBuilder();
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                out.append(Files.readString(path)).append('\n');
            }
        }
        return out.toString();
    }

    private static String readRepoFile(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative));
    }

    private static Path repoRoot() throws IOException {
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path root = userDir; root != null; root = root.getParent()) {
            if (Files.exists(root.resolve("settings.gradle.kts"))) {
                return root;
            }
        }
        throw new IOException("Unable to find repo root from " + userDir);
    }
}
