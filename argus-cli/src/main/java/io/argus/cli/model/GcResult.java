package io.argus.cli.model;

import java.util.List;

/**
 * GC statistics result.
 */
public final class GcResult {
    private final long totalEvents;
    private final double totalPauseMs;
    private final double overheadPercent;
    private final String lastCause;
    private final long heapUsed;
    private final long heapCommitted;
    private final List<CollectorInfo> collectors;

    public GcResult(long totalEvents, double totalPauseMs, double overheadPercent,
                    String lastCause, long heapUsed, long heapCommitted,
                    List<CollectorInfo> collectors) {
        this.totalEvents = totalEvents;
        this.totalPauseMs = totalPauseMs;
        this.overheadPercent = overheadPercent;
        this.lastCause = lastCause;
        this.heapUsed = heapUsed;
        this.heapCommitted = heapCommitted;
        this.collectors = collectors;
    }

    public long totalEvents() { return totalEvents; }
    public double totalPauseMs() { return totalPauseMs; }
    public double overheadPercent() { return overheadPercent; }
    public String lastCause() { return lastCause; }
    public long heapUsed() { return heapUsed; }
    public long heapCommitted() { return heapCommitted; }
    public List<CollectorInfo> collectors() { return collectors; }

    public static final class CollectorInfo {
        private final String name;
        private final long count;
        private final double totalMs;

        public CollectorInfo(String name, long count, double totalMs) {
            this.name = name;
            this.count = count;
            this.totalMs = totalMs;
        }

        public String name() { return name; }
        public long count() { return count; }
        public double totalMs() { return totalMs; }
    }
}
