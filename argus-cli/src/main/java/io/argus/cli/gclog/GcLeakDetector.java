package io.argus.cli.gclog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Detects memory leaks by analyzing heap-after-GC trend using linear regression.
 */
public final class GcLeakDetector {

    private static final int NUM_WINDOWS = 20;
    private static final int MIN_EVENTS = 10;

    public static LeakAnalysis analyze(List<GcEvent> pauseEvents) {
        List<GcEvent> pauses = pauseEvents;

        if (pauses.size() < MIN_EVENTS) {
            return new LeakAnalysis(false, 0, -1, 0, "None", 0,
                    new double[0], 0, 0);
        }

        double firstTs = pauses.getFirst().timestampSec();
        double lastTs = pauses.getLast().timestampSec();
        double duration = Math.max(lastTs - firstTs, 0.001);
        double windowSize = duration / NUM_WINDOWS;

        // Divide into 20 windows, compute median heapAfterKB per window
        List<List<Long>> buckets = new ArrayList<>();
        for (int i = 0; i < NUM_WINDOWS; i++) buckets.add(new ArrayList<>());

        for (GcEvent e : pauses) {
            int bucket = Math.min(NUM_WINDOWS - 1,
                    (int) ((e.timestampSec() - firstTs) / windowSize));
            buckets.get(bucket).add(e.heapAfterKB());
        }

        // Collect windows that have data
        List<double[]> points = new ArrayList<>(); // [windowIndex, medianHeapKB]
        for (int i = 0; i < NUM_WINDOWS; i++) {
            List<Long> b = buckets.get(i);
            if (!b.isEmpty()) {
                points.add(new double[]{i, median(b)});
            }
        }

        if (points.size() < 3) {
            return new LeakAnalysis(false, 0, -1, 0, "None", 0,
                    new double[0], 0, 0);
        }

        // Least-squares linear regression
        int n = points.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (double[] p : points) {
            sumX += p[0];
            sumY += p[1];
            sumXY += p[0] * p[1];
            sumX2 += p[0] * p[0];
        }
        double denom = n * sumX2 - sumX * sumX;
        double slope = denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;

        // R²
        double meanY = sumY / n;
        double ssTot = 0, ssRes = 0;
        for (double[] p : points) {
            double predicted = slope * p[0] + intercept;
            ssRes += (p[1] - predicted) * (p[1] - predicted);
            ssTot += (p[1] - meanY) * (p[1] - meanY);
        }
        double r2 = ssTot == 0 ? 0 : 1.0 - ssRes / ssTot;
        r2 = Math.max(0, Math.min(1, r2));

        // Convert slope from KB/window to KB/sec
        double growthRateKBPerSec = slope / windowSize;

        boolean leakDetected = slope > 0 && r2 > 0.7;
        double confidencePercent = r2 * 100;

        // OOM estimation
        long heapTotalKB = pauses.stream().mapToLong(GcEvent::heapTotalKB).max().orElse(0);
        double lastMedian = points.getLast()[1];
        double estimatedOomSec = -1;
        if (leakDetected && growthRateKBPerSec > 0 && heapTotalKB > lastMedian) {
            estimatedOomSec = (heapTotalKB - lastMedian) / growthRateKBPerSec;
        }

        // Build trendPoints array (up to 20 sampled median values)
        double[] trendPoints = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            trendPoints[i] = points.get(i)[1];
        }
        double trendMin = Arrays.stream(trendPoints).min().orElse(0);
        double trendMax = Arrays.stream(trendPoints).max().orElse(0);

        // Staircase detection
        String pattern = "None";
        int staircaseSteps = 0;
        if (leakDetected) {
            staircaseSteps = countStaircaseSteps(trendPoints);
            pattern = staircaseSteps >= 3 ? "Staircase" : "Gradual";
        }

        return new LeakAnalysis(leakDetected, growthRateKBPerSec, estimatedOomSec,
                confidencePercent, pattern, staircaseSteps, trendPoints, trendMin, trendMax);
    }

    private static double median(List<Long> values) {
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[mid - 1] + sorted[mid]) / 2.0
                : sorted[mid];
    }

    private static int countStaircaseSteps(double[] points) {
        int steps = 0;
        for (int i = 0; i < points.length - 1; i++) {
            if (points[i] > 0 && points[i + 1] > points[i] * 1.05) {
                // Check no recovery below points[i] after this jump
                boolean recovered = false;
                for (int j = i + 2; j < points.length; j++) {
                    if (points[j] < points[i]) {
                        recovered = true;
                        break;
                    }
                }
                if (!recovered) steps++;
            }
        }
        return steps;
    }

    public static final class LeakAnalysis {
        private final boolean leakDetected;
        private final double heapGrowthRateKBPerSec;
        private final double estimatedOomSec;
        private final double confidencePercent;
        private final String pattern;
        private final int staircaseSteps;
        private final double[] trendPoints;
        private final double trendMinKB;
        private final double trendMaxKB;

        public LeakAnalysis(boolean leakDetected, double heapGrowthRateKBPerSec,
                            double estimatedOomSec, double confidencePercent,
                            String pattern, int staircaseSteps, double[] trendPoints,
                            double trendMinKB, double trendMaxKB) {
            this.leakDetected = leakDetected;
            this.heapGrowthRateKBPerSec = heapGrowthRateKBPerSec;
            this.estimatedOomSec = estimatedOomSec;
            this.confidencePercent = confidencePercent;
            this.pattern = pattern;
            this.staircaseSteps = staircaseSteps;
            this.trendPoints = trendPoints;
            this.trendMinKB = trendMinKB;
            this.trendMaxKB = trendMaxKB;
        }

        public boolean leakDetected() { return leakDetected; }
        public double heapGrowthRateKBPerSec() { return heapGrowthRateKBPerSec; }
        public double estimatedOomSec() { return estimatedOomSec; }
        public double confidencePercent() { return confidencePercent; }
        public String pattern() { return pattern; }
        public int staircaseSteps() { return staircaseSteps; }
        public double[] trendPoints() { return trendPoints; }
        public double trendMinKB() { return trendMinKB; }
        public double trendMaxKB() { return trendMaxKB; }
    }
}
