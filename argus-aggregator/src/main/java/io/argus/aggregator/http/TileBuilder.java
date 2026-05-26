package io.argus.aggregator.http;

import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.Tile;
import io.argus.aggregator.model.TileColor;
import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.store.FleetRegistry;

/**
 * Computes {@link TileColor} from {@link TileMetrics} + alert state, and builds
 * full {@link Tile} records for HTTP responses.
 *
 * <p>Color thresholds:
 * <ul>
 *   <li>{@code grey}  — never scraped successfully ({@code lastScrapeAt == null}) or {@code scrapeOk == false}</li>
 *   <li>{@code red}   — heap ≥ 90% OR gc ≥ 10% OR cpu ≥ 90% OR leakSuspected OR any critical alert firing</li>
 *   <li>{@code yellow} — heap ≥ 80% OR gc ≥ 5% OR cpu ≥ 80% OR any warning alert firing</li>
 *   <li>{@code green} — otherwise</li>
 * </ul>
 */
public final class TileBuilder {

    private static final double HEAP_RED = 90.0;
    private static final double HEAP_YELLOW = 80.0;
    private static final double GC_RED = 10.0;
    private static final double GC_YELLOW = 5.0;
    private static final double CPU_RED = 90.0;
    private static final double CPU_YELLOW = 80.0;

    private TileBuilder() {}

    public static Tile build(PodTarget target, FleetRegistry registry) {
        TileMetrics metrics = registry.latestMetrics(target.podId());
        if (metrics == null) metrics = TileMetrics.empty();
        int alertCount = registry.alertCountForPod(target.podId());
        TileColor color = computeColor(target, metrics, registry);
        return new Tile(
                target.podId(),
                color,
                target,
                metrics,
                alertCount,
                Tile.drillDownUrlFor(target.podId())
        );
    }

    public static TileColor computeColor(PodTarget target, TileMetrics metrics, FleetRegistry registry) {
        if (target.lastScrapeAt() == null || !target.scrapeOk()) {
            return TileColor.GREY;
        }
        boolean hasCritical = false;
        boolean hasWarning = false;
        for (var alert : registry.activeAlertsForPod(target.podId())) {
            if ("critical".equalsIgnoreCase(alert.severity())) hasCritical = true;
            else if ("warning".equalsIgnoreCase(alert.severity())) hasWarning = true;
        }
        if (hasCritical
                || metrics.leakSuspected()
                || exceeds(metrics.heapPercent(), HEAP_RED)
                || exceeds(metrics.gcOverheadPercent(), GC_RED)
                || exceeds(metrics.cpuPercent(), CPU_RED)) {
            return TileColor.RED;
        }
        if (hasWarning
                || exceeds(metrics.heapPercent(), HEAP_YELLOW)
                || exceeds(metrics.gcOverheadPercent(), GC_YELLOW)
                || exceeds(metrics.cpuPercent(), CPU_YELLOW)) {
            return TileColor.YELLOW;
        }
        return TileColor.GREEN;
    }

    private static boolean exceeds(Double value, double threshold) {
        return value != null && value >= threshold;
    }
}
