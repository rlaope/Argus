package io.argus.aggregator.http;

import io.argus.aggregator.model.FleetRightsize;
import io.argus.aggregator.model.FleetRightsize.DeploymentRow;
import io.argus.aggregator.model.MetricSample;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.store.FleetRegistry;
import io.argus.aggregator.store.PodRingBuffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes a {@link FleetRightsize} roll-up from the live state of a {@link FleetRegistry}.
 *
 * <p>Reuses the per-pod ring buffers the aggregator already holds. Works in percent of the
 * current {@code -Xmx} (the aggregator does not hold absolute heap bytes). The same safety
 * factor as the per-pod CLI recommender ({@code argus rightsize}) is applied so the two
 * surfaces agree on headroom policy.
 */
public final class FleetRightsizeComputer {

    /** Mirrors {@code RightsizeRecommender.XMX_SAFETY_FACTOR} in the CLI (kept in sync by intent). */
    public static final double SAFETY_FACTOR = 1.5;

    private FleetRightsizeComputer() {}

    public static FleetRightsize compute(FleetRegistry registry) {
        List<PodTarget> targets = registry.listTargets();

        // Group pods by "<namespace>/<deployment>", preserving first-seen order.
        Map<String, List<PodTarget>> byDeployment = new LinkedHashMap<>();
        for (PodTarget t : targets) {
            String key = t.namespace() + "/" + t.deployment();
            byDeployment.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        List<DeploymentRow> rows = new ArrayList<>();
        double savingsSum = 0;
        int savingsCount = 0;

        for (Map.Entry<String, List<PodTarget>> e : byDeployment.entrySet()) {
            List<PodTarget> pods = e.getValue();
            String namespace = pods.get(0).namespace();
            String deployment = pods.get(0).deployment();

            // Peak observed heap% across all pods' samples = the deployment's HWM as a
            // fraction of current -Xmx.
            double peak = -1;
            for (PodTarget t : pods) {
                PodRingBuffer buf = registry.buffer(t.podId());
                if (buf == null) continue;
                for (MetricSample s : buf.snapshot()) {
                    if (s.heapPercent() != null) {
                        peak = Math.max(peak, s.heapPercent());
                    }
                }
            }

            if (peak < 0) {
                // No heap samples for this deployment yet — report it with nulls, no savings.
                rows.add(new DeploymentRow(namespace, deployment, pods.size(), null, null, 0.0));
                continue;
            }

            // Recommended -Xmx as % of current -Xmx = peak% * safety factor, capped at 100%.
            double recommended = Math.min(100.0, peak * SAFETY_FACTOR);
            // Savings = how much of the current -Xmx we can give back (0 if recommended >= 100%).
            double savings = Math.max(0.0, 100.0 - recommended);

            rows.add(new DeploymentRow(namespace, deployment, pods.size(), peak, recommended, savings));
            savingsSum += savings;
            savingsCount++;
        }

        double aggregate = savingsCount == 0 ? 0.0 : savingsSum / savingsCount;
        return new FleetRightsize(SAFETY_FACTOR, rows, aggregate);
    }
}
