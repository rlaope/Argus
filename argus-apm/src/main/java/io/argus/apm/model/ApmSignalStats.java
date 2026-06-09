package io.argus.apm.model;

public record ApmSignalStats(
        double requestRatePerSecond,
        double errorRate,
        double latencyP50Millis,
        double latencyP95Millis,
        double latencyP99Millis,
        double gcPauseP95Millis,
        double heapUsedRatio,
        double cpuUsageRatio
) {
    public ApmSignalStats {
        requireFiniteNonNegative(requestRatePerSecond, "requestRatePerSecond");
        requireFiniteNonNegative(errorRate, "errorRate");
        requireFiniteNonNegative(latencyP50Millis, "latencyP50Millis");
        requireFiniteNonNegative(latencyP95Millis, "latencyP95Millis");
        requireFiniteNonNegative(latencyP99Millis, "latencyP99Millis");
        requireFiniteNonNegative(gcPauseP95Millis, "gcPauseP95Millis");
        requireFiniteNonNegative(heapUsedRatio, "heapUsedRatio");
        requireFiniteNonNegative(cpuUsageRatio, "cpuUsageRatio");
    }

    public static ApmSignalStats empty() {
        return new ApmSignalStats(0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
