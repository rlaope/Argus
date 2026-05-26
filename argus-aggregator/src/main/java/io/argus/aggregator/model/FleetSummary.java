package io.argus.aggregator.model;

/**
 * Cluster-wide roll-up. Used by {@code GET /fleet/summary}.
 *
 * <p>{@code MinMaxAvg} fields use boxed {@link Double} so null can represent
 * "no data available".
 */
public record FleetSummary(
        int totalTargets,
        int upTargets,
        int downTargets,
        int greenCount,
        int yellowCount,
        int redCount,
        int greyCount,
        int totalAlerts,
        MinMaxAvg heap,
        MinMaxAvg gc,
        MinMaxAvg cpu,
        long totalActiveVThreads,
        int leakSuspectedCount,
        String worstPodId,
        String worstReason
) {
    public record MinMaxAvg(Double min, Double max, Double avg) {
        public static MinMaxAvg empty() {
            return new MinMaxAvg(null, null, null);
        }
    }
}
