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
    CPU_LOAD(20),

    // Allocation Events (30-39)
    /**
     * Object allocation in new TLAB.
     */
    ALLOCATION(30),

    /**
     * Metaspace summary.
     */
    METASPACE_SUMMARY(31),

    // Profiling Events (40-49)
    /**
     * Execution sample for method profiling.
     */
    EXECUTION_SAMPLE(40),

    /**
     * Thread contention event (lock wait/enter).
     */
    CONTENTION(41);

    private final int code;

    EventType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static EventType fromCode(int code) {
        switch (code) {
            case 1: return VIRTUAL_THREAD_START;
            case 2: return VIRTUAL_THREAD_END;
            case 3: return VIRTUAL_THREAD_PINNED;
            case 4: return VIRTUAL_THREAD_SUBMIT_FAILED;
            case 10: return GC_PAUSE;
            case 11: return GC_HEAP_SUMMARY;
            case 20: return CPU_LOAD;
            case 30: return ALLOCATION;
            case 31: return METASPACE_SUMMARY;
            case 40: return EXECUTION_SAMPLE;
            case 41: return CONTENTION;
            default: throw new IllegalArgumentException("Unknown event type code: " + code);
        }
    }
}
