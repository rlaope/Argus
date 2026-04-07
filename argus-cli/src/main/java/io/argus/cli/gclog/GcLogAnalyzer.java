package io.argus.cli.gclog;

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

        // Separate pause vs concurrent
        List<GcEvent> pauseEvents = events.stream().filter(e -> !e.isConcurrent()).toList();
        List<GcEvent> concurrentEvents = events.stream().filter(GcEvent::isConcurrent).toList();
        List<GcEvent> fullGcEvents = events.stream().filter(GcEvent::isFullGc).toList();

        // Duration
        double firstTs = events.getFirst().timestampSec();
        double lastTs = events.getLast().timestampSec();
        double durationSec = Math.max(lastTs - firstTs, 0.001);

        // Pause stats
        long totalPauseMs = pauseEvents.stream().mapToLong(GcEvent::pauseMs).sum();
        long maxPauseMs = pauseEvents.stream().mapToLong(GcEvent::pauseMs).max().orElse(0);
        long avgPauseMs = pauseEvents.isEmpty() ? 0 : totalPauseMs / pauseEvents.size();

        // Percentiles
        long[] sortedPauses = pauseEvents.stream().mapToLong(GcEvent::pauseMs).sorted().toArray();
        long p50 = percentile(sortedPauses, 50);
        long p95 = percentile(sortedPauses, 95);
        long p99 = percentile(sortedPauses, 99);

        // Throughput
        double throughput = durationSec > 0 ? (1.0 - totalPauseMs / (durationSec * 1000)) * 100 : 100;

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
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .forEach(entry -> {
                    List<GcEvent> ces = entry.getValue();
                    long total = ces.stream().mapToLong(GcEvent::pauseMs).sum();
                    long max = ces.stream().mapToLong(GcEvent::pauseMs).max().orElse(0);
                    long avg = total / ces.size();
                    causeStats.put(entry.getKey(), new GcLogAnalysis.CauseStats(
                            entry.getKey(), ces.size(), total, max, avg));
                });

        // Tuning recommendations
        List<GcLogAnalysis.TuningRecommendation> recs = generateRecommendations(
                pauseEvents, fullGcEvents, throughput, p99, maxPauseMs, peakHeap, causeStats);

        return new GcLogAnalysis(
                events.size(), pauseEvents.size(), fullGcEvents.size(), concurrentEvents.size(),
                durationSec, throughput, totalPauseMs, maxPauseMs, p50, p95, p99, avgPauseMs,
                peakHeap, avgHeapAfter, Map.copyOf(causeStats), List.copyOf(recs));
    }

    private static List<GcLogAnalysis.TuningRecommendation> generateRecommendations(
            List<GcEvent> pauses, List<GcEvent> fullGcs,
            double throughput, long p99, long maxPause, long peakHeapKB,
            Map<String, GcLogAnalysis.CauseStats> causes) {

        List<GcLogAnalysis.TuningRecommendation> recs = new ArrayList<>();

        // Full GC detected
        if (!fullGcs.isEmpty()) {
            long avgFullMs = fullGcs.stream().mapToLong(GcEvent::pauseMs).sum() / fullGcs.size();
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

    private static long percentile(long[] sorted, int pct) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static String suggestHeap(long peakKB) {
        long suggestedMB = (long) (peakKB / 1024.0 * 1.5);
        return suggestedMB >= 1024 ? (suggestedMB / 1024) + "g" : suggestedMB + "m";
    }
}
