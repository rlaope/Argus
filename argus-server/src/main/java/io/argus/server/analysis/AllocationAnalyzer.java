package io.argus.server.analysis;

import io.argus.core.event.AllocationEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes object allocation events and provides statistics.
 *
 * <p>Tracks allocation rates, top allocating classes, and allocation history.
 */
public final class AllocationAnalyzer {

    private static final int MAX_HISTORY_SIZE = 60;
    private static final int TOP_CLASSES_LIMIT = 10;

    private final AtomicLong totalAllocations = new AtomicLong(0);
    private final AtomicLong totalBytesAllocated = new AtomicLong(0);
    private final Map<String, AtomicLong> classAllocationCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> classAllocationBytes = new ConcurrentHashMap<>();
    private final List<AllocationSnapshot> history = new CopyOnWriteArrayList<>();

    // Rolling window for rate calculation
    private volatile long windowStartTime = System.currentTimeMillis();
    private volatile long windowBytes = 0;
    private volatile double peakAllocationRate = 0;

    /**
     * Records an allocation event for analysis.
     *
     * @param event the allocation event to record
     */
    public void recordAllocationEvent(AllocationEvent event) {
        totalAllocations.incrementAndGet();
        totalBytesAllocated.addAndGet(event.allocationSize());

        // Track by class
        String className = event.className() != null ? event.className() : "Unknown";
        classAllocationCounts.computeIfAbsent(className, k -> new AtomicLong()).incrementAndGet();
        classAllocationBytes.computeIfAbsent(className, k -> new AtomicLong())
                .addAndGet(event.allocationSize());

        // Update window for rate calculation
        updateRateWindow(event.allocationSize());
    }

    private synchronized void updateRateWindow(long bytes) {
        long currentTime = System.currentTimeMillis();
        windowBytes += bytes;

        // Calculate rate every second
        if (currentTime - windowStartTime >= 1000) {
            double rate = windowBytes / ((currentTime - windowStartTime) / 1000.0);

            // Track peak
            if (rate > peakAllocationRate) {
                peakAllocationRate = rate;
            }

            // Add to history
            AllocationSnapshot snapshot = new AllocationSnapshot(
                    Instant.now(),
                    totalAllocations.get(),
                    totalBytesAllocated.get(),
                    rate
            );
            history.add(snapshot);
            while (history.size() > MAX_HISTORY_SIZE) {
                history.removeFirst();
            }

            // Reset window
            windowStartTime = currentTime;
            windowBytes = 0;
        }
    }

    /**
     * Returns the allocation analysis results.
     *
     * @return the allocation analysis result
     */
    public AllocationAnalysisResult getAnalysis() {
        // Calculate current rate
        long currentTime = System.currentTimeMillis();
        double currentRate = 0;
        if (currentTime > windowStartTime) {
            currentRate = windowBytes / ((currentTime - windowStartTime) / 1000.0);
        }

        // Get top allocating classes
        List<ClassAllocation> topClasses = classAllocationBytes.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(TOP_CLASSES_LIMIT)
                .map(e -> new ClassAllocation(
                        e.getKey(),
                        classAllocationCounts.getOrDefault(e.getKey(), new AtomicLong()).get(),
                        e.getValue().get()
                ))
                .toList();

        return new AllocationAnalysisResult(
                totalAllocations.get(),
                totalBytesAllocated.get(),
                currentRate,
                peakAllocationRate,
                topClasses,
                new ArrayList<>(history)
        );
    }

    /**
     * Returns the allocation history for charting.
     *
     * @return list of allocation snapshots
     */
    public List<AllocationSnapshot> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Returns the current allocation rate in bytes per second.
     *
     * @return current allocation rate
     */
    public double getCurrentAllocationRate() {
        long currentTime = System.currentTimeMillis();
        if (currentTime > windowStartTime) {
            return windowBytes / ((currentTime - windowStartTime) / 1000.0);
        }
        return 0;
    }

    /**
     * Clears all recorded data.
     */
    public void clear() {
        totalAllocations.set(0);
        totalBytesAllocated.set(0);
        classAllocationCounts.clear();
        classAllocationBytes.clear();
        history.clear();
        windowStartTime = System.currentTimeMillis();
        windowBytes = 0;
        peakAllocationRate = 0;
    }

    /**
     * Allocation by class.
     */
    public record ClassAllocation(
            String className,
            long allocationCount,
            long totalBytes
    ) {
    }

    /**
     * Snapshot of allocation state at a point in time.
     */
    public record AllocationSnapshot(
            Instant timestamp,
            long totalAllocations,
            long totalBytes,
            double allocationRateBytesPerSec
    ) {
        /**
         * Returns the allocation rate in MB/s.
         */
        public double allocationRateMBPerSec() {
            return allocationRateBytesPerSec / (1024.0 * 1024.0);
        }
    }

    /**
     * Result of allocation analysis.
     */
    public record AllocationAnalysisResult(
            long totalAllocations,
            long totalBytesAllocated,
            double allocationRateBytesPerSec,
            double peakAllocationRate,
            List<ClassAllocation> topAllocatingClasses,
            List<AllocationSnapshot> history
    ) {
        /**
         * Returns the total allocated in MB.
         */
        public double totalAllocatedMB() {
            return totalBytesAllocated / (1024.0 * 1024.0);
        }

        /**
         * Returns the allocation rate in MB/s.
         */
        public double allocationRateMBPerSec() {
            return allocationRateBytesPerSec / (1024.0 * 1024.0);
        }

        /**
         * Returns the peak allocation rate in MB/s.
         */
        public double peakAllocationRateMBPerSec() {
            return peakAllocationRate / (1024.0 * 1024.0);
        }
    }
}
