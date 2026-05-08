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
 *
 * <p>When the GC algorithm is ZGC (matched via {@code algo.contains("ZGC")}),
 * pause-related weights are reduced and an additional
 * "Allocation pressure (ZGC)" axis is included.
 */
public final class GcScoreCalculator {

    private GcScoreCalculator() {}

    /**
     * Convenience overload — infers ZGC from the analysis's cause-breakdown keys.
     * Callers that know the algorithm should use {@link #compute(GcLogAnalysis, String)}.
     */
    public static GcScoreResult compute(GcLogAnalysis a) {
        String algo = inferAlgorithm(a);
        return compute(a, algo);
    }

    /**
     * Full entry-point. Pass the GC algorithm string (e.g. {@code "ZGC"},
     * {@code "ZGC (Generational)"}, {@code "G1GC"}). An empty string or null
     * means "unknown / use standard weights".
     */
    public static GcScoreResult compute(GcLogAnalysis a, String gcAlgorithm) {
        boolean isZgc = gcAlgorithm != null && gcAlgorithm.toUpperCase().contains("ZGC");

        List<AxisScore> axes = new ArrayList<>();

        axes.add(isZgc ? scorePauseP99Zgc(a.p99PauseMs()) : scorePauseP99(a.p99PauseMs()));
        axes.add(isZgc ? scorePauseTailZgc(a.maxPauseMs()) : scorePauseTail(a.maxPauseMs()));
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

        if (isZgc) {
            axes.add(scoreZgcAllocationPressure(a.totalEvents(), a.durationSec()));
        }

        int overall = weightedOverall(axes, isZgc);
        String grade = grade(overall);
        String summary = summaryFor(grade);
        List<String> hints = selectHints(axes, isZgc);

        return new GcScoreResult(axes, overall, grade, summary, hints);
    }

    /**
     * Infers ZGC from cause-breakdown keys when no explicit algorithm is provided.
     * Returns "ZGC" if any cause key contains "ZGC", otherwise empty string.
     */
    public static String inferAlgorithm(GcLogAnalysis a) {
        if (a.causeBreakdown() != null) {
            for (String key : a.causeBreakdown().keySet()) {
                if (key.toUpperCase().contains("ZGC")) return "ZGC";
            }
        }
        return "";
    }

    // ── Per-axis scoring ────────────────────────────────────────────────────

    /**
     * ZGC-aware p99 scorer: thresholds are 10× tighter (ZGC targets sub-ms pauses).
     * A 5 ms p99 scores WARN rather than PASS, and 50 ms scores FAIL.
     */
    static AxisScore scorePauseP99Zgc(long p99Ms) {
        int score;
        AxisScore.Verdict verdict;
        if (p99Ms <= 5)        { score = 100; verdict = AxisScore.Verdict.PASS; }
        else if (p99Ms <= 20)  { score = (int) (100 - (p99Ms - 5) * 60.0 / 15); verdict = AxisScore.Verdict.WARN; }
        else if (p99Ms <= 100) { score = Math.max(0, (int) (40 - (p99Ms - 20) * 40.0 / 80)); verdict = AxisScore.Verdict.FAIL; }
        else                   { score = 0; verdict = AxisScore.Verdict.FAIL; }
        return new AxisScore("Pause p99", p99Ms, "ms", "< 5 ms (ZGC)", score, verdict, true);
    }

    /**
     * ZGC-aware tail scorer: thresholds are 50× tighter than G1 (ZGC targets sub-ms pauses).
     * PASS: &lt;= 10 ms, WARN: 11–49 ms, FAIL: &gt;= 50 ms.
     */
    static AxisScore scorePauseTailZgc(long maxMs) {
        int score;
        AxisScore.Verdict verdict;
        if (maxMs <= 10)        { score = 100; verdict = AxisScore.Verdict.PASS; }
        else if (maxMs < 50)    { score = (int) (100 - (maxMs - 10) * 40.0 / 39); verdict = AxisScore.Verdict.WARN; }
        else if (maxMs <= 200)  { score = Math.max(0, (int) (60 - (maxMs - 50) * 60.0 / 150)); verdict = AxisScore.Verdict.FAIL; }
        else                    { score = 0; verdict = AxisScore.Verdict.FAIL; }
        return new AxisScore("Pause tail (max)", maxMs, "ms", "< 10 ms (ZGC)", score, verdict, true);
    }

    /**
     * ZGC cycle-frequency axis. Penalty when cycles exceed 0.5/s (a cycle every 2s or faster
     * suggests the allocator is under sustained pressure).
     */
    static AxisScore scoreZgcAllocationPressure(int gcCount, double durationSec) {
        double cyclesPerSec = durationSec > 0 ? gcCount / durationSec : 0;
        int score;
        AxisScore.Verdict verdict;
        if (cyclesPerSec < 0.2)      { score = 100; verdict = AxisScore.Verdict.PASS; }
        else if (cyclesPerSec < 0.5) { score = 80;  verdict = AxisScore.Verdict.PASS; }
        else if (cyclesPerSec < 1.0) { score = (int) (60 - (cyclesPerSec - 0.5) * 40); verdict = AxisScore.Verdict.WARN; }
        else                         { score = Math.max(0, (int) (40 - cyclesPerSec * 10)); verdict = AxisScore.Verdict.FAIL; }
        return new AxisScore("Allocation pressure (ZGC)", cyclesPerSec, "/s", "< 0.5/s", score, verdict, true);
    }

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

