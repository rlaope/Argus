package io.argus.server.analysis;

import io.argus.core.event.GCEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the GC pause-time histogram added for the Prometheus OpenMetrics export.
 *
 * <p>Buckets are cumulative (a pause ≤ bound counts in that bucket and every
 * larger one), the +Inf bucket equals the total observation count, and
 * {@code clear()} resets all histogram state.
 */
class GCAnalyzerPauseHistogramTest {

    private static GCEvent pause(double seconds) {
        long nanos = (long) (seconds * 1_000_000_000.0);
        return GCEvent.pause(Instant.now(), nanos, "G1 Young Generation", "G1 Evacuation Pause");
    }

    @Test
    void empty_histogram_has_zero_count() {
        GCAnalyzer a = new GCAnalyzer();
        GCAnalyzer.PauseHistogram h = a.getPauseHistogram();
        assertEquals(0, h.count());
        assertEquals(0.0, h.sumSeconds(), 1e-9);
    }

    @Test
    void cumulative_buckets_and_count() {
        GCAnalyzer a = new GCAnalyzer();
        // bounds include 0.01, 0.05, 0.1, 0.25 ...
        a.recordGCEvent(pause(0.008)); // ≤ 0.01
        a.recordGCEvent(pause(0.04));  // ≤ 0.05
        a.recordGCEvent(pause(0.2));   // ≤ 0.25
        a.recordGCEvent(pause(7.0));   // > 5.0 → only +Inf

        GCAnalyzer.PauseHistogram h = a.getPauseHistogram();
        double[] bounds = h.upperBounds();
        long[] counts = h.cumulativeCounts();

        // total observations
        assertEquals(4, h.count());
        assertEquals(counts[counts.length - 1], h.count(), "+Inf bucket == total count");

        // bucket for le=0.01 should include only the 0.008 pause
        int idx001 = indexOf(bounds, 0.01);
        assertEquals(1, counts[idx001]);

        // bucket for le=0.05 cumulative includes 0.008 and 0.04
        int idx005 = indexOf(bounds, 0.05);
        assertEquals(2, counts[idx005]);

        // bucket for le=0.25 cumulative includes 0.008, 0.04, 0.2
        int idx025 = indexOf(bounds, 0.25);
        assertEquals(3, counts[idx025]);

        // bucket for le=5.0 cumulative still 3 (the 7.0s pause exceeds it)
        int idx5 = indexOf(bounds, 5.0);
        assertEquals(3, counts[idx5]);

        // sum ≈ 0.008 + 0.04 + 0.2 + 7.0
        assertEquals(7.248, h.sumSeconds(), 1e-3);
    }

    @Test
    void monotonic_non_decreasing_buckets() {
        GCAnalyzer a = new GCAnalyzer();
        for (double s : new double[]{0.002, 0.03, 0.07, 0.4, 1.5, 3.0}) {
            a.recordGCEvent(pause(s));
        }
        long[] counts = a.getPauseHistogram().cumulativeCounts();
        for (int i = 1; i < counts.length; i++) {
            assertTrue(counts[i] >= counts[i - 1],
                    "cumulative buckets must be non-decreasing at index " + i);
        }
    }

    @Test
    void clear_resets_histogram() {
        GCAnalyzer a = new GCAnalyzer();
        a.recordGCEvent(pause(0.05));
        a.recordGCEvent(pause(0.5));
        assertEquals(2, a.getPauseHistogram().count());

        a.clear();
        GCAnalyzer.PauseHistogram h = a.getPauseHistogram();
        assertEquals(0, h.count());
        assertEquals(0.0, h.sumSeconds(), 1e-9);
        for (long c : h.cumulativeCounts()) assertEquals(0, c);
    }

    private static int indexOf(double[] bounds, double value) {
        for (int i = 0; i < bounds.length; i++) {
            if (Math.abs(bounds[i] - value) < 1e-9) return i;
        }
        throw new AssertionError("bucket bound not found: " + value);
    }
}
