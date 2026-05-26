package io.argus.aggregator;

import io.argus.aggregator.model.MetricSample;
import io.argus.aggregator.store.PodRingBuffer;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PodRingBufferTest {

    @Test
    void appendsAndSnapshotsInOrder() {
        PodRingBuffer buf = new PodRingBuffer(3600);
        Instant now = Instant.now();
        buf.append(new MetricSample(now.minusSeconds(2), 50.0, 1.0, 30.0, 100));
        buf.append(new MetricSample(now.minusSeconds(1), 55.0, 1.5, 32.0, 110));
        buf.append(new MetricSample(now,                 60.0, 2.0, 35.0, 120));
        var snap = buf.snapshot();
        assertEquals(3, snap.size());
        assertEquals(50.0, snap.get(0).heapPercent(), 0.0001);
        assertEquals(60.0, snap.get(2).heapPercent(), 0.0001);
    }

    @Test
    void evictsExpiredSamples() {
        PodRingBuffer buf = new PodRingBuffer(1); // 1 second retention
        Instant old = Instant.now().minusSeconds(10);
        buf.append(new MetricSample(old, 50.0, 1.0, 30.0, 100));
        buf.append(new MetricSample(Instant.now(), 60.0, 2.0, 35.0, 120));
        var snap = buf.snapshot();
        assertEquals(1, snap.size());
        assertEquals(60.0, snap.get(0).heapPercent(), 0.0001);
    }

    @Test
    void latestReturnsMostRecent() {
        PodRingBuffer buf = new PodRingBuffer(3600);
        Instant now = Instant.now();
        buf.append(new MetricSample(now.minusSeconds(2), 50.0, 1.0, 30.0, 100));
        buf.append(new MetricSample(now,                 60.0, 2.0, 35.0, 120));
        assertEquals(60.0, buf.latest().heapPercent(), 0.0001);
    }

    @Test
    void rejectsNonPositiveRetention() {
        assertThrows(IllegalArgumentException.class, () -> new PodRingBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new PodRingBuffer(-1));
    }
}
