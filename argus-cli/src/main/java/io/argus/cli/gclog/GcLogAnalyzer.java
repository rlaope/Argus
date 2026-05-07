package io.argus.cli.gclog;

import io.argus.cli.render.RichRenderer;

import java.util.*;

/**
 * Analyzes parsed GC events and produces statistics, cause breakdown,
 * and tuning recommendations.
 */
public final class GcLogAnalyzer {

    public static GcLogAnalysis analyze(List<GcEvent> events) {
        if (events.isEmpty()) {
            return new GcLogAnalysis(0, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), List.of());
        }

        // Single-pass partition into pause / concurrent / fullGc
        List<GcEvent> pauseEvents = new ArrayList<>();
        List<GcEvent> concurrentEvents = new ArrayList<>();
        List<GcEvent> fullGcEvents = new ArrayList<>();
        for (GcEvent e : events) {
            if (e.isConcurrent()) {
                concurrentEvents.add(e);
            } else {
                pauseEvents.add(e);
                if (e.isFullGc()) fullGcEvents.add(e);
            }
        }

        // Duration — derive from min/max so out-of-order events (or wrapped ISO baselines) don't fold to 0.
        double firstTs = events.stream().mapToDouble(GcEvent::timestampSec).min().orElse(0);
        double lastTs = events.stream().mapToDouble(GcEvent::timestampSec).max().orElse(0);
        double durationSec = Math.max(lastTs - firstTs, 0.001);

        // Pause stats — keep totals as double; sub-millisecond pauses (ZGC/Shenandoah)
        // would otherwise truncate to 0 in long arithmetic.
        double totalPauseMsD = pauseEvents.stream().mapToDouble(GcEvent::pauseMs).sum();
        double maxPauseMsD = pauseEvents.stream().mapToDouble(GcEvent::pauseMs).max().orElse(0);
        long totalPauseMs = Math.round(totalPauseMsD);
        long maxPauseMs = Math.round(maxPauseMsD);
        long avgPauseMs = pauseEvents.isEmpty() ? 0 : Math.round(totalPauseMsD / pauseEvents.size());

        // Percentiles
        double[] sortedPauses = pauseEvents.stream().mapToDouble(GcEvent::pauseMs).sorted().toArray();
        long p50 = (long) percentile(sortedPauses, 50);
        long p95 = (long) percentile(sortedPauses, 95);
        long p99 = (long) percentile(sortedPauses, 99);

        // Throughput
        double throughput = durationSec > 0
                ? Math.max(0, Math.min(100, (1.0 - totalPauseMs / (durationSec * 1000)) * 100))
                : 100;

        // Heap stats
        long peakHeap = events.stream().mapToLong(GcEvent::heapBeforeKB).max().orElse(0);
        long avgHeapAfter = pauseEvents.isEmpty() ? 0
                : pauseEvents.stream().mapToLong(GcEvent::heapAfterKB).sum() / pauseEvents.size();

        // Cause breakdown
        Map<String, List<GcEvent>> byCause = new LinkedHashMap<>();
        for (GcEvent e : pauseEvents) {
            byCause.computeIfAbsent(e.cause(), k -> new ArrayList<>()).add(e);
        }
        Map<String, GcLogAnalysis.CauseStats> causeStats = new LinkedHashMap<>();
        byCause.entrySet().stream()
                .sorted((a, b) -> Long.compare(
                        Math.round(b.getValue().stream().mapToDouble(GcEvent::pauseMs).sum()),
                        Math.round(a.getValue().stream().mapToDouble(GcEvent::pauseMs).sum())))
                .forEach(entry -> {
                    List<GcEvent> ces = entry.getValue();
                    double totalD = ces.stream().mapToDouble(GcEvent::pauseMs).sum();
                    double maxD = ces.stream().mapToDouble(GcEvent::pauseMs).max().orElse(0);
                    long total = Math.round(totalD);
                    long max = Math.round(maxD);
                    long avg = Math.round(totalD / ces.size());
                    // p99: use sorted-tail percentile; for small buckets (<100) this equals the
                    // highest-pause value, which is an intentionally conservative proxy.
                    double[] sortedCause = ces.stream().mapToDouble(GcEvent::pauseMs).sorted().toArray();
                    long p99cause = (long) percentile(sortedCause, 99);
                    causeStats.put(entry.getKey(), new GcLogAnalysis.CauseStats(
                            entry.getKey(), ces.size(), total, max, avg, p99cause));
                });

        // Tuning recommendations
        List<GcLogAnalysis.TuningRecommendation> recs = generateRecommendations(
                pauseEvents, fullGcEvents, throughput, p99, maxPauseMs, peakHeap, causeStats);

        // Rate and leak analysis (pass pre-filtered pauseEvents — avoids redundant filtering)
        GcRateAnalyzer.RateAnalysis rates = GcRateAnalyzer.analyze(pauseEvents);
        GcLeakDetector.LeakAnalysis leak = GcLeakDetector.analyze(pauseEvents);

