package io.argus.aggregator;

import io.argus.aggregator.http.TileBuilder;
import io.argus.aggregator.model.AlertEvent;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.Tile;
import io.argus.aggregator.model.TileColor;
import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.store.FleetRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TileBuilderTest {

    private FleetRegistry registry(boolean scraped, boolean scrapeOk) {
        FleetRegistry r = new FleetRegistry(60);
        r.register("p", "p", "p", "d", "h", 7070);
        if (scraped) {
            r.recordScrape("p", new TileMetrics(50.0, 1.0, 30.0, 100, false), scrapeOk);
        }
        return r;
    }

    @Test
    void greyWhenNeverScraped() {
        FleetRegistry r = registry(false, false);
        Tile tile = TileBuilder.build(r.get("p"), r);
        assertEquals(TileColor.GREY, tile.color());
    }

    @Test
    void greyWhenLastScrapeFailed() {
        FleetRegistry r = registry(true, false);
        Tile tile = TileBuilder.build(r.get("p"), r);
        assertEquals(TileColor.GREY, tile.color());
    }

    @Test
    void greenWhenHealthy() {
        FleetRegistry r = registry(true, true);
        Tile tile = TileBuilder.build(r.get("p"), r);
        assertEquals(TileColor.GREEN, tile.color());
    }

    @Test
    void redWhenHeapHigh() {
        FleetRegistry r = new FleetRegistry(60);
        r.register("p", "p", "p", "d", "h", 7070);
        r.recordScrape("p", new TileMetrics(95.0, 1.0, 30.0, 100, false), true);
        Tile tile = TileBuilder.build(r.get("p"), r);
        assertEquals(TileColor.RED, tile.color());
    }

    @Test
    void yellowWhenHeapElevated() {
        FleetRegistry r = new FleetRegistry(60);
        r.register("p", "p", "p", "d", "h", 7070);
        r.recordScrape("p", new TileMetrics(85.0, 1.0, 30.0, 100, false), true);
        Tile tile = TileBuilder.build(r.get("p"), r);
        assertEquals(TileColor.YELLOW, tile.color());
    }

    @Test
    void redWhenCriticalAlertFiring() {
        FleetRegistry r = registry(true, true);
        r.recordAlert(new AlertEvent("p/leak", "p", "leak",
                "m", 1, 0, ">", "critical", Instant.now(), true));
        Tile tile = TileBuilder.build(r.get("p"), r);
        assertEquals(TileColor.RED, tile.color());
        assertEquals(1, tile.alertCount());
    }

    @Test
    void redWhenLeakSuspected() {
        FleetRegistry r = new FleetRegistry(60);
        r.register("p", "p", "p", "d", "h", 7070);
        r.recordScrape("p", new TileMetrics(50.0, 1.0, 30.0, 100, true), true);
        Tile tile = TileBuilder.build(r.get("p"), r);
        assertEquals(TileColor.RED, tile.color());
    }
}
