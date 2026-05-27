package io.argus.aggregator.model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
    /**
     * Builds the drill-down URL for the frontend. {@code podId} contains a
     * {@code /} separator ({@code namespace/podName}) which must be percent-
     * encoded. The Dashboard is served at {@code /} (index.html) and reads the
     * {@code pod} query param to load the pod context.
     */
    public static String drillDownUrlFor(String podId) {
        return "/?pod=" + URLEncoder.encode(podId, StandardCharsets.UTF_8);
    }
}
