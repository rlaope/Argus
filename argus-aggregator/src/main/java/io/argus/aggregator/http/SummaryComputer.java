package io.argus.aggregator.http;

import io.argus.aggregator.model.FleetSummary;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.Tile;
import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.store.FleetRegistry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes a {@link FleetSummary} roll-up from the live state of a
 * {@link FleetRegistry}.
 */
public final class SummaryComputer {

    private SummaryComputer() {}

    public static FleetSummary compute(FleetRegistry registry) {
        List<PodTarget> targets = registry.listTargets();
        Map<String, Integer> alertCounts = registry.alertCountsByPod();
        Map<String, Set<String>> alertSeverities = registry.alertSeveritiesByPod();
        int total = targets.size();
        int up = 0, down = 0;
        int green = 0, yellow = 0, red = 0, grey = 0;
        long vtTotal = 0;
        int leakCount = 0;

        double heapMin = Double.MAX_VALUE, heapMax = -1, heapSum = 0;
        double gcMin   = Double.MAX_VALUE, gcMax   = -1, gcSum   = 0;
        double cpuMin  = Double.MAX_VALUE, cpuMax  = -1, cpuSum  = 0;
        int heapCnt = 0, gcCnt = 0, cpuCnt = 0;

        String worstPodId = null;
        double worstGc = -1;
        String worstReason = null;

        for (PodTarget t : targets) {
            if (t.scrapeOk()) up++; else down++;
            TileMetrics m = registry.latestMetrics(t.podId());
            if (m == null) m = TileMetrics.empty();
            int alertCount = alertCounts.getOrDefault(t.podId(), 0);
            Set<String> sev = alertSeverities.getOrDefault(t.podId(), Collections.emptySet());
            Tile tile = TileBuilder.buildWith(t, m, alertCount, sev);
            switch (tile.color()) {
                case GREEN -> green++;
                case YELLOW -> yellow++;
                case RED -> red++;
                case GREY -> grey++;
            }
            if (m.heapPercent() != null) {
                heapMin = Math.min(heapMin, m.heapPercent());
                heapMax = Math.max(heapMax, m.heapPercent());
                heapSum += m.heapPercent();
                heapCnt++;
            }
            if (m.gcOverheadPercent() != null) {
                gcMin = Math.min(gcMin, m.gcOverheadPercent());
                gcMax = Math.max(gcMax, m.gcOverheadPercent());
                gcSum += m.gcOverheadPercent();
                gcCnt++;
                if (m.gcOverheadPercent() > worstGc) {
                    worstGc = m.gcOverheadPercent();
                    worstPodId = t.podId();
                    worstReason = String.format("GC overhead %.1f%%", m.gcOverheadPercent());
                }
            }
            if (m.cpuPercent() != null) {
                cpuMin = Math.min(cpuMin, m.cpuPercent());
                cpuMax = Math.max(cpuMax, m.cpuPercent());
                cpuSum += m.cpuPercent();
                cpuCnt++;
            }
            vtTotal += m.activeVThreads();
            if (m.leakSuspected()) {
                leakCount++;
                if (worstPodId == null) {
                    worstPodId = t.podId();
                    worstReason = "memory leak suspected";
                }
            }
        }

        // If no worst was found via GC, take highest heap
        if (worstPodId == null && heapCnt > 0) {
            double worstHeap = -1;
            for (PodTarget t : targets) {
                TileMetrics m = registry.latestMetrics(t.podId());
                if (m == null || m.heapPercent() == null) continue;
                if (m.heapPercent() > worstHeap) {
                    worstHeap = m.heapPercent();
                    worstPodId = t.podId();
                    worstReason = String.format("heap %.1f%%", m.heapPercent());
                }
            }
        }

        FleetSummary.MinMaxAvg heap = heapCnt == 0
                ? FleetSummary.MinMaxAvg.empty()
                : new FleetSummary.MinMaxAvg(heapMin, heapMax, heapSum / heapCnt);
        FleetSummary.MinMaxAvg gc = gcCnt == 0
                ? FleetSummary.MinMaxAvg.empty()
                : new FleetSummary.MinMaxAvg(gcMin, gcMax, gcSum / gcCnt);
        FleetSummary.MinMaxAvg cpu = cpuCnt == 0
                ? FleetSummary.MinMaxAvg.empty()
                : new FleetSummary.MinMaxAvg(cpuMin, cpuMax, cpuSum / cpuCnt);

        return new FleetSummary(
                total, up, down,
                green, yellow, red, grey,
                registry.activeAlerts().size(),
                heap, gc, cpu,
                vtTotal, leakCount,
                worstPodId, worstReason
        );
    }
}
