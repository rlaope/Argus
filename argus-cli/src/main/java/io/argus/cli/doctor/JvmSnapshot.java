package io.argus.cli.doctor;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all JVM metrics collected for health analysis.
 *
 * <p>Gathered once at the start of a doctor evaluation, then passed to
 * all {@link HealthRule}s. This ensures all rules see consistent data.
 */
public final class JvmSnapshot {

    // Heap
    private final long heapUsed;
    private final long heapMax;
    private final long heapCommitted;
    private final long nonHeapUsed;

    // Memory pools
    private final Map<String, PoolInfo> memoryPools;

    // GC
    private final List<GcInfo> collectors;
    private final long totalGcCount;
    private final long totalGcTimeMs;
    private final long uptimeMs;

    // CPU
    private final double processCpuLoad;
    private final double systemCpuLoad;
    private final int availableProcessors;

    // Threads
    private final int threadCount;
    private final int daemonThreadCount;
    private final int peakThreadCount;
    private final Map<String, Integer> threadStates;
    private final int deadlockedThreads;

    // Buffers
    private final List<BufferInfo> bufferPools;

    // Classes
    private final int loadedClassCount;
    private final long totalLoadedClassCount;
    private final long unloadedClassCount;

    // Finalizer
    private final int pendingFinalization;

    // VM
    private final String vmName;
    private final String vmVersion;
    private final String gcAlgorithm;
    private final List<String> vmFlags;

    public JvmSnapshot(long heapUsed, long heapMax, long heapCommitted, long nonHeapUsed,
                       Map<String, PoolInfo> memoryPools,
                       List<GcInfo> collectors, long totalGcCount, long totalGcTimeMs, long uptimeMs,
                       double processCpuLoad, double systemCpuLoad, int availableProcessors,
                       int threadCount, int daemonThreadCount, int peakThreadCount,
                       Map<String, Integer> threadStates, int deadlockedThreads,
                       List<BufferInfo> bufferPools,
                       int loadedClassCount, long totalLoadedClassCount, long unloadedClassCount,
                       int pendingFinalization,
                       String vmName, String vmVersion, String gcAlgorithm, List<String> vmFlags) {
        this.heapUsed = heapUsed;
        this.heapMax = heapMax;
        this.heapCommitted = heapCommitted;
        this.nonHeapUsed = nonHeapUsed;
        this.memoryPools = memoryPools;
        this.collectors = collectors;
        this.totalGcCount = totalGcCount;
        this.totalGcTimeMs = totalGcTimeMs;
        this.uptimeMs = uptimeMs;
        this.processCpuLoad = processCpuLoad;
        this.systemCpuLoad = systemCpuLoad;
        this.availableProcessors = availableProcessors;
        this.threadCount = threadCount;
        this.daemonThreadCount = daemonThreadCount;
        this.peakThreadCount = peakThreadCount;
        this.threadStates = threadStates;
        this.deadlockedThreads = deadlockedThreads;
        this.bufferPools = bufferPools;
        this.loadedClassCount = loadedClassCount;
        this.totalLoadedClassCount = totalLoadedClassCount;
        this.unloadedClassCount = unloadedClassCount;
        this.pendingFinalization = pendingFinalization;
        this.vmName = vmName;
        this.vmVersion = vmVersion;
        this.gcAlgorithm = gcAlgorithm;
        this.vmFlags = vmFlags;
    }

    // Accessors
    public long heapUsed() { return heapUsed; }
    public long heapMax() { return heapMax; }
    public long heapCommitted() { return heapCommitted; }
    public long nonHeapUsed() { return nonHeapUsed; }
    public double heapUsagePercent() { return heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0; }
    public Map<String, PoolInfo> memoryPools() { return memoryPools; }
    public List<GcInfo> collectors() { return collectors; }
    public long totalGcCount() { return totalGcCount; }
    public long totalGcTimeMs() { return totalGcTimeMs; }
    public long uptimeMs() { return uptimeMs; }
    public double gcOverheadPercent() {
        return uptimeMs > 0 ? (double) totalGcTimeMs / uptimeMs * 100 : 0;
    }
    public double processCpuLoad() { return processCpuLoad; }
    public double systemCpuLoad() { return systemCpuLoad; }
    public int availableProcessors() { return availableProcessors; }
    public int threadCount() { return threadCount; }
    public int daemonThreadCount() { return daemonThreadCount; }
    public int peakThreadCount() { return peakThreadCount; }
    public Map<String, Integer> threadStates() { return threadStates; }
    public int deadlockedThreads() { return deadlockedThreads; }
    public int blockedThreads() { return threadStates.getOrDefault("BLOCKED", 0); }
    public List<BufferInfo> bufferPools() { return bufferPools; }
    public int loadedClassCount() { return loadedClassCount; }
    public long totalLoadedClassCount() { return totalLoadedClassCount; }
    public long unloadedClassCount() { return unloadedClassCount; }
    public int pendingFinalization() { return pendingFinalization; }
    public String vmName() { return vmName; }
    public String vmVersion() { return vmVersion; }
    public String gcAlgorithm() { return gcAlgorithm; }
    public List<String> vmFlags() { return vmFlags; }

    public static final class PoolInfo {
        private final String name;
        private final long used;
        private final long max;
        private final String type;

        public PoolInfo(String name, long used, long max, String type) {
            this.name = name; this.used = used; this.max = max; this.type = type;
        }

        public String name() { return name; }
        public long used() { return used; }
        public long max() { return max; }
        public String type() { return type; }
        public double usagePercent() { return max > 0 ? (double) used / max * 100 : 0; }
    }

    public static final class GcInfo {
        private final String name;
        private final long count;
        private final long timeMs;

        public GcInfo(String name, long count, long timeMs) {
            this.name = name; this.count = count; this.timeMs = timeMs;
        }

        public String name() { return name; }
        public long count() { return count; }
        public long timeMs() { return timeMs; }
    }

    public static final class BufferInfo {
        private final String name;
        private final long count;
        private final long capacity;
        private final long used;

        public BufferInfo(String name, long count, long capacity, long used) {
            this.name = name; this.count = count; this.capacity = capacity; this.used = used;
        }

        public String name() { return name; }
        public long count() { return count; }
        public long capacity() { return capacity; }
        public long used() { return used; }
        public double usagePercent() { return capacity > 0 ? (double) used / capacity * 100 : 0; }
    }
}
