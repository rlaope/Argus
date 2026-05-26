package io.argus.aggregator;

import io.argus.aggregator.model.AlertEvent;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.store.FleetRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FleetRegistryTest {

    @Test
    void registerCreatesNewTarget() {
        FleetRegistry reg = new FleetRegistry(60);
        var result = reg.register("prod/p-1", "prod", "p-1", "payment", "10.0.0.1", 7070);
        assertFalse(result.updated());
        assertEquals("prod/p-1", result.target().podId());
        assertEquals("http://10.0.0.1:7070", result.target().scrapeUrl());
    }

    @Test
    void registerSecondTimeIsUpdate() {
        FleetRegistry reg = new FleetRegistry(60);
        reg.register("prod/p-1", "prod", "p-1", "payment", "10.0.0.1", 7070);
        var result = reg.register("prod/p-1", "prod", "p-1", "payment", "10.0.0.2", 8080);
        assertTrue(result.updated());
        assertEquals("http://10.0.0.2:8080", result.target().scrapeUrl());
    }

    @Test
    void deregisterDropsTargetAndBuffer() {
        FleetRegistry reg = new FleetRegistry(60);
        reg.register("prod/p-1", "prod", "p-1", "payment", "10.0.0.1", 7070);
        assertNotNull(reg.buffer("prod/p-1"));
        reg.deregister("prod/p-1");
        assertNull(reg.get("prod/p-1"));
        assertNull(reg.buffer("prod/p-1"));
    }

    @Test
    void recordScrapeUpdatesTargetAndMetrics() {
        FleetRegistry reg = new FleetRegistry(60);
        reg.register("prod/p-1", "prod", "p-1", "payment", "10.0.0.1", 7070);
        TileMetrics m = new TileMetrics(50.0, 1.0, 30.0, 100, false);
        reg.recordScrape("prod/p-1", m, true);
        PodTarget t = reg.get("prod/p-1");
        assertTrue(t.scrapeOk());
        assertNotNull(t.lastScrapeAt());
        assertEquals(m, reg.latestMetrics("prod/p-1"));
    }

    @Test
    void alertTrackingByPod() {
        FleetRegistry reg = new FleetRegistry(60);
        reg.register("prod/p-1", "prod", "p-1", "payment", "10.0.0.1", 7070);
        reg.register("prod/p-2", "prod", "p-2", "payment", "10.0.0.2", 7070);
        var a1 = new AlertEvent("prod/p-1/gc", "prod/p-1", "gc",
                "argus_gc_overhead_ratio", 0.2, 0.1, ">", "warning", Instant.now(), true);
        var a2 = new AlertEvent("prod/p-2/heap", "prod/p-2", "heap",
                "argus_heap_used_percent", 95, 90, ">", "critical", Instant.now(), true);
        reg.recordAlert(a1);
        reg.recordAlert(a2);
        assertEquals(2, reg.activeAlerts().size());
        assertEquals(1, reg.alertCountForPod("prod/p-1"));
        assertEquals(1, reg.activeAlertsForPod("prod/p-2").size());

        reg.clearAlert("prod/p-1/gc");
        assertEquals(1, reg.activeAlerts().size());
        assertEquals(0, reg.alertCountForPod("prod/p-1"));
    }

    @Test
    void deregisterClearsAssociatedAlerts() {
        FleetRegistry reg = new FleetRegistry(60);
        reg.register("prod/p-1", "prod", "p-1", "payment", "10.0.0.1", 7070);
        reg.recordAlert(new AlertEvent("prod/p-1/gc", "prod/p-1", "gc",
                "m", 1, 0, ">", "warning", Instant.now(), true));
        reg.deregister("prod/p-1");
        assertEquals(0, reg.activeAlerts().size());
    }
}
