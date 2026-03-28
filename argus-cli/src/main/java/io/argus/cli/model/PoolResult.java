package io.argus.cli.model;

import java.util.List;
import java.util.Map;

/**
 * Thread pool analysis result.
 */
public final class PoolResult {
    private final int totalThreads;
    private final int totalPools;
    private final List<PoolInfo> pools;

    public PoolResult(int totalThreads, int totalPools, List<PoolInfo> pools) {
        this.totalThreads = totalThreads;
        this.totalPools = totalPools;
        this.pools = pools;
    }

    public int totalThreads() { return totalThreads; }
    public int totalPools() { return totalPools; }
    public List<PoolInfo> pools() { return pools; }

    public static final class PoolInfo {
        private final String name;
        private final int threadCount;
        private final Map<String, Integer> stateDistribution;

        public PoolInfo(String name, int threadCount, Map<String, Integer> stateDistribution) {
            this.name = name;
            this.threadCount = threadCount;
            this.stateDistribution = stateDistribution;
        }

        public String name() { return name; }
        public int threadCount() { return threadCount; }
        public Map<String, Integer> stateDistribution() { return stateDistribution; }
    }
}
