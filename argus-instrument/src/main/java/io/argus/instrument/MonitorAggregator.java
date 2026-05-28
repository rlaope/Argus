package io.argus.instrument;

/**
 * Thread-safe windowed aggregation for {@link InstrumentMode#MONITOR}.
 *
 * <p>Instead of one event per invocation, MONITOR mode records each call's
 * outcome and wall-clock cost into rolling counters, then periodically emits a
 * single {@link CaptureEvent#monitor} summary covering the window and resets.
 * This keeps a hot method's event volume flat regardless of call rate — the
 * cheapest of the three modes by design.
 *
 * <p>All mutation is under a single monitor lock; the per-call hot path
 * ({@link #record}) only does a handful of arithmetic updates, so contention is
 * negligible relative to the cost of the instrumented method itself.
 */
public final class MonitorAggregator {

    private final Object lock = new Object();

    private long count;
    private long success;
    private long failure;
    private long totalNanos;
    private long maxNanos;

    /** Records one completed invocation. */
    public void record(boolean ok, long wallNanos) {
        long n = wallNanos < 0 ? 0L : wallNanos;
        synchronized (lock) {
            count++;
            if (ok) {
                success++;
            } else {
                failure++;
            }
            totalNanos += n;
            if (n > maxNanos) {
                maxNanos = n;
            }
        }
    }

    /**
     * Produces a MONITOR event over the accumulated window and resets all
     * counters atomically, so successive snapshots never double-count.
     */
    public CaptureEvent snapshotAndReset(long ts, String clazz, String method) {
        long c;
        long s;
        long f;
        long total;
        long max;
        synchronized (lock) {
            c = count;
            s = success;
            f = failure;
            total = totalNanos;
            max = maxNanos;
            count = 0;
            success = 0;
            failure = 0;
            totalNanos = 0;
            maxNanos = 0;
        }
        double avgMs = c == 0 ? 0.0 : (total / (double) c) / 1_000_000.0;
        double maxMs = max / 1_000_000.0;
        return CaptureEvent.monitor(ts, clazz, method, c, s, f, avgMs, maxMs);
    }
}
