package io.argus.cli.gclog;

import java.util.List;
import java.util.Map;

/**
 * Analysis result of a GC log file.
 */
public final class GcLogAnalysis {
    private final int totalEvents;
    private final int pauseEvents;
    private final int fullGcEvents;
    private final int concurrentEvents;
    private final double durationSec;
    private final double throughputPercent;
    private final long totalPauseMs;
    private final long maxPauseMs;
    private final long p50PauseMs;
    private final long p95PauseMs;
    private final long p99PauseMs;
    private final long avgPauseMs;
    private final long peakHeapKB;
    private final long avgHeapAfterKB;
    private final Map<String, CauseStats> causeBreakdown;
    private final List<TuningRecommendation> recommendations;
    private final GcRateAnalyzer.RateAnalysis rateAnalysis;
    private final GcLeakDetector.LeakAnalysis leakAnalysis;

    public GcLogAnalysis(int totalEvents, int pauseEvents, int fullGcEvents, int concurrentEvents,
                         double durationSec, double throughputPercent,
                         long totalPauseMs, long maxPauseMs, long p50PauseMs, long p95PauseMs,
                         long p99PauseMs, long avgPauseMs, long peakHeapKB, long avgHeapAfterKB,
                         Map<String, CauseStats> causeBreakdown,
                         List<TuningRecommendation> recommendations) {
        this(totalEvents, pauseEvents, fullGcEvents, concurrentEvents,
                durationSec, throughputPercent, totalPauseMs, maxPauseMs,
                p50PauseMs, p95PauseMs, p99PauseMs, avgPauseMs, peakHeapKB, avgHeapAfterKB,
                causeBreakdown, recommendations, null, null);
    }

    public GcLogAnalysis(int totalEvents, int pauseEvents, int fullGcEvents, int concurrentEvents,
                         double durationSec, double throughputPercent,
                         long totalPauseMs, long maxPauseMs, long p50PauseMs, long p95PauseMs,
                         long p99PauseMs, long avgPauseMs, long peakHeapKB, long avgHeapAfterKB,
                         Map<String, CauseStats> causeBreakdown,
                         List<TuningRecommendation> recommendations,
                         GcRateAnalyzer.RateAnalysis rateAnalysis,
                         GcLeakDetector.LeakAnalysis leakAnalysis) {
        this.totalEvents = totalEvents;
        this.pauseEvents = pauseEvents;
        this.fullGcEvents = fullGcEvents;
        this.concurrentEvents = concurrentEvents;
        this.durationSec = durationSec;
        this.throughputPercent = throughputPercent;
        this.totalPauseMs = totalPauseMs;
        this.maxPauseMs = maxPauseMs;
        this.p50PauseMs = p50PauseMs;
        this.p95PauseMs = p95PauseMs;
        this.p99PauseMs = p99PauseMs;
        this.avgPauseMs = avgPauseMs;
        this.peakHeapKB = peakHeapKB;
        this.avgHeapAfterKB = avgHeapAfterKB;
        this.causeBreakdown = causeBreakdown;
        this.recommendations = recommendations;
        this.rateAnalysis = rateAnalysis;
        this.leakAnalysis = leakAnalysis;
    }

    public int totalEvents() { return totalEvents; }
    public int pauseEvents() { return pauseEvents; }
    public int fullGcEvents() { return fullGcEvents; }
    public int concurrentEvents() { return concurrentEvents; }
    public double durationSec() { return durationSec; }
    public double throughputPercent() { return throughputPercent; }
    public long totalPauseMs() { return totalPauseMs; }
    public long maxPauseMs() { return maxPauseMs; }
    public long p50PauseMs() { return p50PauseMs; }
    public long p95PauseMs() { return p95PauseMs; }
    public long p99PauseMs() { return p99PauseMs; }
    public long avgPauseMs() { return avgPauseMs; }
    public long peakHeapKB() { return peakHeapKB; }
    public long avgHeapAfterKB() { return avgHeapAfterKB; }
    public Map<String, CauseStats> causeBreakdown() { return causeBreakdown; }
    public List<TuningRecommendation> recommendations() { return recommendations; }
    public GcRateAnalyzer.RateAnalysis rateAnalysis() { return rateAnalysis; }
    public GcLeakDetector.LeakAnalysis leakAnalysis() { return leakAnalysis; }

    public static final class CauseStats {
        private final String cause;
        private final int count;
        private final long totalMs;
        private final long maxMs;
        private final long avgMs;

        public CauseStats(String cause, int count, long totalMs, long maxMs, long avgMs) {
            this.cause = cause; this.count = count; this.totalMs = totalMs;
            this.maxMs = maxMs; this.avgMs = avgMs;
        }

        public String cause() { return cause; }
        public int count() { return count; }
        public long totalMs() { return totalMs; }
        public long maxMs() { return maxMs; }
        public long avgMs() { return avgMs; }
    }

    public static final class TuningRecommendation {
        private final String severity;
        private final String problem;
        private final String suggestion;
        private final String flag;

        public TuningRecommendation(String severity, String problem, String suggestion, String flag) {
            this.severity = severity; this.problem = problem;
            this.suggestion = suggestion; this.flag = flag;
        }

        public String severity() { return severity; }
        public String problem() { return problem; }
        public String suggestion() { return suggestion; }
        public String flag() { return flag; }
    }
}
