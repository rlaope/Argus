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
public record GCEvent(
        EventType eventType,
        Instant timestamp,
        long duration,
        String gcName,
        String gcCause,
        long heapUsedBefore,
        long heapUsedAfter,
        long heapCommitted
) {
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
