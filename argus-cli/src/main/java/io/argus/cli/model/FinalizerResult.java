package io.argus.cli.model;

/**
 * Finalizer queue status.
 */
public final class FinalizerResult {
    private final int pendingCount;
    private final String finalizerThreadState;

    public FinalizerResult(int pendingCount, String finalizerThreadState) {
        this.pendingCount = pendingCount;
        this.finalizerThreadState = finalizerThreadState;
    }

    public int pendingCount() { return pendingCount; }
    public String finalizerThreadState() { return finalizerThreadState; }
}
