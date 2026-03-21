package io.argus.cli.model;

import java.util.Map;

/**
 * Heap memory usage result.
 */
public record HeapResult(
        long used,
        long committed,
        long max,
        Map<String, SpaceInfo> spaces
) {
    public record SpaceInfo(String name, long used, long committed, long max) {}
}
