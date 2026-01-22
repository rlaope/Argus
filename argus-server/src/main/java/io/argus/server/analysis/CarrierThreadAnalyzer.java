package io.argus.server.analysis;

import io.argus.core.event.VirtualThreadEvent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes carrier thread usage patterns.
 *
 * <p>Tracks which carrier threads are being used to run virtual threads,
 * their utilization rates, and distribution of virtual threads across carriers.
 */
public final class CarrierThreadAnalyzer {

    /**
     * Statistics for a single carrier thread.
     */
    public record CarrierStats(
            long carrierId,
            long totalVirtualThreads,
            long currentVirtualThreads,
            long pinnedEvents,
            double utilizationPercent,
            Instant lastActivity
    ) {}

    /**
     * Analysis result containing all carrier thread statistics.
     */
    public record CarrierAnalysisResult(
            int totalCarriers,
            long totalVirtualThreadsHandled,
            double avgVirtualThreadsPerCarrier,
            List<CarrierStats> carriers
    ) {}

    private final Map<Long, CarrierData> carrierMap = new ConcurrentHashMap<>();
    private final AtomicLong totalVirtualThreadsHandled = new AtomicLong(0);

    // Track which carrier is handling which virtual thread
    private final Map<Long, Long> virtualThreadToCarrier = new ConcurrentHashMap<>();

    /**
     * Records a virtual thread start event.
     *
     * @param event the start event
     */
    public void onThreadStart(VirtualThreadEvent event) {
        long carrierId = event.carrierThread();
        if (carrierId <= 0) {
            return; // No carrier info
        }

        totalVirtualThreadsHandled.incrementAndGet();
        virtualThreadToCarrier.put(event.threadId(), carrierId);

        carrierMap.computeIfAbsent(carrierId, CarrierData::new)
                  .onVirtualThreadStart();
    }

    /**
     * Records a virtual thread end event.
     *
     * @param event the end event
     */
    public void onThreadEnd(VirtualThreadEvent event) {
        Long carrierId = virtualThreadToCarrier.remove(event.threadId());
        if (carrierId == null) {
            // Try from event
            carrierId = event.carrierThread();
        }
        if (carrierId == null || carrierId <= 0) {
            return;
        }

        CarrierData carrier = carrierMap.get(carrierId);
        if (carrier != null) {
            carrier.onVirtualThreadEnd();
        }
    }

    /**
     * Records a virtual thread pinned event.
     *
     * @param event the pinned event
     */
    public void onThreadPinned(VirtualThreadEvent event) {
        long carrierId = event.carrierThread();
        if (carrierId <= 0) {
            // Try to get from tracked mapping
            Long tracked = virtualThreadToCarrier.get(event.threadId());
            if (tracked != null) {
                carrierId = tracked;
            } else {
                return;
            }
        }

        CarrierData carrier = carrierMap.get(carrierId);
        if (carrier != null) {
            carrier.onPinnedEvent();
        }
    }

    /**
     * Returns the current carrier thread analysis.
     *
     * @return analysis result
     */
    public CarrierAnalysisResult getAnalysis() {
        List<CarrierStats> stats = new ArrayList<>();
        long maxVirtualThreads = 1; // Avoid division by zero

        // Find max for utilization calculation
        for (CarrierData data : carrierMap.values()) {
            if (data.totalVirtualThreads.get() > maxVirtualThreads) {
                maxVirtualThreads = data.totalVirtualThreads.get();
            }
        }

        // Build stats list
        for (Map.Entry<Long, CarrierData> entry : carrierMap.entrySet()) {
            CarrierData data = entry.getValue();
            double utilization = (data.totalVirtualThreads.get() * 100.0) / maxVirtualThreads;

            stats.add(new CarrierStats(
                    entry.getKey(),
                    data.totalVirtualThreads.get(),
                    data.currentVirtualThreads.get(),
                    data.pinnedEvents.get(),
                    Math.round(utilization * 10) / 10.0,
                    data.lastActivity
            ));
        }

        // Sort by total virtual threads (descending)
        stats.sort((a, b) -> Long.compare(b.totalVirtualThreads(), a.totalVirtualThreads()));

        int totalCarriers = stats.size();
        long totalHandled = totalVirtualThreadsHandled.get();
        double avgPerCarrier = totalCarriers > 0 ? (double) totalHandled / totalCarriers : 0;

        return new CarrierAnalysisResult(
                totalCarriers,
                totalHandled,
                Math.round(avgPerCarrier * 10) / 10.0,
                stats
        );
    }

    /**
     * Internal mutable carrier data.
     */
    private static class CarrierData {
        final long carrierId;
        final AtomicLong totalVirtualThreads = new AtomicLong(0);
        final AtomicLong currentVirtualThreads = new AtomicLong(0);
        final AtomicLong pinnedEvents = new AtomicLong(0);
        volatile Instant lastActivity = Instant.now();

        CarrierData(long carrierId) {
            this.carrierId = carrierId;
        }

        void onVirtualThreadStart() {
            totalVirtualThreads.incrementAndGet();
            currentVirtualThreads.incrementAndGet();
            lastActivity = Instant.now();
        }

        void onVirtualThreadEnd() {
            currentVirtualThreads.decrementAndGet();
            lastActivity = Instant.now();
        }

        void onPinnedEvent() {
            pinnedEvents.incrementAndGet();
            lastActivity = Instant.now();
        }
    }
}
