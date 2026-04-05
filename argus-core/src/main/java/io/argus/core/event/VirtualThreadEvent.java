package io.argus.core.event;

import java.time.Instant;

/**
 * Represents a virtual thread event captured by the Argus agent.
 *
 * @param eventType     the type of event
 * @param threadId      the virtual thread ID
 * @param threadName    the virtual thread name (may be null)
 * @param carrierThread the carrier thread ID (for pinned events)
 * @param timestamp     the event timestamp
 * @param duration      the duration in nanoseconds (for applicable events)
 * @param stackTrace    the stack trace (for pinned events)
 */
public final class VirtualThreadEvent {
    private final EventType eventType;
    private final long threadId;
    private final String threadName;
    private final long carrierThread;
    private final Instant timestamp;
    private final long duration;
    private final String stackTrace;

    public VirtualThreadEvent(EventType eventType, long threadId, String threadName,
                              long carrierThread, Instant timestamp, long duration,
                              String stackTrace) {
        this.eventType = eventType;
        this.threadId = threadId;
        this.threadName = threadName;
        this.carrierThread = carrierThread;
        this.timestamp = timestamp;
        this.duration = duration;
        this.stackTrace = stackTrace;
    }

    public EventType eventType() { return eventType; }
    public long threadId() { return threadId; }
    public String threadName() { return threadName; }
    public long carrierThread() { return carrierThread; }
    public Instant timestamp() { return timestamp; }
    public long duration() { return duration; }
    public String stackTrace() { return stackTrace; }

    /**
     * Creates a thread start event.
     */
    public static VirtualThreadEvent start(long threadId, String threadName, Instant timestamp) {
        return new VirtualThreadEvent(
                EventType.VIRTUAL_THREAD_START,
                threadId,
                threadName,
                -1,
                timestamp,
                0,
                null
        );
    }

    /**
     * Creates a thread end event without duration.
     */
    public static VirtualThreadEvent end(long threadId, String threadName, Instant timestamp) {
        return new VirtualThreadEvent(
                EventType.VIRTUAL_THREAD_END,
                threadId,
                threadName,
                -1,
                timestamp,
                0,
                null
        );
    }

    /**
     * Creates a thread end event with duration.
     */
    public static VirtualThreadEvent end(long threadId, String threadName, Instant timestamp, long durationNanos) {
        return new VirtualThreadEvent(
                EventType.VIRTUAL_THREAD_END,
                threadId,
                threadName,
                -1,
                timestamp,
                durationNanos,
                null
        );
    }

    /**
     * Creates a thread pinned event.
     */
    public static VirtualThreadEvent pinned(long threadId, String threadName, long carrierThread,
                                            Instant timestamp, long duration, String stackTrace) {
        return new VirtualThreadEvent(
                EventType.VIRTUAL_THREAD_PINNED,
                threadId,
                threadName,
                carrierThread,
                timestamp,
                duration,
                stackTrace
        );
    }

    /**
     * Creates a submit failed event.
     */
    public static VirtualThreadEvent submitFailed(long threadId, String threadName, Instant timestamp) {
        return new VirtualThreadEvent(
                EventType.VIRTUAL_THREAD_SUBMIT_FAILED,
                threadId,
                threadName,
                -1,
                timestamp,
                0,
                null
        );
    }
}
