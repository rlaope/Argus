package io.argus.diagnostics.gclog;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes allocation rate and promotion rate from GC events.
 */
public final class GcRateAnalyzer {

    private static final int NUM_WINDOWS = 10;

    public static RateAnalysis analyze(List<GcEvent> pauseEvents) {
        if (pauseEvents.size() < 2) {
            return new RateAnalysis(0, 0, 0, 0, 0, 0,
                    new double[NUM_WINDOWS], new double[NUM_WINDOWS]);
        }

        double firstTs = pauseEvents.get(0).timestampSec();
        double lastTs = pauseEvents.get(pauseEvents.size() - 1).timestampSec();
        double duration = Math.max(lastTs - firstTs, 0.001);
        double windowSize = duration / NUM_WINDOWS;

        // Accumulators for aggregate stats
        double allocSum = 0, allocMax = 0;
        int allocCount = 0;
        double promoSum = 0, promoMax = 0;
        int promoCount = 0;
        long totalAllocatedKB = 0;
        long totalReclaimedKB = 0;

        // Buckets for sparkline windows (computed in single pass)
        List<List<Double>> allocBuckets = new ArrayList<>();
        List<List<Double>> promoBuckets = new ArrayList<>();
        for (int i = 0; i < NUM_WINDOWS; i++) {
            allocBuckets.add(new ArrayList<>());
            promoBuckets.add(new ArrayList<>());
        }

        boolean skipNext = false;
        for (int i = 1; i < pauseEvents.size(); i++) {
            GcEvent prev = pauseEvents.get(i - 1);
            GcEvent cur = pauseEvents.get(i);

            // Skip humongous events: G1 humongous allocations distort heap-delta
            // measurements because the JVM accounts for humongous regions differently.
            // When either boundary event is humongous, skip this interval and flag
            // the next one too (the carry-over would otherwise pollute the next delta).
            boolean prevHumongous = isHumongous(prev);
            boolean curHumongous = isHumongous(cur);
            if (skipNext || prevHumongous || curHumongous) {
                skipNext = curHumongous; // flag next interval if cur is humongous
                continue;
            }
            skipNext = false;

            double timeDelta = cur.timestampSec() - prev.timestampSec();
            // Skip sub-millisecond intervals: these are parser artefacts (two events
            // logged at effectively the same timestamp) and produce nonsensical rates.
            if (timeDelta < 0.001) continue;

            int bucket = Math.min(NUM_WINDOWS - 1,
                    (int) ((cur.timestampSec() - firstTs) / windowSize));

            // Allocation rate
            long allocatedKB = cur.heapBeforeKB() - prev.heapAfterKB();
            if (allocatedKB > 0) {
                double allocRate = allocatedKB / timeDelta;
                totalAllocatedKB += allocatedKB;

                // Promotion rate (same interval, so we can clamp promo ≤ alloc here)
                double promoRate = 0;
                if (!prev.isFullGc() && !cur.isFullGc()) {
                    long promotedKB = cur.heapAfterKB() - prev.heapAfterKB();
                    if (promotedKB > 0) {
                        // Clamp: promotion cannot exceed allocation in the same window
                        promoRate = Math.min(promotedKB / timeDelta, allocRate);
                    }
                }

                allocSum += allocRate;
                allocCount++;
                if (allocRate > allocMax) allocMax = allocRate;
                allocBuckets.get(bucket).add(allocRate);

                if (promoRate > 0) {
                    promoSum += promoRate;
                    promoCount++;
                    if (promoRate > promoMax) promoMax = promoRate;
                    promoBuckets.get(bucket).add(promoRate);
                }
            }

            totalReclaimedKB += Math.max(0, cur.reclaimedKB());
        }

        double avgAlloc = allocCount > 0 ? allocSum / allocCount : 0;
        double avgPromo = promoCount > 0 ? promoSum / promoCount : 0;
        double reclaimEfficiency = totalAllocatedKB > 0
                ? Math.min(100.0, (double) totalReclaimedKB / totalAllocatedKB * 100) : 0;
        double promoAllocRatio = avgAlloc > 0
                ? Math.min(100.0, avgPromo / avgAlloc * 100) : 0;

        double[] allocWindows = averageBuckets(allocBuckets);
        double[] promoWindows = averageBuckets(promoBuckets);

        return new RateAnalysis(avgAlloc, allocMax, avgPromo, promoMax,
                reclaimEfficiency, promoAllocRatio, allocWindows, promoWindows);
    }

    private static boolean isHumongous(GcEvent event) {
        String cause = event.cause();
        return cause != null && cause.toLowerCase().contains("humongous");
    }

    private static double[] averageBuckets(List<List<Double>> buckets) {
        double[] windows = new double[buckets.size()];
        for (int i = 0; i < buckets.size(); i++) {
            List<Double> b = buckets.get(i);
            if (!b.isEmpty()) {
                double sum = 0;
                for (double v : b) sum += v;
                windows[i] = sum / b.size();
            }
        }
        return windows;
    }

    public static final class RateAnalysis {
        private final double allocationRateKBPerSec;
        private final double peakAllocationRateKBPerSec;
        private final double promotionRateKBPerSec;
        private final double peakPromotionRateKBPerSec;
        private final double reclaimEfficiencyPercent;
        private final double promoAllocRatioPercent;
        private final double[] allocationRateWindows;
        private final double[] promotionRateWindows;

        public RateAnalysis(double allocationRateKBPerSec, double peakAllocationRateKBPerSec,
                            double promotionRateKBPerSec, double peakPromotionRateKBPerSec,
                            double reclaimEfficiencyPercent, double promoAllocRatioPercent,
                            double[] allocationRateWindows, double[] promotionRateWindows) {
            this.allocationRateKBPerSec = allocationRateKBPerSec;
            this.peakAllocationRateKBPerSec = peakAllocationRateKBPerSec;
            this.promotionRateKBPerSec = promotionRateKBPerSec;
            this.peakPromotionRateKBPerSec = peakPromotionRateKBPerSec;
            this.reclaimEfficiencyPercent = reclaimEfficiencyPercent;
            this.promoAllocRatioPercent = promoAllocRatioPercent;
            this.allocationRateWindows = allocationRateWindows;
            this.promotionRateWindows = promotionRateWindows;
        }

        public double allocationRateKBPerSec() { return allocationRateKBPerSec; }
        public double peakAllocationRateKBPerSec() { return peakAllocationRateKBPerSec; }
        public double promotionRateKBPerSec() { return promotionRateKBPerSec; }
        public double peakPromotionRateKBPerSec() { return peakPromotionRateKBPerSec; }
        public double reclaimEfficiencyPercent() { return reclaimEfficiencyPercent; }
        public double promoAllocRatioPercent() { return promoAllocRatioPercent; }
        public double[] allocationRateWindows() { return allocationRateWindows; }
        public double[] promotionRateWindows() { return promotionRateWindows; }
    }
}
