package io.argus.cli.model;

import java.util.List;
import java.util.Map;

/**
 * Result of JFR recording file analysis.
 */
public final class JfrAnalyzeResult {

    private final String filePath;
    private final long durationMs;
    private final long totalEvents;

    // GC
    private final int gcEventCount;
    private final long totalGcPauseMs;
    private final long maxGcPauseMs;
    private final Map<String, Integer> gcCauseDistribution;

    // CPU
    private final double avgCpuLoad;
    private final double maxCpuLoad;
    private final double avgSystemCpuLoad;

    // Hot methods
    private final List<HotMethod> hotMethods;

    // Allocations
    private final List<AllocationSite> topAllocations;

    // Contention
    private final List<ContentionSite> topContention;

    // Exceptions
    private final Map<String, Integer> exceptionCounts;

    // I/O
    private final long fileReadCount;
    private final long fileWriteCount;
    private final long socketReadCount;
    private final long socketWriteCount;
    private final long totalFileReadBytes;
    private final long totalSocketReadBytes;

    public JfrAnalyzeResult(String filePath, long durationMs, long totalEvents,
                            int gcEventCount, long totalGcPauseMs, long maxGcPauseMs,
                            Map<String, Integer> gcCauseDistribution,
                            double avgCpuLoad, double maxCpuLoad, double avgSystemCpuLoad,
                            List<HotMethod> hotMethods, List<AllocationSite> topAllocations,
                            List<ContentionSite> topContention, Map<String, Integer> exceptionCounts,
                            long fileReadCount, long fileWriteCount,
                            long socketReadCount, long socketWriteCount,
                            long totalFileReadBytes, long totalSocketReadBytes) {
        this.filePath = filePath;
        this.durationMs = durationMs;
        this.totalEvents = totalEvents;
        this.gcEventCount = gcEventCount;
        this.totalGcPauseMs = totalGcPauseMs;
        this.maxGcPauseMs = maxGcPauseMs;
        this.gcCauseDistribution = gcCauseDistribution;
        this.avgCpuLoad = avgCpuLoad;
        this.maxCpuLoad = maxCpuLoad;
        this.avgSystemCpuLoad = avgSystemCpuLoad;
        this.hotMethods = hotMethods;
        this.topAllocations = topAllocations;
        this.topContention = topContention;
        this.exceptionCounts = exceptionCounts;
        this.fileReadCount = fileReadCount;
        this.fileWriteCount = fileWriteCount;
        this.socketReadCount = socketReadCount;
        this.socketWriteCount = socketWriteCount;
        this.totalFileReadBytes = totalFileReadBytes;
        this.totalSocketReadBytes = totalSocketReadBytes;
    }

    public String filePath() { return filePath; }
    public long durationMs() { return durationMs; }
    public long totalEvents() { return totalEvents; }
    public int gcEventCount() { return gcEventCount; }
    public long totalGcPauseMs() { return totalGcPauseMs; }
    public long maxGcPauseMs() { return maxGcPauseMs; }
    public Map<String, Integer> gcCauseDistribution() { return gcCauseDistribution; }
    public double avgCpuLoad() { return avgCpuLoad; }
    public double maxCpuLoad() { return maxCpuLoad; }
    public double avgSystemCpuLoad() { return avgSystemCpuLoad; }
    public List<HotMethod> hotMethods() { return hotMethods; }
    public List<AllocationSite> topAllocations() { return topAllocations; }
    public List<ContentionSite> topContention() { return topContention; }
    public Map<String, Integer> exceptionCounts() { return exceptionCounts; }
    public long fileReadCount() { return fileReadCount; }
    public long fileWriteCount() { return fileWriteCount; }
    public long socketReadCount() { return socketReadCount; }
    public long socketWriteCount() { return socketWriteCount; }
    public long totalFileReadBytes() { return totalFileReadBytes; }
    public long totalSocketReadBytes() { return totalSocketReadBytes; }

    public static final class HotMethod {
        private final String method;
        private final int sampleCount;
        private final double percentage;

        public HotMethod(String method, int sampleCount, double percentage) {
            this.method = method;
            this.sampleCount = sampleCount;
            this.percentage = percentage;
        }

        public String method() { return method; }
        public int sampleCount() { return sampleCount; }
        public double percentage() { return percentage; }
    }

    public static final class AllocationSite {
        private final String className;
        private final long totalBytes;
        private final int count;

        public AllocationSite(String className, long totalBytes, int count) {
            this.className = className;
            this.totalBytes = totalBytes;
            this.count = count;
        }

        public String className() { return className; }
        public long totalBytes() { return totalBytes; }
        public int count() { return count; }
    }

    public static final class ContentionSite {
        private final String monitorClass;
        private final long totalDurationMs;
        private final int count;

        public ContentionSite(String monitorClass, long totalDurationMs, int count) {
            this.monitorClass = monitorClass;
            this.totalDurationMs = totalDurationMs;
            this.count = count;
        }

        public String monitorClass() { return monitorClass; }
        public long totalDurationMs() { return totalDurationMs; }
        public int count() { return count; }
    }
}
