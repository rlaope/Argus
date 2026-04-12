package io.argus.cli.model;

import java.util.List;

/**
 * Object age distribution from the young generation survivor spaces.
 * Age data is extracted from GC log debug output (-Xlog:gc+age=debug)
 * or from jcmd GC.heap_info when available.
 */
public record AgeDistribution(
        List<AgeEntry> entries,
        int tenuringThreshold,
        int maxTenuringThreshold,
        long desiredSurvivorSize,
        long survivorCapacity
) {
    /**
     * A single age bucket: objects of this age in the survivor space.
     */
    public record AgeEntry(int age, long bytes, long cumulativeBytes) {}
}
