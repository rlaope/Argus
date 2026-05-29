package io.argus.aggregator.model;

import java.util.List;

/**
 * Cluster-wide right-sizing roll-up. Used by {@code GET /fleet/rightsize}.
 *
 * <p>The aggregator only holds heap <i>percent</i> (heap used relative to each pod's current
 * {@code -Xmx}), not absolute bytes — so this roll-up reasons in percent. For each deployment
 * it takes the peak observed heap% across its pods (the high-water mark as a fraction of the
 * current {@code -Xmx}) and recommends shrinking {@code -Xmx} to that peak times a safety
 * factor, capped at 100%. A deployment whose peak heap% sits well below 100% is
 * over-provisioned; {@code savingsPercent} is the proportional reduction available.
 *
 * <p>This is intentionally a fleet triage signal, not a per-pod prescription: drill into a
 * single pod with {@code argus rightsize <pid>} for the byte-accurate, live-set-floored
 * recommendation.
 *
 * @param safetyFactor       the safety factor applied to peak heap% (echoed for honesty)
 * @param deployments        per-deployment current-vs-recommended rows
 * @param aggregateSavingsPercent average savings across deployments with data (0–100)
 */
public record FleetRightsize(
        double safetyFactor,
        List<DeploymentRow> deployments,
        double aggregateSavingsPercent
) {
    /**
     * One deployment's current-vs-recommended memory picture, in percent of current -Xmx.
     *
     * @param namespace        K8s namespace
     * @param deployment       deployment/statefulset name
     * @param podCount         pods contributing samples
     * @param peakHeapPercent  peak observed heap% across the deployment's pods (null if no data)
     * @param recommendedHeapPercent recommended -Xmx as a % of the current -Xmx (null if no data)
     * @param savingsPercent   proportional -Xmx reduction available (0 if none / no data)
     */
    public record DeploymentRow(
            String namespace,
            String deployment,
            int podCount,
            Double peakHeapPercent,
            Double recommendedHeapPercent,
            double savingsPercent
    ) {}
}
