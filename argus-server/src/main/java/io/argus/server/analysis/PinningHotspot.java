package io.argus.server.analysis;

/**
 * Represents a pinning hotspot - a unique stack trace location where
 * virtual thread pinning frequently occurs.
 *
 * @param rank           the rank by frequency (1 = most frequent)
 * @param count          the number of pinning events with this stack trace
 * @param percentage     the percentage of total pinned events
 * @param stackTraceHash the hash of the stack trace for grouping
 * @param topFrame       the top frame of the stack trace (most relevant location)
 * @param fullStackTrace the complete stack trace
 * @param taxonomy       the post-JEP-491 pinning bucket for this hotspot
 */
public final class PinningHotspot {
    private final int rank;
    private final long count;
    private final double percentage;
    private final String stackTraceHash;
    private final String topFrame;
    private final String fullStackTrace;
    private final PinningTaxonomy taxonomy;

    public PinningHotspot(int rank, long count, double percentage, String stackTraceHash,
                          String topFrame, String fullStackTrace, PinningTaxonomy taxonomy) {
        this.rank = rank;
        this.count = count;
        this.percentage = percentage;
        this.stackTraceHash = stackTraceHash;
        this.topFrame = topFrame;
        this.fullStackTrace = fullStackTrace;
        this.taxonomy = taxonomy == null ? PinningTaxonomy.UNCLASSIFIED : taxonomy;
    }

    public int rank() { return rank; }
    public long count() { return count; }
    public double percentage() { return percentage; }
    public String stackTraceHash() { return stackTraceHash; }
    public String topFrame() { return topFrame; }
    public String fullStackTrace() { return fullStackTrace; }

    /** Post-JEP-491 pinning classification for this hotspot's stack trace. */
    public PinningTaxonomy taxonomy() { return taxonomy; }
}
