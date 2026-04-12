package io.argus.cli.gclog;

/**
 * A single GC event parsed from a GC log file.
 */
public final class GcEvent {
    private final double timestampSec;
    private final String type;         // "Young", "Mixed", "Full", "Concurrent"
    private final String cause;        // "G1 Evacuation Pause", "Metadata GC Threshold", etc.
    private final double pauseMs;      // STW pause duration in milliseconds (sub-ms precision)
    private final long heapBeforeKB;
    private final long heapAfterKB;
    private final long heapTotalKB;
    private final boolean fullGc;
    private final boolean concurrent;

    public GcEvent(double timestampSec, String type, String cause, double pauseMs,
                   long heapBeforeKB, long heapAfterKB, long heapTotalKB) {
        this.timestampSec = timestampSec;
        this.type = type;
        this.cause = cause;
        this.pauseMs = pauseMs;
        this.heapBeforeKB = heapBeforeKB;
        this.heapAfterKB = heapAfterKB;
        this.heapTotalKB = heapTotalKB;
        String lower = type.toLowerCase();
        this.fullGc = lower.contains("full");
        this.concurrent = lower.contains("concurrent");
    }

    public double timestampSec() { return timestampSec; }
    public String type() { return type; }
    public String cause() { return cause; }
    public double pauseMs() { return pauseMs; }
    public long heapBeforeKB() { return heapBeforeKB; }
    public long heapAfterKB() { return heapAfterKB; }
    public long heapTotalKB() { return heapTotalKB; }
    public long reclaimedKB() { return heapBeforeKB - heapAfterKB; }
    public boolean isFullGc() { return fullGc; }
    public boolean isConcurrent() { return concurrent; }
}
