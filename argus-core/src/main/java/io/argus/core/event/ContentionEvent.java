package io.argus.core.event;

import java.time.Instant;

/**
 * Represents a thread contention event captured by the Argus agent.
 *
 * <p>This event is generated from JFR events:
 * <ul>
 *   <li>{@code jdk.JavaMonitorEnter} - Thread attempting to enter a synchronized block</li>
 *   <li>{@code jdk.JavaMonitorWait} - Thread waiting on a monitor</li>
 * </ul>
 *
 * @param timestamp     the event timestamp
 * @param threadId      the thread ID experiencing contention
 * @param threadName    the thread name
 * @param monitorClass  the class of the monitor object
 * @param durationNanos the duration of the contention in nanoseconds
 * @param type          the type of contention (ENTER or WAIT)
 */
public record ContentionEvent(
        Instant timestamp,
        long threadId,
        String threadName,
        String monitorClass,
        long durationNanos,
        ContentionType type
) {
    /**
     * Types of contention events.
     */
    public enum ContentionType {
        /**
         * Thread attempting to enter a synchronized block.
         */
        ENTER,

        /**
         * Thread waiting on a monitor (Object.wait()).
         */
        WAIT
    }

    /**
     * Creates a monitor enter contention event.
     *
     * @param timestamp     the event timestamp
     * @param threadId      the thread ID
     * @param threadName    the thread name
     * @param monitorClass  the monitor class
     * @param durationNanos the duration in nanoseconds
     * @return the contention event
     */
    public static ContentionEvent enter(Instant timestamp, long threadId, String threadName,
                                        String monitorClass, long durationNanos) {
        return new ContentionEvent(timestamp, threadId, threadName, monitorClass,
                durationNanos, ContentionType.ENTER);
    }

    /**
     * Creates a monitor wait contention event.
     *
     * @param timestamp     the event timestamp
     * @param threadId      the thread ID
     * @param threadName    the thread name
     * @param monitorClass  the monitor class
     * @param durationNanos the duration in nanoseconds
     * @return the contention event
     */
    public static ContentionEvent wait(Instant timestamp, long threadId, String threadName,
                                       String monitorClass, long durationNanos) {
        return new ContentionEvent(timestamp, threadId, threadName, monitorClass,
                durationNanos, ContentionType.WAIT);
    }

    /**
     * Returns the duration in milliseconds.
     *
     * @return duration in milliseconds
     */
    public double durationMs() {
        return durationNanos / 1_000_000.0;
    }

    /**
     * Returns the duration in microseconds.
     *
     * @return duration in microseconds
     */
    public double durationMicros() {
        return durationNanos / 1_000.0;
    }
}