        // Leak recommendation
        if (leak.leakDetected()) {
            recs.add(new GcLogAnalysis.TuningRecommendation("CRITICAL",
                    String.format("Memory leak suspected (%.0f%% confidence), heap growing at %s/min",
                            leak.confidencePercent(), RichRenderer.formatRate(leak.heapGrowthRateKBPerSec() * 60)),
                    "Heap baseline is rising. Check for unclosed resources or growing caches.",
                    "-XX:+HeapDumpOnOutOfMemoryError"));
        }

        // High promotion ratio recommendation
        if (rates.promoAllocRatioPercent() > 5) {
            recs.add(new GcLogAnalysis.TuningRecommendation("WARNING",
                    String.format("High promotion ratio %.1f%% (target: <5%%)", rates.promoAllocRatioPercent()),
                    "Objects are surviving young gen too quickly. Increase young gen size.",
                    "-XX:NewRatio=2"));
        }

        return new GcLogAnalysis(
                events.size(), pauseEvents.size(), fullGcEvents.size(), concurrentEvents.size(),
                durationSec, throughput, totalPauseMs, maxPauseMs, p50, p95, p99, avgPauseMs,
                peakHeap, avgHeapAfter, Map.copyOf(causeStats), List.copyOf(recs), rates, leak);
    }

    private static List<GcLogAnalysis.TuningRecommendation> generateRecommendations(
            List<GcEvent> pauses, List<GcEvent> fullGcs,
            double throughput, long p99, long maxPause, long peakHeapKB,
            Map<String, GcLogAnalysis.CauseStats> causes) {

        List<GcLogAnalysis.TuningRecommendation> recs = new ArrayList<>();

        // Full GC detected
        if (!fullGcs.isEmpty()) {
            long avgFullMs = (long) (fullGcs.stream().mapToDouble(GcEvent::pauseMs).sum() / fullGcs.size());
            recs.add(new GcLogAnalysis.TuningRecommendation("CRITICAL",
                    String.format("%d Full GC events detected (avg %dms)", fullGcs.size(), avgFullMs),
                    "Full GC stops the entire application. Increase heap or tune promotion.",
                    "-Xmx" + suggestHeap(peakHeapKB)));
        }

        // Low throughput
        if (throughput < 95) {
            recs.add(new GcLogAnalysis.TuningRecommendation("WARNING",
                    String.format("GC throughput %.1f%% (target: >95%%)", throughput),
                    "Too much time spent in GC. Increase heap size or optimize allocation patterns.",
                    "-Xmx" + suggestHeap(peakHeapKB)));
        }

        // High p99 pause
        if (p99 > 200) {
            recs.add(new GcLogAnalysis.TuningRecommendation("WARNING",
                    String.format("p99 pause time %dms (target: <200ms)", p99),
                    "For latency-sensitive workloads, tune MaxGCPauseMillis or consider ZGC.",
                    "-XX:MaxGCPauseMillis=200"));
        }

        // Humongous allocations
        for (var entry : causes.entrySet()) {
            String cause = entry.getKey().toLowerCase();
            if (cause.contains("humongous")) {
                int count = entry.getValue().count();
                double pct = pauses.isEmpty() ? 0 : (double) count / pauses.size() * 100;
                recs.add(new GcLogAnalysis.TuningRecommendation("WARNING",
                        String.format("Humongous allocations: %d events (%.0f%%)", count, pct),
                        "Large objects bypass young gen. Increase G1 region size.",
                        "-XX:G1HeapRegionSize=16m"));
            }
            if (cause.contains("metadata") || cause.contains("metaspace")) {
                recs.add(new GcLogAnalysis.TuningRecommendation("WARNING",
                        "Metaspace GC triggered (" + entry.getValue().count() + " events)",
                        "Check for ClassLoader leaks. Set explicit metaspace limit.",
                        "-XX:MaxMetaspaceSize=512m"));
            }
            if (cause.contains("allocation failure") && entry.getValue().avgMs() > 100) {
                recs.add(new GcLogAnalysis.TuningRecommendation("INFO",
                        "Allocation failures with avg " + entry.getValue().avgMs() + "ms pause",
                        "Young gen might be too small for the allocation rate.",
                        "-XX:NewRatio=2"));
            }
        }

        // Max pause extreme
        if (maxPause > 1000) {
            recs.add(new GcLogAnalysis.TuningRecommendation("CRITICAL",
                    String.format("Max pause %dms (>1 second)", maxPause),
                    "Consider ZGC for sub-millisecond pauses on large heaps.",
                    "-XX:+UseZGC"));
        }

        return recs;
    }

    private static double percentile(double[] sorted, int pct) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static String suggestHeap(long peakKB) {
        long suggestedMB = (long) (peakKB / 1024.0 * 1.5);
        return suggestedMB >= 1024 ? (suggestedMB / 1024) + "g" : suggestedMB + "m";
    }
}
