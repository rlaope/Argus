package io.argus.server.analysis;

/**
 * Represents a pinning hotspot - a unique stack trace location where
 * virtual thread pinning frequently occurs.
 *
 * @param rank           the rank by frequency (1 = most frequent)
 * @param count          the number of pinning events with this stack trace
 * @param percentage     the percentage of total pinned events
 * @param stackTraceHash the hash of the stack trace for grouping
 * @param topFrame       the top frame of the stack trace (most relevant location)
 * @param fullStackTrace the complete stack trace
 */
public record PinningHotspot(
        int rank,
        long count,
        double percentage,
        String stackTraceHash,
        String topFrame,
        String fullStackTrace
) {
}
