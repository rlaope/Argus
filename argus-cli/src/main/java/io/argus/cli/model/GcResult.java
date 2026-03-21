package io.argus.cli.model;

import java.util.List;

/**
 * GC statistics result.
 */
public record GcResult(
        long totalEvents,
        double totalPauseMs,
        double overheadPercent,
        String lastCause,
        long heapUsed,
        long heapCommitted,
        List<CollectorInfo> collectors
) {
    public record CollectorInfo(String name, long count, double totalMs) {}
}
