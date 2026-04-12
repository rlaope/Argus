package io.argus.server.analysis;

import java.util.List;

/**
 * Computes allocation rate, promotion rate, and leak detection metrics
 * from recent GC summaries, using simplified versions of the CLI gclog algorithms.
 *
 * <p>Designed for real-time monitoring with small event windows (last 100 events).
 */
public final class GcMetricsComputer {

    private static final int RATE_WINDOW = 20;
    private static final int LEAK_WINDOW = 50;
    private static final double LEAK_R2_THRESHOLD = 0.7;

    private GcMetricsComputer() {}

    /**
     * Computes GC rate metrics from recent GC summaries.
     *
     * <p>Allocation rate: average of (heapUsedBefore[i] - heapUsedAfter[i-1]) / timeDelta
     * over the last {@value #RATE_WINDOW} consecutive event pairs.
     *
     * <p>Promotion rate: average of positive (heapUsedAfter[i] - heapUsedAfter[i-1]) / timeDelta
     * over the same window.
     *
     * @param summaries ordered list of GC summaries (oldest first)
     * @return computed rate metrics
     */
    public static RateMetrics computeRates(List<GCAnalyzer.GCSummary> summaries) {
        if (summaries.size() < 2) {
            return new RateMetrics(0.0, 0.0);
        }

        // Use last RATE_WINDOW events
        int start = Math.max(0, summaries.size() - RATE_WINDOW);
        List<GCAnalyzer.GCSummary> window = summaries.subList(start, summaries.size());

        double allocSum = 0;
        int allocCount = 0;
        double promoSum = 0;
        int promoCount = 0;

        for (int i = 1; i < window.size(); i++) {
            GCAnalyzer.GCSummary prev = window.get(i - 1);
            GCAnalyzer.GCSummary cur = window.get(i);

            double timeDeltaSec = (cur.timestamp().toEpochMilli() - prev.timestamp().toEpochMilli()) / 1000.0;
            if (timeDeltaSec <= 0) {
                continue;
            }

            // Allocation rate: bytes allocated between GCs, converted to KB/s
            long heapBeforeBytes = cur.heapUsedBefore();
            long prevHeapAfterBytes = prev.heapUsedAfter();
            long allocatedBytes = heapBeforeBytes - prevHeapAfterBytes;
            if (allocatedBytes > 0) {
                double allocKBPerSec = (allocatedBytes / 1024.0) / timeDeltaSec;
                allocSum += allocKBPerSec;
                allocCount++;
            }

            // Promotion rate: positive growth in heap-after-GC between consecutive events
            long promotedBytes = cur.heapUsedAfter() - prev.heapUsedAfter();
            if (promotedBytes > 0) {
                double promoKBPerSec = (promotedBytes / 1024.0) / timeDeltaSec;
                promoSum += promoKBPerSec;
                promoCount++;
            }
        }

        double allocationRate = allocCount > 0 ? allocSum / allocCount : 0.0;
        double promotionRate = promoCount > 0 ? promoSum / promoCount : 0.0;

        return new RateMetrics(allocationRate, promotionRate);
    }

    /**
     * Detects a memory leak by performing linear regression on the last {@value #LEAK_WINDOW}
     * heap-after-GC values. R² > {@value #LEAK_R2_THRESHOLD} with a positive slope is
     * considered a leak.
     *
     * @param summaries ordered list of GC summaries (oldest first)
     * @return computed leak metrics
     */
    public static LeakMetrics detectLeak(List<GCAnalyzer.GCSummary> summaries) {
        if (summaries.size() < 3) {
            return new LeakMetrics(false, 0.0);
        }

        // Use last LEAK_WINDOW events
        int start = Math.max(0, summaries.size() - LEAK_WINDOW);
        List<GCAnalyzer.GCSummary> window = summaries.subList(start, summaries.size());

        // Filter to events with valid heap-after data
        List<GCAnalyzer.GCSummary> valid = window.stream()
                .filter(s -> s.heapUsedAfter() > 0)
                .toList();

        if (valid.size() < 3) {
            return new LeakMetrics(false, 0.0);
        }

        int n = valid.size();
        // x = index, y = heapUsedAfter in KB
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = valid.get(i).heapUsedAfter() / 1024.0;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denom = n * sumX2 - sumX * sumX;
        if (denom == 0) {
            return new LeakMetrics(false, 0.0);
        }

        double slope = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;

        // R² calculation
        double meanY = sumY / n;
        double ssTot = 0, ssRes = 0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = valid.get(i).heapUsedAfter() / 1024.0;
            double predicted = slope * x + intercept;
            ssRes += (y - predicted) * (y - predicted);
            ssTot += (y - meanY) * (y - meanY);
        }

        double r2 = ssTot == 0 ? 0.0 : Math.max(0.0, Math.min(1.0, 1.0 - ssRes / ssTot));

        boolean leakSuspected = slope > 0 && r2 > LEAK_R2_THRESHOLD;
        double confidencePercent = r2 * 100.0;

        return new LeakMetrics(leakSuspected, confidencePercent);
    }

    /**
     * Allocation and promotion rate metrics.
     */
    public static final class RateMetrics {
        private final double allocationRateKBPerSec;
        private final double promotionRateKBPerSec;

        public RateMetrics(double allocationRateKBPerSec, double promotionRateKBPerSec) {
            this.allocationRateKBPerSec = allocationRateKBPerSec;
            this.promotionRateKBPerSec = promotionRateKBPerSec;
        }

        public double allocationRateKBPerSec() { return allocationRateKBPerSec; }
        public double promotionRateKBPerSec() { return promotionRateKBPerSec; }
    }

    /**
     * Leak detection metrics.
     */
    public static final class LeakMetrics {
        private final boolean leakSuspected;
        private final double confidencePercent;

        public LeakMetrics(boolean leakSuspected, double confidencePercent) {
            this.leakSuspected = leakSuspected;
            this.confidencePercent = confidencePercent;
        }

        public boolean leakSuspected() { return leakSuspected; }
        public double confidencePercent() { return confidencePercent; }
    }
}
