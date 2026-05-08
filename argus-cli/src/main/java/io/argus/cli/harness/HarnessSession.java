package io.argus.cli.harness;

import io.argus.cli.doctor.JvmSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded rolling window of {@link TimedSnapshot} samples taken during a
 * harness run, plus accumulated findings produced by snapshot/trend rules.
 *
 * <p>The window has two retention caps:
 * <ul>
 *   <li>{@code maxSamples} — absolute upper bound on samples kept (oldest evicted)</li>
 *   <li>{@code maxAgeMs} — samples older than this relative to the most recent
 *       sample are evicted</li>
 * </ul>
 * Both are enforced on every {@link #record(TimedSnapshot)} call.
 *
 * <p>This class is not thread-safe. The harness engine drives it from a single
 * scheduled thread.
 */
public final class HarnessSession {

    private final int maxSamples;
    private final long maxAgeMs;
    private final Deque<TimedSnapshot> samples = new ArrayDeque<>();

    public HarnessSession(int maxSamples, long maxAgeMs) {
        if (maxSamples <= 0) throw new IllegalArgumentException("maxSamples must be > 0");
        if (maxAgeMs <= 0) throw new IllegalArgumentException("maxAgeMs must be > 0");
        this.maxSamples = maxSamples;
        this.maxAgeMs = maxAgeMs;
    }

    public void record(TimedSnapshot s) {
        samples.addLast(s);
        evictBySize();
        evictByAge();
    }

    private void evictBySize() {
        while (samples.size() > maxSamples) {
            samples.removeFirst();
        }
    }

    private void evictByAge() {
        if (samples.isEmpty()) return;
        long mostRecent = samples.peekLast().timestampMs();
        long cutoff = mostRecent - maxAgeMs;
        while (!samples.isEmpty() && samples.peekFirst().timestampMs() < cutoff) {
            samples.removeFirst();
        }
    }

    public int size() { return samples.size(); }
    public boolean isEmpty() { return samples.isEmpty(); }

    public TimedSnapshot mostRecent() {
        return samples.peekLast();
    }

    public TimedSnapshot oldest() {
        return samples.peekFirst();
    }

    /** Returns a snapshot copy of all samples in chronological order (oldest first). */
    public List<TimedSnapshot> snapshots() {
        return new ArrayList<>(samples);
    }

    /** Wall-clock window length actually retained (most recent − oldest). 0 if &lt;2 samples. */
    public long windowDurationMs() {
        if (samples.size() < 2) return 0L;
        return samples.peekLast().timestampMs() - samples.peekFirst().timestampMs();
    }

    /** Most recent JvmSnapshot, or null if no samples recorded yet. */
    public JvmSnapshot mostRecentSnapshot() {
        TimedSnapshot t = samples.peekLast();
        return t == null ? null : t.snapshot();
    }
}
