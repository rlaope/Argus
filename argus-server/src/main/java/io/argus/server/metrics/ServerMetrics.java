package io.argus.server.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregates and manages server-side metrics for virtual thread events.
 *
 * <p>This class provides thread-safe counters for tracking various event types
 * and exposes methods for incrementing and querying these metrics.
 */
public final class ServerMetrics {

    private final AtomicLong totalEvents = new AtomicLong(0);
    private final AtomicLong startEvents = new AtomicLong(0);
    private final AtomicLong endEvents = new AtomicLong(0);
    private final AtomicLong pinnedEvents = new AtomicLong(0);
    private final AtomicLong submitFailedEvents = new AtomicLong(0);

    /**
     * Increments the total events counter.
     */
    public void incrementTotal() {
        totalEvents.incrementAndGet();
    }

    /**
     * Increments the start events counter.
     */
    public void incrementStart() {
        startEvents.incrementAndGet();
    }

    /**
     * Increments the end events counter.
     */
    public void incrementEnd() {
        endEvents.incrementAndGet();
    }

    /**
     * Increments the pinned events counter.
     */
    public void incrementPinned() {
        pinnedEvents.incrementAndGet();
    }

    /**
     * Increments the submit failed events counter.
     */
    public void incrementSubmitFailed() {
        submitFailedEvents.incrementAndGet();
    }

    public long getTotalEvents() {
        return totalEvents.get();
    }

    public long getStartEvents() {
        return startEvents.get();
    }

    public long getEndEvents() {
        return endEvents.get();
    }

    public long getPinnedEvents() {
        return pinnedEvents.get();
    }

    public long getSubmitFailedEvents() {
        return submitFailedEvents.get();
    }

    /**
     * Returns a JSON representation of all metrics.
     *
     * @param activeThreadCount current number of active threads
     * @param connectedClients  number of connected WebSocket clients
     * @return JSON string with all metrics
     */
    public String toJson(int activeThreadCount, int connectedClients) {
        return String.format(
                "{\"totalEvents\":%d,\"startEvents\":%d,\"endEvents\":%d,\"activeThreads\":%d,\"pinnedEvents\":%d,\"submitFailedEvents\":%d,\"connectedClients\":%d}",
                totalEvents.get(),
                startEvents.get(),
                endEvents.get(),
                activeThreadCount,
                pinnedEvents.get(),
                submitFailedEvents.get(),
                connectedClients
        );
    }
}