    /**
     * Backward-compatible overload used by tests that call the method directly.
     */
    static int weightedOverall(List<AxisScore> axes) {
        return weightedOverall(axes, false);
    }

    /**
     * Weighted overall score (0–100).
     *
     * <p>Standard weights (indices 0–5): p99=0.25, tail=0.15, throughput=0.25,
     * fullGcFreq=0.15, allocRate=0.10, promoRatio=0.10.
     *
     * <p>ZGC weights: pause axes are halved (p99=0.12, tail=0.08) and the saved
     * weight is redistributed to throughput (0.30) and the new
     * "Allocation pressure (ZGC)" axis (index 6) at 0.15. This reflects that
     * sub-ms ZGC pauses are expected by design and should not dominate the score.
     */
    static int weightedOverall(List<AxisScore> axes, boolean isZgc) {
        // Standard weights: [p99, tail, throughput, fullGcFreq, allocRate, promoRatio]
        // ZGC weights:      [p99, tail, throughput, fullGcFreq, allocRate, promoRatio, allocPressure]
        double[] weights = isZgc
                ? new double[]{0.12, 0.08, 0.30, 0.15, 0.10, 0.10, 0.15}
                : new double[]{0.25, 0.15, 0.25, 0.15, 0.10, 0.10};
        double sum = 0, wAvail = 0;
        for (int i = 0; i < axes.size(); i++) {
            AxisScore ax = axes.get(i);
            if (!ax.available()) continue;
            double w = i < weights.length ? weights[i] : 0;
            sum += ax.score() * w;
            wAvail += w;
        }
        if (wAvail <= 0) return 0;
        double availableAvg = sum / wAvail;
        // Missing axes inherit the available average → preserves total-weight=1.00 semantics
        // while remaining honest about uncertainty.
        return (int) Math.round(sum + availableAvg * (1.0 - wAvail));
    }

    static String grade(int overall) {
        if (overall >= 90) return "A";
        if (overall >= 75) return "B";
        if (overall >= 60) return "C";
        if (overall >= 40) return "D";
        return "F";
    }

    static String summaryFor(String grade) {
        switch (grade) {
            case "A": return "excellent, no tuning required";
            case "B": return "good, minor tuning opportunities";
            case "C": return "acceptable, tuning recommended";
            case "D": return "poor, tuning required";
            default: return "critical, immediate action required";
        }
    }

    // ── Hint selection ──────────────────────────────────────────────────────

    /**
     * Backward-compatible overload.
     */
    static List<String> selectHints(List<AxisScore> axes) {
        return selectHints(axes, false);
    }

    static List<String> selectHints(List<AxisScore> axes, boolean isZgc) {
        List<String> hints = new ArrayList<>();
        for (AxisScore ax : axes) {
            if (ax.verdict() == AxisScore.Verdict.PASS || ax.verdict() == AxisScore.Verdict.NA) continue;
            String hint = isZgc ? hintForZgc(ax) : hintFor(ax);
            if (hint != null) hints.add(hint);
            if (hints.size() >= 3) break;
        }
        if (hints.isEmpty()) hints.add("GC health looks good — no changes needed.");
        return hints;
    }

    private static String hintFor(AxisScore ax) {
        switch (ax.name()) {
            case "Pause p99": return "Long p99 pauses — try -XX:MaxGCPauseMillis=200 or raise heap to absorb allocation bursts";
            case "Pause tail (max)": return "Long tail pauses suggest concurrent-mode failure or humongous allocation; consider G1/ZGC or increase heap";
            case "Throughput": return "Low throughput — raise -Xmx or consider a concurrent collector (G1/ZGC/Shenandoah)";
            case "Full GC frequency": return "Frequent Full GC — heap too small or memory leak; inspect with argus heap / argus diff";
            case "Allocation rate": return "Very high allocation rate — run argus flame --type=alloc to find hot allocation sites";
            case "Promotion ratio": return "Survivor overflow promoting short-lived objects; raise -XX:MaxTenuringThreshold or -XX:SurvivorRatio";
            default: return null;
        }
    }

    private static String hintForZgc(AxisScore ax) {
        switch (ax.name()) {
            case "Pause p99": return "Elevated ZGC p99 pauses — raise -Xmx or set -XX:SoftMaxHeapSize to reduce GC pressure; consider -XX:ConcGCThreads";
            case "Pause tail (max)": return "Elevated ZGC max pause — check for allocation stalls; increase heap with -Xmx or tune -XX:SoftMaxHeapSize";
            case "Throughput": return "Low throughput under ZGC — raise -Xmx or increase -XX:ConcGCThreads to keep concurrent work ahead of mutators";
            case "Full GC frequency": return "Frequent Full GC — heap too small or memory leak; inspect with argus heap / argus diff";
            case "Allocation rate": return "Very high allocation rate under ZGC — run argus flame --type=alloc to find hot allocation sites";
            case "Promotion ratio": return "High promotion ratio under ZGC — review object lifetime patterns; consider raising -Xmx";
            case "Allocation pressure (ZGC)": return "ZGC cycle frequency too high (> 0.5/s) — heap is undersized; raise -Xmx or tune -XX:SoftMaxHeapSize and -XX:ConcGCThreads";
            default: return null;
        }
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

    static List<String> zgcAxisOrder() {
        return Arrays.asList(
                "Pause p99",
                "Pause tail (max)",
                "Throughput",
                "Full GC frequency",
                "Allocation rate",
                "Promotion ratio",
                "Allocation pressure (ZGC)");
    }
}
