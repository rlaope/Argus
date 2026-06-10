package io.argus.apm;

import io.argus.apm.demo.ApmDemoCatalog;
import io.argus.apm.demo.ApmDemoScenario;
import io.argus.apm.demo.ApmDemoScenarioType;
import io.argus.apm.demo.ApmDemoTopology;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApmDemoCatalogTest {
    @Test
    void defaultTopologyCoversRequiredE2eIncidentScenarios() {
        ApmDemoTopology topology = ApmDemoCatalog.defaultTopology();

        assertEquals(Set.of(
                ApmDemoScenarioType.GC_LATENCY,
                ApmDemoScenarioType.LOCK_CONTENTION,
                ApmDemoScenarioType.VIRTUAL_THREAD_PINNING,
                ApmDemoScenarioType.BAD_RELEASE_REGRESSION
        ), topology.scenarios().stream().map(ApmDemoScenario::type).collect(Collectors.toSet()));
        assertEquals(2, topology.services().size());
        assertEquals(4, topology.incidents().size());
        assertEquals(4, topology.traces().size());
        assertTrue(topology.services().stream()
                .flatMap(service -> service.endpoints().stream())
                .anyMatch(endpoint -> endpoint.method().equals("POST") && endpoint.route().equals("/session")));
    }

    @Test
    void eachScenarioHasTraceIncidentFindingAndSafeDrilldowns() {
        for (ApmDemoScenario scenario : ApmDemoCatalog.defaultTopology().scenarios()) {
            assertFalse(scenario.findings().isEmpty(), scenario.type().name());
            assertFalse(scenario.backendLinks().isEmpty(), scenario.type().name());
            assertEquals(scenario.trace().traceId(), scenario.findings().get(0).traceId());
            assertEquals(scenario.incident().findings().get(0), scenario.findings().get(0));
            assertTrue(scenario.localDashboardPath().startsWith("/?service="));
            assertTrue(scenario.grafanaPath().contains("tenant=tenant-a"));
            scenario.backendLinks().forEach(link ->
                    assertFalse(ApmFacadeRoutes.isForbiddenAggregatorRoute(link.uri().getPath()), link.uri().toString()));
        }
    }
}
