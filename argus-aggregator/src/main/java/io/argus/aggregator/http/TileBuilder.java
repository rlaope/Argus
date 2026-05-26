package io.argus.aggregator.http;

import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.Tile;
import io.argus.aggregator.model.TileColor;
import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.store.FleetRegistry;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>For batch rendering (fleet list / summary), prefer
 * {@link #buildWith(PodTarget, TileMetrics, int, Set)} so the caller can
 * precompute per-pod alert summaries once via
 * {@link FleetRegistry#alertCountsByPod()} and
 * {@link FleetRegistry#alertSeveritiesByPod()} — avoids O(targets × alerts)
 * scans across the whole fleet.
 */
public final class TileBuilder {

    private static final double HEAP_RED = 90.0;
    private static final double HEAP_YELLOW = 80.0;
    private static final double GC_RED = 10.0;
    private static final double GC_YELLOW = 5.0;
    private static final double CPU_RED = 90.0;
    private static final double CPU_YELLOW = 80.0;

    private TileBuilder() {}

    /** Single-target convenience. Performs the per-pod alert scans inline. */
    public static Tile build(PodTarget target, FleetRegistry registry) {
        TileMetrics metrics = registry.latestMetrics(target.podId());
        if (metrics == null) metrics = TileMetrics.empty();
        int alertCount = registry.alertCountForPod(target.podId());
        Set<String> severities = severitiesFor(target.podId(), registry);
        return buildWith(target, metrics, alertCount, severities);
    }

    /** Batch-friendly: caller supplies precomputed alert summary for this pod. */
    public static Tile buildWith(PodTarget target, TileMetrics metrics,
                                 int alertCount, Set<String> alertSeverities) {
        TileMetrics m = metrics == null ? TileMetrics.empty() : metrics;
        Set<String> sev = alertSeverities == null ? Collections.emptySet() : alertSeverities;
        TileColor color = computeColor(target, m, sev);
        return new Tile(
                target.podId(),
                color,
                target,
                m,
                alertCount,
                Tile.drillDownUrlFor(target.podId())
        );
    }

    /** Single-target color computation (registry-based; uses inline alert scan). */
    public static TileColor computeColor(PodTarget target, TileMetrics metrics, FleetRegistry registry) {
        return computeColor(target, metrics, severitiesFor(target.podId(), registry));
    }

    /** Color computation with precomputed severity set. */
    public static TileColor computeColor(PodTarget target, TileMetrics metrics,
                                         Set<String> alertSeverities) {
        if (target.lastScrapeAt() == null || !target.scrapeOk()) {
            return TileColor.GREY;
        }
        boolean hasCritical = alertSeverities.contains("critical");
        boolean hasWarning = alertSeverities.contains("warning");
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

    private static Set<String> severitiesFor(String podId, FleetRegistry registry) {
        Set<String> out = new java.util.HashSet<>();
        for (var alert : registry.activeAlertsForPod(podId)) {
            if (alert.severity() != null) {
                out.add(alert.severity().toLowerCase());
            }
        }
        return out;
    }

    private static boolean exceeds(Double value, double threshold) {
        return value != null && value >= threshold;
    }
}
