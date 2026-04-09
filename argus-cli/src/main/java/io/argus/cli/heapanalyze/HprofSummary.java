package io.argus.cli.heapanalyze;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Summary of an HPROF heap dump analysis.
 */
public record HprofSummary(
        String fileName,
        long fileSize,
        int idSize,
        Map<String, long[]> histogram,   // className → [count, shallowBytes]
        long totalInstances,
        long totalBytes,
        long totalArrays,
        long totalArrayBytes,
        long stringCount,
        int classCount
) {
    /**
     * Returns the top N classes by shallow size, descending.
     */
    public List<Map.Entry<String, long[]>> topBySize(int n) {
        return histogram.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(n)
                .collect(Collectors.toList());
    }

    /**
     * Returns the top N classes by instance count, descending.
     */
    public List<Map.Entry<String, long[]>> topByCount(int n) {
        return histogram.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(n)
                .collect(Collectors.toList());
    }

    /**
     * Returns total object count (instances + arrays).
     */
    public long totalObjects() {
        return totalInstances + totalArrays;
    }

    /**
     * Returns total shallow bytes (instances + arrays).
     */
    public long totalShallowBytes() {
        return totalBytes + totalArrayBytes;
    }
}
