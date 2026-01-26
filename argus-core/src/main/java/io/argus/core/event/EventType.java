package io.argus.core.event;

/**
 * Types of virtual thread events captured by Argus.
 */
public enum EventType {
    /**
     * Virtual thread has been started.
     */
    VIRTUAL_THREAD_START(1),

    /**
     * Virtual thread has ended.
     */
    VIRTUAL_THREAD_END(2),

    /**
     * Virtual thread was pinned to carrier thread.
     * This is critical for Loom performance monitoring.
     */
    VIRTUAL_THREAD_PINNED(3),

    /**
     * Virtual thread submit failed.
     */
    VIRTUAL_THREAD_SUBMIT_FAILED(4),

    // GC Events (10-19)
    /**
     * GC pause event with duration and cause.
     */
    GC_PAUSE(10),

    /**
     * GC heap summary with memory usage.
     */
    GC_HEAP_SUMMARY(11),

    // CPU Events (20-29)
    /**
     * CPU load metrics.
     */
    CPU_LOAD(20);

    private final int code;

    EventType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static EventType fromCode(int code) {
        return switch (code) {
            case 1 -> VIRTUAL_THREAD_START;
            case 2 -> VIRTUAL_THREAD_END;
            case 3 -> VIRTUAL_THREAD_PINNED;
            case 4 -> VIRTUAL_THREAD_SUBMIT_FAILED;
            case 10 -> GC_PAUSE;
            case 11 -> GC_HEAP_SUMMARY;
            case 20 -> CPU_LOAD;
            default -> throw new IllegalArgumentException("Unknown event type code: " + code);
        };
    }
}
