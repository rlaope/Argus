package io.argus.cli.harness;

import io.argus.cli.doctor.JvmSnapshot;

/**
 * A {@link JvmSnapshot} paired with the wall-clock time it was captured.
 *
 * <p>Used by {@link HarnessSession} to maintain an ordered rolling window
 * of samples for trend-based analysis.
 */
public final class TimedSnapshot {

    private final long timestampMs;
    private final JvmSnapshot snapshot;

    public TimedSnapshot(long timestampMs, JvmSnapshot snapshot) {
        this.timestampMs = timestampMs;
        this.snapshot = snapshot;
    }

    public long timestampMs() { return timestampMs; }
    public JvmSnapshot snapshot() { return snapshot; }
}
