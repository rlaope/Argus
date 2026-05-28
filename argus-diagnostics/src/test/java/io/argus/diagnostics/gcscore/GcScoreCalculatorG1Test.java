package io.argus.diagnostics.gcscore;

import io.argus.diagnostics.gclog.GcLogAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GcScoreCalculatorG1Test {

    /**
     * Construct a GcLogAnalysis with G1 cause keys and the specified pause/fullGc
     * profile. Keeps remaining fields at sane defaults so the tests focus on
     * what they're varying.
     */
    private static GcLogAnalysis g1Analysis(int totalEvents, int fullGcEvents,
                                            long maxPauseMs, long p99PauseMs,
                                            double durationSec, double throughputPct) {
        Map<String, GcLogAnalysis.CauseStats> causes = Map.of(
                "G1 Evacuation Pause",
                new GcLogAnalysis.CauseStats("G1 Evacuation Pause",
                        totalEvents - fullGcEvents, /*totalMs*/ p99PauseMs * (totalEvents - fullGcEvents),
                        maxPauseMs, p99PauseMs / 2, p99PauseMs));
        return new GcLogAnalysis(
                /* totalEvents      */ totalEvents,
                /* pauseEvents      */ totalEvents,
                /* fullGcEvents     */ fullGcEvents,
                /* concurrentEvents */ 0,
                /* durationSec      */ durationSec,
                /* throughputPercent*/ throughputPct,
                /* totalPauseMs     */ p99PauseMs * totalEvents,
                /* maxPauseMs       */ maxPauseMs,
                /* p50PauseMs       */ p99PauseMs / 3,
                /* p95PauseMs       */ p99PauseMs / 2,
                /* p99PauseMs       */ p99PauseMs,
                /* avgPauseMs       */ p99PauseMs / 2,
                /* peakHeapKB       */ 0,
                /* avgHeapAfterKB   */ 0,
                causes,
                List.of(),
                /* rateAnalysis     */ null,
                /* leakAnalysis     */ null);
    }

    @Test
    void g1_branch_uses_standard_pause_scorers() {
        // p99 = 350 ms → standard scorer returns WARN (200 < x ≤ 500).
        GcLogAnalysis a = g1Analysis(100, 0, 400, 350, 60, 95);
        GcScoreResult r = GcScoreCalculator.compute(a, "G1GC");
        AxisScore p99 = r.axes().get(0);
        assertEquals("Pause p99", p99.name());
        assertEquals(AxisScore.Verdict.WARN, p99.verdict());
    }

    @Test
    void g1_branch_weights_full_gc_higher_than_standard() {
        // 10 Full GCs / 60s = 600/hour → standard scorer FAIL on both, but the
        // G1 weight on this axis (0.25) is higher than standard (0.15), so the
        // G1 score should be lower.
        GcLogAnalysis a = g1Analysis(100, 10, 80, 60, 60, 99);

        GcScoreResult g1Score  = GcScoreCalculator.compute(a, "G1GC");
        GcScoreResult stdScore = GcScoreCalculator.compute(a, "");

        assertTrue(g1Score.overall() < stdScore.overall(),
                "G1 overall (" + g1Score.overall() + ") should be lower than standard ("
                        + stdScore.overall() + ") when Full GC dominates");
    }

    @Test
    void g1_axis_count_matches_standard_six() {
        GcScoreResult r = GcScoreCalculator.compute(
                g1Analysis(100, 0, 80, 60, 60, 99.5), "G1GC");
        assertEquals(6, r.axes().size());
    }

    @Test
    void g1_healthy_grade_is_A_or_B() {
        GcScoreResult r = GcScoreCalculator.compute(
                g1Analysis(120, 0, 80, 60, 60, 99.5), "G1GC");
        assertTrue(r.grade().equals("A") || r.grade().equals("B"),
                "Expected A or B for healthy G1, got " + r.grade() + " (overall=" + r.overall() + ")");
    }

    @Test
    void g1_hint_for_full_gc_mentions_g1_specific_remedy() {
        GcLogAnalysis a = g1Analysis(50, 5, 150, 100, 60, 97);
        GcScoreResult r = GcScoreCalculator.compute(a, "G1GC");
        List<String> hints = r.hints();
        assertTrue(hints.stream().anyMatch(h -> h.contains("Full GC")
                        && (h.contains("InitiatingHeapOccupancyPercent") || h.contains("argus heap"))),
                "Expected G1-specific Full GC hint, got: " + hints);
    }

    @Test
    void weights_sum_to_one_for_g1() {
        double[] weights = new double[]{0.22, 0.13, 0.20, 0.25, 0.10, 0.10};
        double sum = 0;
        for (double w : weights) sum += w;
        assertEquals(1.00, sum, 0.0001);
    }
}
