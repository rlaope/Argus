package io.argus.aggregator;

import io.argus.aggregator.http.FleetRightsizeComputer;
import io.argus.aggregator.http.JsonWriter;
import io.argus.aggregator.model.FleetRightsize;
import io.argus.aggregator.model.MetricSample;
import io.argus.aggregator.store.FleetRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FleetRightsizeComputerTest {

    private static void sample(FleetRegistry reg, String podId, double heapPercent) {
        reg.buffer(podId).append(new MetricSample(
                Instant.now(), heapPercent, 2.0, 10.0, 0L));
    }

    @Test
    void overProvisionedDeploymentShowsSavings() {
        FleetRegistry reg = new FleetRegistry(3600);
        reg.register("prod/p-1", "prod", "p-1", "payment", "10.0.0.1", 7070);
        reg.register("prod/p-2", "prod", "p-2", "payment", "10.0.0.2", 7070);
        // Peak observed heap across the deployment is 40% of current -Xmx.
        sample(reg, "prod/p-1", 30.0);
        sample(reg, "prod/p-1", 40.0);
        sample(reg, "prod/p-2", 25.0);

        FleetRightsize r = FleetRightsizeComputer.compute(reg);
        assertEquals(1, r.deployments().size());
        FleetRightsize.DeploymentRow row = r.deployments().get(0);
        assertEquals("payment", row.deployment());
        assertEquals(2, row.podCount());
        assertEquals(40.0, row.peakHeapPercent());
        // Recommended = 40 * 1.5 = 60% of current -Xmx -> 40% savings available.
        assertEquals(60.0, row.recommendedHeapPercent());
        assertEquals(40.0, row.savingsPercent());
        assertEquals(40.0, r.aggregateSavingsPercent());
    }

    @Test
    void hotDeploymentRecommendsNoSavings() {
        FleetRegistry reg = new FleetRegistry(3600);
        reg.register("prod/h-1", "prod", "h-1", "search", "10.0.1.1", 7070);
        // Peak 80% -> 80*1.5=120 capped at 100% -> 0 savings.
        sample(reg, "prod/h-1", 70.0);
        sample(reg, "prod/h-1", 80.0);

        FleetRightsize r = FleetRightsizeComputer.compute(reg);
        FleetRightsize.DeploymentRow row = r.deployments().get(0);
        assertEquals(80.0, row.peakHeapPercent());
        assertEquals(100.0, row.recommendedHeapPercent());
        assertEquals(0.0, row.savingsPercent());
    }

    @Test
    void deploymentWithoutSamplesReportsNullsAndNoSavings() {
        FleetRegistry reg = new FleetRegistry(3600);
        reg.register("prod/n-1", "prod", "n-1", "nodata", "10.0.2.1", 7070);

        FleetRightsize r = FleetRightsizeComputer.compute(reg);
        FleetRightsize.DeploymentRow row = r.deployments().get(0);
        assertNull(row.peakHeapPercent());
        assertNull(row.recommendedHeapPercent());
        assertEquals(0.0, row.savingsPercent());
        // No data => no contribution to the aggregate.
        assertEquals(0.0, r.aggregateSavingsPercent());
    }

    @Test
    void jsonShapeIncludesSafetyFactorAndDeployments() {
        FleetRegistry reg = new FleetRegistry(3600);
        reg.register("prod/p-1", "prod", "p-1", "payment", "10.0.0.1", 7070);
        sample(reg, "prod/p-1", 40.0);

        String json = JsonWriter.rightsize(FleetRightsizeComputer.compute(reg));
        assertTrue(json.contains("\"rightsize\""));
        assertTrue(json.contains("\"safetyFactor\":1.5"));
        assertTrue(json.contains("\"deployment\":\"payment\""));
        assertTrue(json.contains("\"peakHeapPercent\":40"));
        assertTrue(json.contains("\"savingsPercent\""));
        assertTrue(json.contains("\"aggregateSavingsPercent\""));
    }
}
