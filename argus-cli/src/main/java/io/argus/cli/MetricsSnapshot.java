package io.argus.cli;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all metrics from one poll cycle.
 */
public final class MetricsSnapshot {
    // Connection
    private final boolean connected;
    private final int clientCount;

    // Basic metrics
    private final long totalEvents;
    private final long startEvents;
    private final long endEvents;
    private final long pinnedEvents;
    private final int activeThreads;

    // CPU
    private final double cpuJvmPercent;
    private final double cpuMachinePercent;
    private final double cpuPeakJvm;

    // GC
    private final long gcTotalEvents;
    private final double gcTotalPauseMs;
    private final double gcOverheadPercent;
    private final long heapUsedBytes;
    private final long heapCommittedBytes;

    // Metaspace
    private final double metaspaceUsedMB;
    private final long classCount;

    // Carrier threads
    private final int carrierCount;
    private final double avgVtPerCarrier;

    // Pinning
    private final long totalPinnedEvents;
    private final int uniquePinningStacks;

    // Profiling
    private final long profilingSamples;
    private final List<HotMethodInfo> hotMethods;

    // Contention
    private final long contentionEvents;
    private final double contentionTimeMs;
    private final List<ContentionHotspot> contentionHotspots;

    public MetricsSnapshot(boolean connected, int clientCount,
                           long totalEvents, long startEvents, long endEvents,
                           long pinnedEvents, int activeThreads,
                           double cpuJvmPercent, double cpuMachinePercent, double cpuPeakJvm,
                           long gcTotalEvents, double gcTotalPauseMs, double gcOverheadPercent,
                           long heapUsedBytes, long heapCommittedBytes,
                           double metaspaceUsedMB, long classCount,
                           int carrierCount, double avgVtPerCarrier,
                           long totalPinnedEvents, int uniquePinningStacks,
                           long profilingSamples, List<HotMethodInfo> hotMethods,
                           long contentionEvents, double contentionTimeMs,
                           List<ContentionHotspot> contentionHotspots) {
        this.connected = connected;
        this.clientCount = clientCount;
        this.totalEvents = totalEvents;
        this.startEvents = startEvents;
        this.endEvents = endEvents;
        this.pinnedEvents = pinnedEvents;
        this.activeThreads = activeThreads;
        this.cpuJvmPercent = cpuJvmPercent;
        this.cpuMachinePercent = cpuMachinePercent;
        this.cpuPeakJvm = cpuPeakJvm;
        this.gcTotalEvents = gcTotalEvents;
        this.gcTotalPauseMs = gcTotalPauseMs;
        this.gcOverheadPercent = gcOverheadPercent;
        this.heapUsedBytes = heapUsedBytes;
        this.heapCommittedBytes = heapCommittedBytes;
        this.metaspaceUsedMB = metaspaceUsedMB;
        this.classCount = classCount;
        this.carrierCount = carrierCount;
        this.avgVtPerCarrier = avgVtPerCarrier;
        this.totalPinnedEvents = totalPinnedEvents;
        this.uniquePinningStacks = uniquePinningStacks;
        this.profilingSamples = profilingSamples;
        this.hotMethods = hotMethods;
        this.contentionEvents = contentionEvents;
        this.contentionTimeMs = contentionTimeMs;
        this.contentionHotspots = contentionHotspots;
    }

    public static MetricsSnapshot disconnected() {
        return new MetricsSnapshot(false, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, List.of(), 0, 0, List.of());
    }

    public boolean connected() { return connected; }
    public int clientCount() { return clientCount; }
    public long totalEvents() { return totalEvents; }
    public long startEvents() { return startEvents; }
    public long endEvents() { return endEvents; }
    public long pinnedEvents() { return pinnedEvents; }
    public int activeThreads() { return activeThreads; }
    public double cpuJvmPercent() { return cpuJvmPercent; }
    public double cpuMachinePercent() { return cpuMachinePercent; }
    public double cpuPeakJvm() { return cpuPeakJvm; }
    public long gcTotalEvents() { return gcTotalEvents; }
    public double gcTotalPauseMs() { return gcTotalPauseMs; }
    public double gcOverheadPercent() { return gcOverheadPercent; }
    public long heapUsedBytes() { return heapUsedBytes; }
    public long heapCommittedBytes() { return heapCommittedBytes; }
    public double metaspaceUsedMB() { return metaspaceUsedMB; }
    public long classCount() { return classCount; }
    public int carrierCount() { return carrierCount; }
    public double avgVtPerCarrier() { return avgVtPerCarrier; }
    public long totalPinnedEvents() { return totalPinnedEvents; }
    public int uniquePinningStacks() { return uniquePinningStacks; }
    public long profilingSamples() { return profilingSamples; }
    public List<HotMethodInfo> hotMethods() { return hotMethods; }
    public long contentionEvents() { return contentionEvents; }
    public double contentionTimeMs() { return contentionTimeMs; }
    public List<ContentionHotspot> contentionHotspots() { return contentionHotspots; }

    public static final class HotMethodInfo {
        private final String className;
        private final String methodName;
        private final double percentage;

        public HotMethodInfo(String className, String methodName, double percentage) {
            this.className = className;
            this.methodName = methodName;
            this.percentage = percentage;
        }

        public String className() { return className; }
        public String methodName() { return methodName; }
        public double percentage() { return percentage; }
    }

    public static final class ContentionHotspot {
        private final String monitorClass;
        private final long eventCount;

        public ContentionHotspot(String monitorClass, long eventCount) {
            this.monitorClass = monitorClass;
            this.eventCount = eventCount;
        }

        public String monitorClass() { return monitorClass; }
        public long eventCount() { return eventCount; }
    }
}
