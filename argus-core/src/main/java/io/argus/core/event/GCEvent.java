package io.argus.core.event;

import java.time.Instant;

/**
 * Represents a garbage collection event captured by the Argus agent.
 *
 * @param eventType      the type of GC event (GC_PAUSE or GC_HEAP_SUMMARY)
 * @param timestamp      the event timestamp
 * @param duration       the GC pause duration in nanoseconds
 * @param gcName         the name of the GC collector (e.g., "G1 Young Generation")
 * @param gcCause        the cause of the GC (e.g., "G1 Evacuation Pause")
 * @param heapUsedBefore heap used before GC in bytes
 * @param heapUsedAfter  heap used after GC in bytes
 * @param heapCommitted  heap committed memory in bytes
 */
public final class GCEvent {
    private final EventType eventType;
    private final Instant timestamp;
    private final long duration;
    private final String gcName;
    private final String gcCause;
    private final long heapUsedBefore;
    private final long heapUsedAfter;
    private final long heapCommitted;

    public GCEvent(EventType eventType, Instant timestamp, long duration, String gcName,
                   String gcCause, long heapUsedBefore, long heapUsedAfter, long heapCommitted) {
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.duration = duration;
        this.gcName = gcName;
        this.gcCause = gcCause;
        this.heapUsedBefore = heapUsedBefore;
        this.heapUsedAfter = heapUsedAfter;
        this.heapCommitted = heapCommitted;
    }

    public EventType eventType() { return eventType; }
    public Instant timestamp() { return timestamp; }
    public long duration() { return duration; }
    public String gcName() { return gcName; }
    public String gcCause() { return gcCause; }
    public long heapUsedBefore() { return heapUsedBefore; }
    public long heapUsedAfter() { return heapUsedAfter; }
    public long heapCommitted() { return heapCommitted; }

    /**
     * Creates a GC pause event.
     */
    public static GCEvent pause(Instant timestamp, long durationNanos,
                                String gcName, String gcCause) {
        return new GCEvent(
                EventType.GC_PAUSE,
                timestamp,
                durationNanos,
                gcName,
                gcCause,
                0,
                0,
                0
        );
    }

    /**
     * Creates a GC heap summary event.
     */
    public static GCEvent heapSummary(Instant timestamp, long heapUsedBefore,
                                      long heapUsedAfter, long heapCommitted) {
        return new GCEvent(
                EventType.GC_HEAP_SUMMARY,
                timestamp,
                0,
                null,
                null,
                heapUsedBefore,
                heapUsedAfter,
                heapCommitted
        );
    }

    /**
     * Creates a combined GC event with both pause and heap information.
     */
    public static GCEvent combined(Instant timestamp, long durationNanos,
                                   String gcName, String gcCause,
                                   long heapUsedBefore, long heapUsedAfter,
                                   long heapCommitted) {
        return new GCEvent(
                EventType.GC_PAUSE,
                timestamp,
                durationNanos,
                gcName,
                gcCause,
                heapUsedBefore,
                heapUsedAfter,
                heapCommitted
        );
    }

    /**
     * Returns the memory reclaimed by this GC in bytes.
     * Returns 0 if heap information is not available.
     */
    public long memoryReclaimed() {
        if (heapUsedBefore > 0 && heapUsedAfter > 0) {
            return heapUsedBefore - heapUsedAfter;
        }
        return 0;
    }

    /**
     * Returns the duration in milliseconds.
     */
    public double durationMs() {
        return duration / 1_000_000.0;
    }
}
