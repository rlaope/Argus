package io.argus.diagnostics.gcscore;

import io.argus.diagnostics.gclog.GcLogAnalysis;
import io.argus.diagnostics.gclog.GcRateAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GcScoreCalculatorTest {

    @Test
    void perfectGcGetsA() {
        GcLogAnalysis a = analysis(50, 300, 99.0, 0, healthyRates());
        GcScoreResult r = GcScoreCalculator.compute(a);
        assertEquals("A", r.grade());
        assertTrue(r.overall() >= 90, "overall should be >= 90, got " + r.overall());
        assertEquals(1, r.hints().size(), "healthy GC yields single fallback hint");
    }

    @Test
    void longPauseP99DrivesGradeDown() {
        GcLogAnalysis a = analysis(900, 1800, 92.0, 2, healthyRates());
        GcScoreResult r = GcScoreCalculator.compute(a);
        assertTrue(r.overall() < 60, "overall should be < 60 for long p99, got " + r.overall());
        assertNotEquals("A", r.grade());
        assertTrue(
                r.hints().stream().anyMatch(h -> h.contains("p99") || h.contains("tail")),
                "expected hint about pauses, got " + r.hints());
    }

    @Test
    void frequentFullGcFlagged() {
        GcLogAnalysis a = analysis(100, 400, 95.0, 60, healthyRates());
        GcScoreResult r = GcScoreCalculator.compute(a);
        AxisScore fullGc = r.axes().stream()
                .filter(ax -> ax.name().equals("Full GC frequency"))
                .findFirst().orElseThrow();
        assertEquals(AxisScore.Verdict.FAIL, fullGc.verdict());
        assertTrue(
                r.hints().stream().anyMatch(h -> h.toLowerCase().contains("full gc")),
                "expected hint about Full GC, got " + r.hints());
    }

    @Test
    void missingRateAnalysisMarksAxesAsNa() {
        GcLogAnalysis a = analysis(50, 300, 99.0, 0, null);
        GcScoreResult r = GcScoreCalculator.compute(a);
        AxisScore alloc = axis(r, "Allocation rate");
        AxisScore promo = axis(r, "Promotion ratio");
        assertFalse(alloc.available());
        assertFalse(promo.available());
        assertEquals(AxisScore.Verdict.NA, alloc.verdict());
        assertEquals(AxisScore.Verdict.NA, promo.verdict());
    }

    @Test
    void highPromotionRatioTriggersSurvivorHint() {
        GcRateAnalyzer.RateAnalysis highPromo = new GcRateAnalyzer.RateAnalysis(
                200_000, 300_000,   // allocation KB/s (~195 MB/s)
                120_000, 150_000,   // promotion KB/s (~60% of alloc)
                50.0, 60.0,         // reclaim %, promo/alloc %
                new double[0], new double[0]);
        GcLogAnalysis a = analysis(100, 400, 97.0, 0, highPromo);
        GcScoreResult r = GcScoreCalculator.compute(a);
        AxisScore promo = axis(r, "Promotion ratio");
        assertEquals(AxisScore.Verdict.FAIL, promo.verdict());
        assertTrue(
                r.hints().stream().anyMatch(h -> h.toLowerCase().contains("survivor") || h.toLowerCase().contains("tenuring")),
                "expected survivor/tenuring hint, got " + r.hints());
    }

    /**
     * ZGC weighting: a 5 ms max pause that would score PASS (100) under G1
     * should still score PASS under ZGC's tighter thresholds (target < 10 ms),
     * and a 50 ms max pause that scores PASS under G1 should score FAIL under ZGC.
     * The overall ZGC score must be meaningfully higher than the G1 score for
     * the same synthetic log that has a 5 ms max pause (a normal ZGC result).
     */
    @Test
    void zgcWeightReducesPauseTailImpact() {
        // 5 ms max pause, 3 ms p99 — perfectly normal for ZGC, mediocre for G1
        GcLogAnalysis zgcAnalysis = analysisWithCause(3, 5, 99.0, 0, healthyRates(), "ZGC");
        GcLogAnalysis g1Analysis  = analysisWithCause(3, 5, 99.0, 0, healthyRates(), "G1 Evacuation Pause");

        GcScoreResult zgcResult = GcScoreCalculator.compute(zgcAnalysis, "ZGC");
        GcScoreResult g1Result  = GcScoreCalculator.compute(g1Analysis, "G1GC");

        // ZGC pause tail axis should score 100 (5 ms <= 10 ms target)
        AxisScore zgcTail = zgcResult.axes().stream()
                .filter(ax -> ax.name().equals("Pause tail (max)"))
                .findFirst().orElseThrow();
        assertEquals(AxisScore.Verdict.PASS, zgcTail.verdict(),
                "5 ms max pause should be PASS under ZGC scoring");

        // ZGC result should include the allocation-pressure axis
        boolean hasAllocPressure = zgcResult.axes().stream()
                .anyMatch(ax -> ax.name().equals("Allocation pressure (ZGC)"));
        assertTrue(hasAllocPressure, "ZGC compute should include Allocation pressure (ZGC) axis");

        // Both should produce a good overall score, but the ZGC result should not
        // be penalised more than G1 for a 5 ms pause
        assertTrue(zgcResult.overall() >= g1Result.overall(),
                "ZGC overall (" + zgcResult.overall() + ") should be >= G1 overall (" + g1Result.overall() + ") for a 5 ms pause");
    }

    /**
     * A 50 ms max pause that scores PASS under G1 (target < 500 ms) must score
     * FAIL under ZGC (target < 10 ms), confirming that ZGC uses tighter thresholds.
     */
    @Test
    void zgcPauseTailIsTighterThanG1() {
        GcLogAnalysis analysis = analysisWithCause(30, 50, 99.0, 0, healthyRates(), "ZGC");

        GcScoreResult zgcResult = GcScoreCalculator.compute(analysis, "ZGC");
        GcScoreResult g1Result  = GcScoreCalculator.compute(analysis, "G1GC");

        AxisScore zgcTail = zgcResult.axes().stream()
                .filter(ax -> ax.name().equals("Pause tail (max)"))
                .findFirst().orElseThrow();
        AxisScore g1Tail = g1Result.axes().stream()
                .filter(ax -> ax.name().equals("Pause tail (max)"))
                .findFirst().orElseThrow();

        assertEquals(AxisScore.Verdict.FAIL, zgcTail.verdict(),
                "50 ms max pause should be FAIL under ZGC scoring (target < 10 ms)");
        assertEquals(AxisScore.Verdict.PASS, g1Tail.verdict(),
                "50 ms max pause should be PASS under G1 scoring (target < 500 ms)");
    }

    @Test
    void gradeBandariesCorrect() {
        assertEquals("A", GcScoreCalculator.grade(95));
        assertEquals("A", GcScoreCalculator.grade(90));
        assertEquals("B", GcScoreCalculator.grade(75));
        assertEquals("C", GcScoreCalculator.grade(60));
        assertEquals("D", GcScoreCalculator.grade(40));
        assertEquals("F", GcScoreCalculator.grade(39));
        assertEquals("F", GcScoreCalculator.grade(0));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static AxisScore axis(GcScoreResult r, String name) {
        return r.axes().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("axis not found: " + name));
    }

    private static GcRateAnalyzer.RateAnalysis healthyRates() {
        return new GcRateAnalyzer.RateAnalysis(
                100_000, 150_000,    // allocation KB/s (~98 MB/s)
                5_000, 10_000,       // promotion KB/s (~5% of alloc)
                80.0, 5.0,
                new double[0], new double[0]);
    }

    private static GcLogAnalysis analysis(
            long p99Ms, long maxMs, double throughputPct, int fullGcCount,
            GcRateAnalyzer.RateAnalysis rates) {
        double duration = 3600; // 1 hour window
        return new GcLogAnalysis(
                100,                          // totalEvents
                90,                           // pauseEvents
                fullGcCount,                  // fullGcEvents
                10,                           // concurrentEvents
                duration,
                throughputPct,
                500,                          // totalPauseMs
                maxMs,
                Math.max(1, p99Ms / 10),      // p50
                Math.max(1, (long) (p99Ms * 0.7)), // p95
                p99Ms,
                5,                            // avg
                1_048_576,                    // peakHeapKB
                500_000,                      // avgHeapAfterKB
                Map.of(),
                List.of(),
                rates,
                null);
    }

    /**
     * Like {@link #analysis} but injects a single cause entry so that
     * {@link GcScoreCalculator#inferAlgorithm} can detect the GC type.
     */
    private static GcLogAnalysis analysisWithCause(
            long p99Ms, long maxMs, double throughputPct, int fullGcCount,
            GcRateAnalyzer.RateAnalysis rates, String causeKey) {
        double duration = 3600;
        return new GcLogAnalysis(
                100,
                90,
                fullGcCount,
                10,
                duration,
                throughputPct,
                500,
                maxMs,
                Math.max(1, p99Ms / 10),
                Math.max(1, (long) (p99Ms * 0.7)),
                p99Ms,
                5,
                1_048_576,
                500_000,
                Map.of(causeKey, new GcLogAnalysis.CauseStats(causeKey, 10, 50, 10, 5)),
                List.of(),
                rates,
                null);
    }
}
