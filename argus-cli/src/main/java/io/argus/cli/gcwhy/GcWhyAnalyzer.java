package io.argus.cli.gcwhy;

import io.argus.cli.gclog.GcEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Correlation engine for {@code argus gcwhy}: given a list of GC events and
 * a time window, picks the worst pause and derives a short narrative
 * explanation by applying ordered rules over the preceding events.
 */
public final class GcWhyAnalyzer {

    private static final int MAX_BULLETS = 3;

    private GcWhyAnalyzer() {}

    /** @param windowSeconds lookback window relative to the last event timestamp */
    public static GcWhyResult analyze(List<GcEvent> events, double windowSeconds) {
        if (events == null || events.isEmpty()) return GcWhyResult.empty();

        double latestTs = events.getLast().timestampSec();
        double cutoffTs = latestTs - windowSeconds;

        List<GcEvent> window = new ArrayList<>();
        for (GcEvent e : events) {
            if (e.timestampSec() >= cutoffTs && !e.isConcurrent()) {
                window.add(e);
            }
        }
        if (window.isEmpty()) return GcWhyResult.empty();

        // Pick worst pause in window.
        GcEvent worst = window.get(0);
        for (GcEvent e : window) {
            if (e.pauseMs() > worst.pauseMs()) worst = e;
        }

        // Prior events (anything in window strictly before target)
        List<GcEvent> prior = new ArrayList<>();
        for (GcEvent e : window) {
            if (e.timestampSec() < worst.timestampSec()) prior.add(e);
        }

        List<String> bullets = buildBullets(worst, prior, window);
        Map<String, String> counters = buildCounters(worst, prior);

        return new GcWhyResult(
                worst.timestampSec(),
                worst.type(),
                worst.cause(),
                worst.pauseMs(),
                bullets,
                counters);
    }

    private static List<String> buildBullets(GcEvent target, List<GcEvent> prior, List<GcEvent> window) {
        List<String> bullets = new ArrayList<>();
        String cause = target.cause() == null ? "" : target.cause();
        String lowerCause = cause.toLowerCase();
        String lowerType  = target.type() == null ? "" : target.type().toLowerCase();

        double heapUsedPct = target.heapTotalKB() > 0
                ? (target.heapBeforeKB() * 100.0 / target.heapTotalKB())
                : 0;

        // R1. System.gc()
        if (lowerCause.contains("system.gc")) {
            bullets.add("Cause is an explicit System.gc() call — application or a framework is "
                    + "forcing a full cycle; consider -XX:+DisableExplicitGC if unintended.");
        }

        // R2. Humongous (G1)
        if (lowerCause.contains("humongous") || lowerCause.contains("g1 humongous")) {
            bullets.add("G1 humongous allocation triggered this pause — an object larger than half "
                    + "the region size bypassed the young gen and went straight to old.");
        }

        // R3. Metadata GC
        if (lowerCause.contains("metadata")) {
            bullets.add("Metaspace pressure — Metadata GC Threshold hit; consider -XX:MetaspaceSize="
                    + "and -XX:MaxMetaspaceSize= tuning.");
        }

        // R4. Full GC after concurrent cycle (concurrent mode / evacuation failure)
        if (target.isFullGc() && (lowerCause.contains("concurrent") || lowerCause.contains("evacuation")
                || lowerCause.contains("failure"))) {
            bullets.add("Full GC fallback — the concurrent collector ran out of time or space before "
                    + "it could reclaim enough; heap filled faster than it could clean.");
        } else if (target.isFullGc()) {
            bullets.add("Full GC (STW) — old generation could not satisfy allocation through young/mixed "
                    + "cycles; likely heap-sizing or live-set issue.");
        }

        // R5. Allocation burst — heapBefore jumped compared to prior events' avg delta
        Double burstMultiplier = allocationBurstMultiplier(target, prior);
        if (burstMultiplier != null && burstMultiplier >= 2.0 && bullets.size() < MAX_BULLETS) {
            bullets.add(String.format(
                    "Allocation rate surged roughly %.1fx the recent baseline in the events leading up "
                            + "to this pause — a burst of allocation pressure.", burstMultiplier));
        }

        // R6. High occupancy
        if (heapUsedPct >= 90 && bullets.size() < MAX_BULLETS) {
            bullets.add(String.format(
                    "Heap was %.0f%% occupied at pause start — very little headroom left for new allocation.",
                    heapUsedPct));
        }

        // R7. Pause is an outlier vs prior average
        double priorAvg = prior.stream().mapToDouble(GcEvent::pauseMs).average().orElse(0);
        if (priorAvg > 0 && target.pauseMs() > priorAvg * 3 && bullets.size() < MAX_BULLETS) {
            bullets.add(String.format(
                    "Pause was %.1fx the recent average (%.0f ms vs %.0f ms) — this is an outlier.",
                    target.pauseMs() / priorAvg, target.pauseMs(), priorAvg));
        }

        // Fallback — always show at least one line.
        if (bullets.isEmpty()) {
            bullets.add(String.format(
                    "Cause '%s' within nominal range; pause of %.0f ms aligns with the current "
                            + "workload profile (no obvious anomaly in the preceding %d events).",
                    cause.isEmpty() ? "unknown" : cause, target.pauseMs(), window.size() - 1));
        }

        // Trim
        return bullets.size() > MAX_BULLETS ? bullets.subList(0, MAX_BULLETS) : bullets;
    }

    /**
     * Returns null when a burst ratio cannot be computed. Otherwise: ratio of allocation between
     * the target event (heapBefore - previous heapAfter) and the average allocation across prior
     * events in the window.
     */
    private static Double allocationBurstMultiplier(GcEvent target, List<GcEvent> prior) {
        if (prior.size() < 2) return null;
        GcEvent last = prior.get(prior.size() - 1);
        double targetAlloc = Math.max(0, target.heapBeforeKB() - last.heapAfterKB());
        double targetDelta = Math.max(0.001, target.timestampSec() - last.timestampSec());
        double targetRate = targetAlloc / targetDelta;

        double totalAlloc = 0;
        double totalDelta = 0;
        for (int i = 1; i < prior.size(); i++) {
            double alloc = Math.max(0, prior.get(i).heapBeforeKB() - prior.get(i - 1).heapAfterKB());
            double delta = Math.max(0.001, prior.get(i).timestampSec() - prior.get(i - 1).timestampSec());
            totalAlloc += alloc;
            totalDelta += delta;
        }
        if (totalDelta <= 0 || totalAlloc <= 0) return null;
        double avgRate = totalAlloc / totalDelta;
        if (avgRate <= 0) return null;
        return targetRate / avgRate;
    }

    private static Map<String, String> buildCounters(GcEvent target, List<GcEvent> prior) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pause-ms", String.format("%.0f", target.pauseMs()));
        m.put("type", target.type());
        m.put("cause", target.cause());
        m.put("heap-before-kb", String.valueOf(target.heapBeforeKB()));
        m.put("heap-after-kb",  String.valueOf(target.heapAfterKB()));
        m.put("heap-total-kb",  String.valueOf(target.heapTotalKB()));
        if (target.heapTotalKB() > 0) {
            m.put("heap-used-pct", String.format("%.1f",
                    target.heapBeforeKB() * 100.0 / target.heapTotalKB()));
        }
        double priorAvg = prior.stream().mapToDouble(GcEvent::pauseMs).average().orElse(0);
        m.put("prior-avg-pause-ms", String.format("%.0f", priorAvg));
        m.put("prior-events", String.valueOf(prior.size()));
        return m;
    }
}
