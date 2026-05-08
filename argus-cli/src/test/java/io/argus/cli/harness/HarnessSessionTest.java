package io.argus.cli.harness;

import io.argus.cli.doctor.JvmSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HarnessSessionTest {

    @Test
    void retainsSamplesUpToCountCap() {
        HarnessSession s = new HarnessSession(3, 1_000_000L);
        for (int i = 0; i < 10; i++) {
            s.record(new TimedSnapshot(i * 1000L, snap()));
        }
        assertEquals(3, s.size());
        // Oldest retained should be tick 7 (we kept the last 3: 7,8,9)
        assertEquals(7000L, s.oldest().timestampMs());
        assertEquals(9000L, s.mostRecent().timestampMs());
    }

    @Test
    void retainsSamplesUpToAgeCap() {
        // 5 second age cap, 1000 sample headroom.
        HarnessSession s = new HarnessSession(1000, 5_000L);
        s.record(new TimedSnapshot(0L, snap()));
        s.record(new TimedSnapshot(2_000L, snap()));
        s.record(new TimedSnapshot(4_000L, snap()));
        s.record(new TimedSnapshot(10_000L, snap()));
        // The 10s sample makes everything older than 5s evictable.
        // Cutoff = 10_000 - 5_000 = 5_000; samples at 0/2/4 all < cutoff → evicted.
        assertEquals(1, s.size());
        assertEquals(10_000L, s.oldest().timestampMs());
    }

    @Test
    void windowDurationReflectsRetainedSamples() {
        HarnessSession s = new HarnessSession(100, 100_000L);
        s.record(new TimedSnapshot(1_000L, snap()));
        s.record(new TimedSnapshot(5_000L, snap()));
        s.record(new TimedSnapshot(7_500L, snap()));
        assertEquals(6_500L, s.windowDurationMs());
    }

    @Test
    void emptySessionReportsZeroWindow() {
        HarnessSession s = new HarnessSession(10, 10_000L);
        assertTrue(s.isEmpty());
        assertEquals(0L, s.windowDurationMs());
        assertNull(s.mostRecentSnapshot());
    }

    @Test
    void rejectsInvalidCaps() {
        assertThrows(IllegalArgumentException.class, () -> new HarnessSession(0, 1_000L));
        assertThrows(IllegalArgumentException.class, () -> new HarnessSession(10, 0L));
        assertThrows(IllegalArgumentException.class, () -> new HarnessSession(-1, 1_000L));
    }

    @Test
    void snapshotsReturnsImmutableCopy() {
        HarnessSession s = new HarnessSession(10, 100_000L);
        s.record(new TimedSnapshot(1_000L, snap()));
        List<TimedSnapshot> copy = s.snapshots();
        assertEquals(1, copy.size());
        // Mutating the copy must not affect the session.
        copy.clear();
        assertEquals(1, s.size());
    }

    private static JvmSnapshot snap() {
        return new JvmSnapshot(
                0, 0, 0, 0,
                Map.of(),
                List.of(), 0, 0, 0,
                0, 0, 1,
                0, 0, 0,
                Map.of(), 0,
                List.of(),
                0, 0, 0, 0,
                "", "", "", List.of(),
                0L);
    }
}
