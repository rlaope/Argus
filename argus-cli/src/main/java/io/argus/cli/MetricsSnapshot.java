package io.argus.cli;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all metrics from one poll cycle.
 */
public record MetricsSnapshot(
        // Connection
        boolean connected,
        int clientCount,

        // Basic metrics
        long totalEvents,
        long startEvents,
        long endEvents,
        long pinnedEvents,
        int activeThreads,

        // CPU
        double cpuJvmPercent,
        double cpuMachinePercent,
        double cpuPeakJvm,

        // GC
        long gcTotalEvents,
        double gcTotalPauseMs,
        double gcOverheadPercent,
        long heapUsedBytes,
        long heapCommittedBytes,

        // Metaspace
        double metaspaceUsedMB,
        long classCount,

        // Carrier threads
        int carrierCount,
        double avgVtPerCarrier,

        // Pinning
        long totalPinnedEvents,
        int uniquePinningStacks,

        // Profiling
        long profilingSamples,
        List<HotMethodInfo> hotMethods,

        // Contention
        long contentionEvents,
        double contentionTimeMs,
        List<ContentionHotspot> contentionHotspots
) {
    public static MetricsSnapshot disconnected() {
        return new MetricsSnapshot(false, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, List.of(), 0, 0, List.of());
    }

    public record HotMethodInfo(String className, String methodName, double percentage) {}
    public record ContentionHotspot(String monitorClass, long eventCount) {}
}
