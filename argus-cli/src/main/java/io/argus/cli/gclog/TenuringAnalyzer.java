package io.argus.cli.gclog;

import io.argus.cli.model.AgeDistribution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes GC log files for age table entries from debug-level GC logging
 * ({@code -Xlog:gc+age=debug}).
 *
 * <p>Example log lines parsed:
 * <pre>
 * [0.234s][debug][gc,age] GC(0) Desired survivor size 1048576 bytes, new threshold 6 (max threshold 15)
 * [0.234s][debug][gc,age] GC(0) - age   1:     524288 bytes,     524288 total
 * [0.234s][debug][gc,age] GC(0) - age   2:     262144 bytes,     786432 total
 * </pre>
 */
public final class TenuringAnalyzer {

    private static final Pattern DESIRED_LINE = Pattern.compile(
            "GC\\((\\d+)\\) Desired survivor size (\\d+) bytes, new threshold (\\d+) \\(max threshold (\\d+)\\)");
    private static final Pattern AGE_LINE = Pattern.compile(
            "GC\\((\\d+)\\)\\s*-\\s*age\\s+(\\d+):\\s+(\\d+) bytes,\\s+(\\d+) total");
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d+\\.\\d+)s\\]");

    /**
     * Result of tenuring log analysis.
     */
    public static final class TenuringAnalysis {
        private final List<GcAgeSnapshot> snapshots;
        private final List<String> insights;
        private final List<String> recommendations;
        private final boolean prematurePromotionDetected;
        private final boolean survivorOverflowDetected;
        private final int minThreshold;
        private final int maxThresholdSeen;
        private final int suggestedMaxTenuringThreshold;

        public TenuringAnalysis(List<GcAgeSnapshot> snapshots, List<String> insights,
                                List<String> recommendations,
                                boolean prematurePromotionDetected,
                                boolean survivorOverflowDetected,
                                int minThreshold, int maxThresholdSeen,
                                int suggestedMaxTenuringThreshold) {
            this.snapshots = snapshots;
            this.insights = insights;
            this.recommendations = recommendations;
            this.prematurePromotionDetected = prematurePromotionDetected;
            this.survivorOverflowDetected = survivorOverflowDetected;
            this.minThreshold = minThreshold;
            this.maxThresholdSeen = maxThresholdSeen;
            this.suggestedMaxTenuringThreshold = suggestedMaxTenuringThreshold;
        }

        public List<GcAgeSnapshot> snapshots() { return snapshots; }
        public List<String> insights() { return insights; }
        public List<String> recommendations() { return recommendations; }
        public boolean prematurePromotionDetected() { return prematurePromotionDetected; }
        public boolean survivorOverflowDetected() { return survivorOverflowDetected; }
        public int minThreshold() { return minThreshold; }
        public int maxThresholdSeen() { return maxThresholdSeen; }
        public int suggestedMaxTenuringThreshold() { return suggestedMaxTenuringThreshold; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TenuringAnalysis)) return false;
            TenuringAnalysis that = (TenuringAnalysis) o;
            return prematurePromotionDetected == that.prematurePromotionDetected
                    && survivorOverflowDetected == that.survivorOverflowDetected
                    && minThreshold == that.minThreshold
                    && maxThresholdSeen == that.maxThresholdSeen
                    && suggestedMaxTenuringThreshold == that.suggestedMaxTenuringThreshold
                    && java.util.Objects.equals(snapshots, that.snapshots)
                    && java.util.Objects.equals(insights, that.insights)
                    && java.util.Objects.equals(recommendations, that.recommendations);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(snapshots, insights, recommendations,
                    prematurePromotionDetected, survivorOverflowDetected,
                    minThreshold, maxThresholdSeen, suggestedMaxTenuringThreshold);
        }

        @Override
        public String toString() {
            return "TenuringAnalysis[snapshots=" + snapshots + ", insights=" + insights
                    + ", recommendations=" + recommendations
                    + ", prematurePromotionDetected=" + prematurePromotionDetected
                    + ", survivorOverflowDetected=" + survivorOverflowDetected
                    + ", minThreshold=" + minThreshold
                    + ", maxThresholdSeen=" + maxThresholdSeen
                    + ", suggestedMaxTenuringThreshold=" + suggestedMaxTenuringThreshold + "]";
        }
    }

    /**
     * Age distribution captured at a single GC event.
     */
    public static final class GcAgeSnapshot {
        private final int gcId;
        private final double timestampSec;
        private final AgeDistribution distribution;

        public GcAgeSnapshot(int gcId, double timestampSec, AgeDistribution distribution) {
            this.gcId = gcId;
            this.timestampSec = timestampSec;
            this.distribution = distribution;
        }

        public int gcId() { return gcId; }
        public double timestampSec() { return timestampSec; }
        public AgeDistribution distribution() { return distribution; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GcAgeSnapshot)) return false;
            GcAgeSnapshot that = (GcAgeSnapshot) o;
            return gcId == that.gcId
                    && Double.compare(that.timestampSec, timestampSec) == 0
                    && java.util.Objects.equals(distribution, that.distribution);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(gcId, timestampSec, distribution);
        }

        @Override
        public String toString() {
            return "GcAgeSnapshot[gcId=" + gcId + ", timestampSec=" + timestampSec
                    + ", distribution=" + distribution + "]";
        }
    }

    /**
     * Parses a GC log file and returns tenuring analysis.
     * Only processes lines tagged with gc,age (debug level).
     */
    public static TenuringAnalysis analyze(Path logFile) throws java.io.IOException {
        List<String> lines = java.nio.file.Files.readAllLines(logFile);
        return analyzeLines(lines);
    }

    /**
     * Analyzes a list of log lines (testable without file I/O).
     */
    public static TenuringAnalysis analyzeLines(List<String> lines) {
        List<GcAgeSnapshot> snapshots = new ArrayList<>();

        // State for building current snapshot
        int currentGcId = -1;
        double currentTimestamp = 0;
        int currentThreshold = 0;
        int currentMaxThreshold = 15;
        long currentDesiredSize = 0;
        List<AgeDistribution.AgeEntry> currentEntries = new ArrayList<>();

        for (String line : lines) {
            if (!line.contains("gc,age") && !line.contains("gc+age")) continue;

            double ts = extractTimestamp(line);

            Matcher dm = DESIRED_LINE.matcher(line);
            if (dm.find()) {
                int gcId = Integer.parseInt(dm.group(1));
                if (gcId != currentGcId && currentGcId >= 0 && !currentEntries.isEmpty()) {
                    snapshots.add(buildSnapshot(currentGcId, currentTimestamp,
                            currentThreshold, currentMaxThreshold,
                            currentDesiredSize, currentEntries));
                }
                currentGcId = gcId;
                currentTimestamp = ts >= 0 ? ts : currentTimestamp;
                currentDesiredSize = Long.parseLong(dm.group(2));
                currentThreshold = Integer.parseInt(dm.group(3));
                currentMaxThreshold = Integer.parseInt(dm.group(4));
                currentEntries = new ArrayList<>();
                continue;
            }

            Matcher am = AGE_LINE.matcher(line);
            if (am.find()) {
                int gcId = Integer.parseInt(am.group(1));
                if (gcId != currentGcId) continue; // safety guard
                int age = Integer.parseInt(am.group(2));
                long bytes = Long.parseLong(am.group(3));
                long cumulative = Long.parseLong(am.group(4));
                currentEntries.add(new AgeDistribution.AgeEntry(age, bytes, cumulative));
            }
        }

        // Flush last snapshot
        if (currentGcId >= 0 && !currentEntries.isEmpty()) {
            snapshots.add(buildSnapshot(currentGcId, currentTimestamp,
                    currentThreshold, currentMaxThreshold,
                    currentDesiredSize, currentEntries));
        }

        return buildAnalysis(snapshots);
    }

    private static GcAgeSnapshot buildSnapshot(int gcId, double ts, int tt, int mtt,
                                                long desiredSize,
                                                List<AgeDistribution.AgeEntry> entries) {
        long survivorCap = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).cumulativeBytes();
        AgeDistribution dist = new AgeDistribution(List.copyOf(entries), tt, mtt,
                desiredSize, survivorCap);
        return new GcAgeSnapshot(gcId, ts, dist);
    }

    private static TenuringAnalysis buildAnalysis(List<GcAgeSnapshot> snapshots) {
        List<String> insights = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        if (snapshots.isEmpty()) {
            insights.add("No age table data found. Enable -Xlog:gc+age=debug to collect tenuring data.");
            return new TenuringAnalysis(snapshots, insights, recommendations, false, false, 0, 0, -1);
        }

        int minThreshold = Integer.MAX_VALUE;
        int maxThresholdSeen = 0;
        boolean prematurePromotion = false;
        boolean survivorOverflow = false;

        for (GcAgeSnapshot snap : snapshots) {
            int tt = snap.distribution().tenuringThreshold();
            if (tt < minThreshold) minThreshold = tt;
            if (tt > maxThresholdSeen) maxThresholdSeen = tt;
            if (tt == 1) prematurePromotion = true;

            // Survivor overflow: used > desired survivor size
            long used = snap.distribution().survivorCapacity();
            long desired = snap.distribution().desiredSurvivorSize();
            if (desired > 0 && used > desired * 2) survivorOverflow = true;
        }

        // Analyze the last snapshot for current state
        GcAgeSnapshot latest = snapshots.get(snapshots.size() - 1);
        List<AgeDistribution.AgeEntry> entries = latest.distribution().entries();

        // Age-1 ratio insight
        if (!entries.isEmpty()) {
            long total = latest.distribution().survivorCapacity();
            long age1 = entries.get(0).bytes();
            if (total > 0) {
                int age1Pct = (int) (age1 * 100 / total);
                if (age1Pct >= 60) {
                    insights.add(age1Pct + "% of survivor objects die at age 1 (healthy short-lived objects)");
                } else if (age1Pct < 30) {
                    insights.add("Only " + age1Pct + "% die at age 1 — objects surviving multiple GCs");
                }
            }
        }

        if (prematurePromotion) {
            insights.add("Premature promotion detected: threshold dropped to 1 (survivor space pressure)");
            recommendations.add("Increase survivor space: -XX:SurvivorRatio=<lower value> or -XX:NewSize");
        }

        if (survivorOverflow) {
            insights.add("Survivor overflow detected: objects promoted early due to full survivor space");
            recommendations.add("Increase survivor space with -XX:SurvivorRatio or -Xmn");
        }

        // Suggest MaxTenuringThreshold based on where most data accumulates
        int suggested = suggestMaxTenuringThreshold(snapshots);
        if (suggested > 0 && suggested < maxThresholdSeen) {
            recommendations.add("Consider -XX:MaxTenuringThreshold=" + suggested
                    + " (most objects promoted by age " + suggested + ")");
        }

        if (minThreshold == Integer.MAX_VALUE) minThreshold = 0;

        return new TenuringAnalysis(snapshots, insights, recommendations,
                prematurePromotion, survivorOverflow, minThreshold, maxThresholdSeen, suggested);
    }

    /**
     * Suggests MaxTenuringThreshold: the age at which 80%+ of objects have been promoted
     * (cumulative bytes >= 80% of total), averaged across recent snapshots.
     */
    static int suggestMaxTenuringThreshold(List<GcAgeSnapshot> snapshots) {
        if (snapshots.isEmpty()) return -1;

        // Use last few snapshots for stability
        int start = Math.max(0, snapshots.size() - 5);
        int total80PctAge = 0;
        int counted = 0;

        for (int i = start; i < snapshots.size(); i++) {
            AgeDistribution dist = snapshots.get(i).distribution();
            if (dist.entries().isEmpty() || dist.survivorCapacity() == 0) continue;

            long threshold80 = (long) (dist.survivorCapacity() * 0.80);
            for (AgeDistribution.AgeEntry e : dist.entries()) {
                if (e.cumulativeBytes() >= threshold80) {
                    total80PctAge += e.age();
                    counted++;
                    break;
                }
            }
        }

        if (counted == 0) return -1;
        return Math.max(1, total80PctAge / counted);
    }

    private static double extractTimestamp(String line) {
        Matcher m = TIMESTAMP.matcher(line);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }
}
