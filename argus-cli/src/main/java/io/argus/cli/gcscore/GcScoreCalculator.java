package io.argus.cli.gcscore;

import io.argus.cli.gclog.GcLogAnalysis;
import io.argus.cli.gclog.GcRateAnalyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Computes a {@link GcScoreResult} from a {@link GcLogAnalysis}.
 *
 * <p>Six axes scored 0–100 with Pass/Warn/Fail verdicts, combined into a
 * weighted overall score (0–100) and an A–F grade. Up to three hints are
 * selected from a rule base keyed on which axes failed.
 */
public final class GcScoreCalculator {

    private GcScoreCalculator() {}

    public static GcScoreResult compute(GcLogAnalysis a) {
        List<AxisScore> axes = new ArrayList<>();

        axes.add(scorePauseP99(a.p99PauseMs()));
        axes.add(scorePauseTail(a.maxPauseMs()));
        axes.add(scoreThroughput(a.throughputPercent()));
        axes.add(scoreFullGcFreq(a.fullGcEvents(), a.durationSec()));

        GcRateAnalyzer.RateAnalysis rate = a.rateAnalysis();
        if (rate != null && rate.allocationRateKBPerSec() > 0) {
            double allocMBps = rate.allocationRateKBPerSec() / 1024.0;
            axes.add(scoreAllocationRate(allocMBps));
            axes.add(scorePromotionRatio(rate.promoAllocRatioPercent()));
        } else {
            axes.add(AxisScore.na("Allocation rate", "< 1 GB/s healthy band"));
            axes.add(AxisScore.na("Promotion ratio", "< 20% of allocation"));
        }

        int overall = weightedOverall(axes);
        String grade = grade(overall);
        String summary = summaryFor(grade);
        List<String> hints = selectHints(axes);

        return new GcScoreResult(axes, overall, grade, summary, hints);
    }

    // ── Per-axis scoring ────────────────────────────────────────────────────

    static AxisScore scorePauseP99(long p99Ms) {
        int score;
        AxisScore.Verdict verdict;
        if (p99Ms <= 200) { score = 100; verdict = AxisScore.Verdict.PASS; }
        else if (p99Ms <= 500) { score = (int) (100 - (p99Ms - 200) * 60.0 / 300); verdict = AxisScore.Verdict.WARN; }
        else if (p99Ms <= 1000) { score = Math.max(0, (int) (40 - (p99Ms - 500) * 40.0 / 500)); verdict = AxisScore.Verdict.FAIL; }
        else { score = 0; verdict = AxisScore.Verdict.FAIL; }
        return new AxisScore("Pause p99", p99Ms, "ms", "< 200 ms", score, verdict, true);
    }

    static AxisScore scorePauseTail(long maxMs) {
        int score;
        AxisScore.Verdict verdict;
        if (maxMs <= 500) { score = 100; verdict = AxisScore.Verdict.PASS; }
        else if (maxMs <= 1000) { score = (int) (100 - (maxMs - 500) * 40.0 / 500); verdict = AxisScore.Verdict.WARN; }
        else if (maxMs <= 2000) { score = Math.max(0, (int) (60 - (maxMs - 1000) * 60.0 / 1000)); verdict = AxisScore.Verdict.FAIL; }
        else { score = 0; verdict = AxisScore.Verdict.FAIL; }
        return new AxisScore("Pause tail (max)", maxMs, "ms", "< 500 ms", score, verdict, true);
    }

    static AxisScore scoreThroughput(double throughputPct) {
        int score;
        AxisScore.Verdict verdict;
        if (throughputPct >= 98) { score = 100; verdict = AxisScore.Verdict.PASS; }
        else if (throughputPct >= 95) { score = (int) (80 + (throughputPct - 95) * 20.0 / 3); verdict = AxisScore.Verdict.PASS; }
        else if (throughputPct >= 90) { score = (int) (50 + (throughputPct - 90) * 30.0 / 5); verdict = AxisScore.Verdict.WARN; }
        else { score = Math.max(0, (int) (throughputPct / 90 * 50)); verdict = AxisScore.Verdict.FAIL; }
        return new AxisScore("Throughput", throughputPct, "%", "> 95%", score, verdict, true);
    }

    static AxisScore scoreFullGcFreq(int fullGcCount, double durationSec) {
        double perHour = durationSec > 0 ? fullGcCount * 3600.0 / durationSec : 0;
        int score;
        AxisScore.Verdict verdict;
        if (perHour < 0.5) { score = 100; verdict = AxisScore.Verdict.PASS; }
        else if (perHour <= 1) { score = 80; verdict = AxisScore.Verdict.PASS; }
        else if (perHour <= 5) { score = (int) (60 - (perHour - 1) * 30.0 / 4); verdict = AxisScore.Verdict.WARN; }
        else { score = Math.max(0, (int) (30 - perHour)); verdict = AxisScore.Verdict.FAIL; }
        return new AxisScore("Full GC frequency", perHour, "/hour", "0 / hour", score, verdict, true);
    }

