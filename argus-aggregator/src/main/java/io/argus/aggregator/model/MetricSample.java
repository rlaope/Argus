package io.argus.aggregator.model;

import java.time.Instant;

/**
 * One ring buffer sample for a target. Append-only, timestamped.
 *
 * @param ts                sample wall-clock timestamp
 * @param heapPercent       JVM heap used % (null if unavailable at sample time)
 * @param gcOverheadPercent GC overhead % (null if unavailable)
 * @param cpuPercent        process CPU % (null if unavailable)
 * @param activeVThreads    active virtual threads count
 */
public record MetricSample(
        Instant ts,
        Double heapPercent,
        Double gcOverheadPercent,
        Double cpuPercent,
        long activeVThreads
) {}
