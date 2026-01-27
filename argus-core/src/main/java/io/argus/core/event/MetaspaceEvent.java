package io.argus.core.event;

import java.time.Instant;

/**
 * Represents a metaspace summary event captured by the Argus agent.
 *
 * <p>This event is generated from {@code jdk.MetaspaceSummary} JFR events
 * which track metaspace memory usage.
 *
 * @param timestamp          the event timestamp
 * @param metaspaceUsed      metaspace used memory in bytes
 * @param metaspaceCommitted metaspace committed memory in bytes
 * @param metaspaceReserved  metaspace reserved memory in bytes
 * @param classCount         number of loaded classes
 */
public record MetaspaceEvent(
        Instant timestamp,
        long metaspaceUsed,
        long metaspaceCommitted,
        long metaspaceReserved,
        long classCount
) {
    /**
     * Creates a metaspace event.
     *
     * @param timestamp          the event timestamp
     * @param metaspaceUsed      metaspace used memory in bytes
     * @param metaspaceCommitted metaspace committed memory in bytes
     * @param metaspaceReserved  metaspace reserved memory in bytes
     * @param classCount         number of loaded classes
     * @return the metaspace event
     */
    public static MetaspaceEvent of(Instant timestamp, long metaspaceUsed,
                                    long metaspaceCommitted, long metaspaceReserved,
                                    long classCount) {
        return new MetaspaceEvent(timestamp, metaspaceUsed, metaspaceCommitted,
                metaspaceReserved, classCount);
    }

    /**
     * Returns the metaspace used in MB.
     *
     * @return metaspace used in MB
     */
    public double usedMB() {
        return metaspaceUsed / (1024.0 * 1024.0);
    }

    /**
     * Returns the metaspace committed in MB.
     *
     * @return metaspace committed in MB
     */
    public double committedMB() {
        return metaspaceCommitted / (1024.0 * 1024.0);
    }

    /**
     * Returns the metaspace utilization ratio (used/committed).
     *
     * @return utilization ratio (0.0-1.0)
     */
    public double utilizationRatio() {
        return metaspaceCommitted > 0 ? (double) metaspaceUsed / metaspaceCommitted : 0.0;
    }
}
