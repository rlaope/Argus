package io.argus.core.event;

import java.time.Instant;

/**
 * Represents an object allocation event captured by the Argus agent.
 *
 * <p>This event is generated from {@code jdk.ObjectAllocationInNewTLAB} JFR events
 * which track object allocations in new Thread Local Allocation Buffers.
 *
 * @param timestamp      the event timestamp
 * @param className      the class of the allocated object
 * @param allocationSize the size of the allocation in bytes
 * @param tlabSize       the size of the TLAB in bytes
 */
public record AllocationEvent(
        Instant timestamp,
        String className,
        long allocationSize,
        long tlabSize
) {
    /**
     * Creates an allocation event.
     *
     * @param timestamp      the event timestamp
     * @param className      the class of the allocated object
     * @param allocationSize the size of the allocation in bytes
     * @param tlabSize       the size of the TLAB in bytes
     * @return the allocation event
     */
    public static AllocationEvent of(Instant timestamp, String className,
                                     long allocationSize, long tlabSize) {
        return new AllocationEvent(timestamp, className, allocationSize, tlabSize);
    }

    /**
     * Returns the allocation size in KB.
     *
     * @return allocation size in KB
     */
    public double allocationSizeKB() {
        return allocationSize / 1024.0;
    }

    /**
     * Returns the TLAB size in KB.
     *
     * @return TLAB size in KB
     */
    public double tlabSizeKB() {
        return tlabSize / 1024.0;
    }
}
