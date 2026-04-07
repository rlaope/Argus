package io.argus.cli.gclog;

/**
 * A single GC event parsed from a GC log file.
 */
public final class GcEvent {
    private final double timestampSec;
    private final String type;         // "Young", "Mixed", "Full", "Concurrent"
    private final String cause;        // "G1 Evacuation Pause", "Metadata GC Threshold", etc.
    private final long pauseMs;        // STW pause duration in milliseconds
    private final long heapBeforeKB;
    private final long heapAfterKB;
    private final long heapTotalKB;

    public GcEvent(double timestampSec, String type, String cause, long pauseMs,
                   long heapBeforeKB, long heapAfterKB, long heapTotalKB) {
        this.timestampSec = timestampSec;
        this.type = type;
        this.cause = cause;
        this.pauseMs = pauseMs;
        this.heapBeforeKB = heapBeforeKB;
        this.heapAfterKB = heapAfterKB;
        this.heapTotalKB = heapTotalKB;
    }

    public double timestampSec() { return timestampSec; }
    public String type() { return type; }
    public String cause() { return cause; }
    public long pauseMs() { return pauseMs; }
    public long heapBeforeKB() { return heapBeforeKB; }
    public long heapAfterKB() { return heapAfterKB; }
    public long heapTotalKB() { return heapTotalKB; }
    public long reclaimedKB() { return heapBeforeKB - heapAfterKB; }
    public boolean isFullGc() { return type.toLowerCase().contains("full"); }
    public boolean isConcurrent() { return type.toLowerCase().contains("concurrent"); }
}
