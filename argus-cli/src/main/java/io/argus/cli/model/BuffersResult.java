package io.argus.cli.model;

import java.util.List;

/**
 * Result of NIO buffer pool query via BufferPoolMXBean.
 */
public final class BuffersResult {

    private final List<BufferPool> pools;
    private final long totalCount;
    private final long totalCapacity;
    private final long totalUsed;

    public BuffersResult(List<BufferPool> pools, long totalCount, long totalCapacity, long totalUsed) {
        this.pools = pools;
        this.totalCount = totalCount;
        this.totalCapacity = totalCapacity;
        this.totalUsed = totalUsed;
    }

    public List<BufferPool> pools() { return pools; }
    public long totalCount() { return totalCount; }
    public long totalCapacity() { return totalCapacity; }
    public long totalUsed() { return totalUsed; }

    public static final class BufferPool {
        private final String name;
        private final long count;
        private final long totalCapacity;
        private final long memoryUsed;

        public BufferPool(String name, long count, long totalCapacity, long memoryUsed) {
            this.name = name;
            this.count = count;
            this.totalCapacity = totalCapacity;
            this.memoryUsed = memoryUsed;
        }

        public String name() { return name; }
        public long count() { return count; }
        public long totalCapacity() { return totalCapacity; }
        public long memoryUsed() { return memoryUsed; }
    }
}
