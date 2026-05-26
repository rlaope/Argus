package io.argus.aggregator.model;

/**
 * Full tile descriptor for one pod. Used in /fleet/list and /fleet/pod/{podId}.
 *
 * @param podId        matches PodTarget.podId
 * @param color        tile color
 * @param target       embedded full target record
 * @param metrics      latest scraped metrics
 * @param alertCount   number of active (firing) alerts for this pod
 * @param drillDownUrl frontend relative URL for drill-down link
 */
public record Tile(
        String podId,
        TileColor color,
        PodTarget target,
        TileMetrics metrics,
        int alertCount,
        String drillDownUrl
) {
    public static String drillDownUrlFor(String podId) {
        return "/pod/" + podId;
    }
}
