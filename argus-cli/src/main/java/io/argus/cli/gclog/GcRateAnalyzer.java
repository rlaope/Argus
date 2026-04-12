package io.argus.cli.gclog;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes allocation rate and promotion rate from GC events.
 */
public final class GcRateAnalyzer {

    public static RateAnalysis analyze(List<GcEvent> pauseEvents) {
        List<GcEvent> pauses = pauseEvents;

        if (pauses.size() < 2) {
            return new RateAnalysis(0, 0, 0, 0, 0, 0,
                    new double[10], new double[10]);
        }

        List<Double> allocRates = new ArrayList<>();
        List<Double> promoRates = new ArrayList<>();
        long totalAllocatedKB = 0;
        long totalReclaimedKB = 0;

        for (int i = 1; i < pauses.size(); i++) {
            GcEvent prev = pauses.get(i - 1);
            GcEvent cur = pauses.get(i);

            double timeDelta = cur.timestampSec() - prev.timestampSec();
            if (timeDelta <= 0) continue;

            // Allocation rate: heap grew between prev after-GC and this before-GC
            long allocatedKB = cur.heapBeforeKB() - prev.heapAfterKB();
            if (allocatedKB > 0) {
                allocRates.add(allocatedKB / timeDelta);
                totalAllocatedKB += allocatedKB;
            }

            totalReclaimedKB += Math.max(0, cur.reclaimedKB());

            // Promotion rate: old gen growth between consecutive non-Full GC events
            if (!prev.isFullGc() && !cur.isFullGc()) {
                long promotedKB = cur.heapAfterKB() - prev.heapAfterKB();
                if (promotedKB > 0) {
                    promoRates.add(promotedKB / timeDelta);
                }
            }
        }

        double avgAlloc = allocRates.isEmpty() ? 0
                : allocRates.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double peakAlloc = allocRates.isEmpty() ? 0
                : allocRates.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double avgPromo = promoRates.isEmpty() ? 0
                : promoRates.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double peakPromo = promoRates.isEmpty() ? 0
                : promoRates.stream().mapToDouble(Double::doubleValue).max().orElse(0);

        double reclaimEfficiency = totalAllocatedKB > 0
                ? (double) totalReclaimedKB / totalAllocatedKB * 100 : 0;
        double promoAllocRatio = avgAlloc > 0 ? avgPromo / avgAlloc * 100 : 0;

        double[] allocWindows = computeWindows(pauses, true);
        double[] promoWindows = computeWindows(pauses, false);

        return new RateAnalysis(avgAlloc, peakAlloc, avgPromo, peakPromo,
                reclaimEfficiency, promoAllocRatio, allocWindows, promoWindows);
    }

    private static double[] computeWindows(List<GcEvent> pauses, boolean isAlloc) {
        double[] windows = new double[10];
        if (pauses.size() < 2) return windows;

        double firstTs = pauses.getFirst().timestampSec();
        double lastTs = pauses.getLast().timestampSec();
        double duration = Math.max(lastTs - firstTs, 0.001);
        double windowSize = duration / 10.0;

        List<List<Double>> buckets = new ArrayList<>();
        for (int i = 0; i < 10; i++) buckets.add(new ArrayList<>());

        for (int i = 1; i < pauses.size(); i++) {
            GcEvent prev = pauses.get(i - 1);
            GcEvent cur = pauses.get(i);
            double timeDelta = cur.timestampSec() - prev.timestampSec();
            if (timeDelta <= 0) continue;

            int bucket = Math.min(9, (int) ((cur.timestampSec() - firstTs) / windowSize));

            if (isAlloc) {
                long allocatedKB = cur.heapBeforeKB() - prev.heapAfterKB();
                if (allocatedKB > 0) buckets.get(bucket).add(allocatedKB / timeDelta);
            } else {
                if (!prev.isFullGc() && !cur.isFullGc()) {
                    long promotedKB = cur.heapAfterKB() - prev.heapAfterKB();
                    if (promotedKB > 0) buckets.get(bucket).add(promotedKB / timeDelta);
                }
            }
        }

        for (int i = 0; i < 10; i++) {
            List<Double> b = buckets.get(i);
            windows[i] = b.isEmpty() ? 0
                    : b.stream().mapToDouble(Double::doubleValue).average().orElse(0);
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
