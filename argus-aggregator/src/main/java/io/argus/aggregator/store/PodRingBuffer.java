package io.argus.aggregator.store;

import io.argus.aggregator.model.MetricSample;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-target append-only ring buffer with time-based retention.
 *
 * <p>Thread-safe via internal synchronization. Samples older than
 * {@link #retentionSeconds} are discarded on every append and read.
 */
public final class PodRingBuffer {

    private final long retentionSeconds;
    private final java.util.Deque<MetricSample> samples = new java.util.ArrayDeque<>();

    public PodRingBuffer(long retentionSeconds) {
        if (retentionSeconds <= 0) {
            throw new IllegalArgumentException("retentionSeconds must be positive");
        }
        this.retentionSeconds = retentionSeconds;
    }

    public long retentionSeconds() {
        return retentionSeconds;
    }

    /** Appends a sample and evicts entries older than retention. */
    public synchronized void append(MetricSample sample) {
        samples.addLast(sample);
        evictExpired(sample.ts());
    }

    /** Returns a snapshot of all samples currently retained, oldest first. */
    public synchronized List<MetricSample> snapshot() {
        evictExpired(Instant.now());
        return new ArrayList<>(samples);
    }

    public synchronized int size() {
        evictExpired(Instant.now());
        return samples.size();
    }

    /** Returns the most recent sample, or null if empty. */
    public synchronized MetricSample latest() {
        evictExpired(Instant.now());
        return samples.peekLast();
    }

    public synchronized void clear() {
        samples.clear();
    }

    private void evictExpired(Instant now) {
        Instant cutoff = now.minus(Duration.ofSeconds(retentionSeconds));
        while (!samples.isEmpty() && samples.peekFirst().ts().isBefore(cutoff)) {
            samples.pollFirst();
        }
    }
}