    static AxisScore scoreAllocationRate(double mbPerSec) {
        int score;
        AxisScore.Verdict verdict;
        if (mbPerSec < 500) { score = 100; verdict = AxisScore.Verdict.PASS; }
        else if (mbPerSec < 1000) { score = 85; verdict = AxisScore.Verdict.PASS; }
        else if (mbPerSec < 2000) { score = 60; verdict = AxisScore.Verdict.WARN; }
        else { score = Math.max(0, (int) (50 - (mbPerSec - 2000) / 100)); verdict = AxisScore.Verdict.FAIL; }
        return new AxisScore("Allocation rate", mbPerSec, "MB/s", "< 1 GB/s", score, verdict, true);
    }

    static AxisScore scorePromotionRatio(double ratio) {
        int score;
        AxisScore.Verdict verdict;
        if (ratio < 10) { score = 100; verdict = AxisScore.Verdict.PASS; }
        else if (ratio < 20) { score = 80; verdict = AxisScore.Verdict.PASS; }
        else if (ratio < 40) { score = (int) (60 - (ratio - 20) * 30.0 / 20); verdict = AxisScore.Verdict.WARN; }
        else { score = Math.max(0, (int) (30 - (ratio - 40))); verdict = AxisScore.Verdict.FAIL; }
        return new AxisScore("Promotion ratio", ratio, "%", "< 20% of allocation", score, verdict, true);
    }

    // ── Overall grade ───────────────────────────────────────────────────────

    static int weightedOverall(List<AxisScore> axes) {
        double[] weights = {0.25, 0.15, 0.25, 0.15, 0.10, 0.10};
        double sum = 0, wUsed = 0;
        for (int i = 0; i < axes.size(); i++) {
            AxisScore ax = axes.get(i);
            if (!ax.available()) continue;
            double w = i < weights.length ? weights[i] : 0;
            sum += ax.score() * w;
            wUsed += w;
        }
        return wUsed > 0 ? (int) Math.round(sum / wUsed * 1.0) : 0;
    }

    static String grade(int overall) {
        if (overall >= 90) return "A";
        if (overall >= 75) return "B";
        if (overall >= 60) return "C";
        if (overall >= 40) return "D";
        return "F";
    }

    static String summaryFor(String grade) {
        return switch (grade) {
            case "A" -> "excellent, no tuning required";
            case "B" -> "good, minor tuning opportunities";
            case "C" -> "acceptable, tuning recommended";
            case "D" -> "poor, tuning required";
            default -> "critical, immediate action required";
        };
    }

    // ── Hint selection ──────────────────────────────────────────────────────

    static List<String> selectHints(List<AxisScore> axes) {
        List<String> hints = new ArrayList<>();
        for (AxisScore ax : axes) {
            if (ax.verdict() == AxisScore.Verdict.PASS || ax.verdict() == AxisScore.Verdict.NA) continue;
            String hint = hintFor(ax);
            if (hint != null) hints.add(hint);
            if (hints.size() >= 3) break;
        }
        if (hints.isEmpty()) hints.add("GC health looks good — no changes needed.");
        return hints;
    }

    private static String hintFor(AxisScore ax) {
        return switch (ax.name()) {
            case "Pause p99" -> "Long p99 pauses — try -XX:MaxGCPauseMillis=200 or raise heap to absorb allocation bursts";
            case "Pause tail (max)" -> "Long tail pauses suggest concurrent-mode failure or humongous allocation; consider G1/ZGC or increase heap";
            case "Throughput" -> "Low throughput — raise -Xmx or consider a concurrent collector (G1/ZGC/Shenandoah)";
            case "Full GC frequency" -> "Frequent Full GC — heap too small or memory leak; inspect with argus heap / argus diff";
            case "Allocation rate" -> "Very high allocation rate — run argus flame --type=alloc to find hot allocation sites";
            case "Promotion ratio" -> "Survivor overflow promoting short-lived objects; raise -XX:MaxTenuringThreshold or -XX:SurvivorRatio";
            default -> null;
        };
    }

    // Expose for tests
    static List<String> defaultAxisOrder() {
        return Arrays.asList(
                "Pause p99",
                "Pause tail (max)",
                "Throughput",
                "Full GC frequency",
                "Allocation rate",
                "Promotion ratio");
    }
}
