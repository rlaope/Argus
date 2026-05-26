package io.argus.aggregator.model;

/**
 * Summary metrics used to render one tile in the fleet grid.
 *
 * <p>Each percent field uses {@link Double} so {@code null} can represent
 * "unavailable" (the wire format requires {@code null} for missing data).
 *
 * @param heapPercent       JVM heap used % (0–100), null if unavailable
 * @param gcOverheadPercent GC overhead % (0–100), null if unavailable
 * @param cpuPercent        process CPU % (0–100), null if unavailable
 * @param activeVThreads    active virtual threads count (0 if not applicable)
 * @param leakSuspected     true if memory leak signal detected
 */
public record TileMetrics(
        Double heapPercent,
        Double gcOverheadPercent,
        Double cpuPercent,
        long activeVThreads,
        boolean leakSuspected
) {
    public static TileMetrics empty() {
        return new TileMetrics(null, null, null, 0L, false);
    }
}
