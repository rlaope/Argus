package io.argus.server.analysis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnomalyDetectorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** sustainedSamples=3, ring=5, capture recommendations on. */
    private static AnomalyDetector detector() {
        return new AnomalyDetector(0.85, 500_000.0, 3, 5, true);
    }

    // ── CPU ──────────────────────────────────────────────────────────────────

    @Test
    void cpu_underThreshold_neverFires() {
        AnomalyDetector d = detector();
        for (int i = 0; i < 10; i++) {
            assertNull(d.recordCpuSample(0.50, T0.plusSeconds(i)));
        }
        assertTrue(d.recentAnomalies().isEmpty());
        assertFalse(d.isCaptureRecommended());
    }

    @Test
    void cpu_overThresholdButNotSustained_doesNotFire() {
        AnomalyDetector d = detector();
        // Two over-threshold samples, short of the 3-sample sustained window.
        assertNull(d.recordCpuSample(0.95, T0));
        assertNull(d.recordCpuSample(0.95, T0.plusSeconds(1)));
        assertTrue(d.recentAnomalies().isEmpty());
    }

    @Test
    void cpu_sustainedOverThreshold_firesOnce() {
        AnomalyDetector d = detector();
        assertNull(d.recordCpuSample(0.95, T0));
        assertNull(d.recordCpuSample(0.92, T0.plusSeconds(1)));
        AnomalyDetector.AnomalyEvent fired = d.recordCpuSample(0.99, T0.plusSeconds(2));

        assertNotNull(fired);
        assertEquals(AnomalyDetector.AnomalyType.CPU, fired.type());
        assertEquals(0.99, fired.value(), 1e-9);
        assertEquals(0.85, fired.threshold(), 1e-9);
        assertEquals(1, d.recentAnomalies().size());
    }

    @Test
    void cpu_singleUnderSample_resetsStreak() {
        AnomalyDetector d = detector();
        d.recordCpuSample(0.95, T0);
        d.recordCpuSample(0.95, T0.plusSeconds(1));
        // Dip under threshold resets the sustained streak.
        assertNull(d.recordCpuSample(0.10, T0.plusSeconds(2)));
        // Next two over-threshold samples are not yet a full window.
        assertNull(d.recordCpuSample(0.95, T0.plusSeconds(3)));
        assertNull(d.recordCpuSample(0.95, T0.plusSeconds(4)));
        assertTrue(d.recentAnomalies().isEmpty());
        // Third consecutive over-threshold sample finally fires.
        assertNotNull(d.recordCpuSample(0.95, T0.plusSeconds(5)));
    }

    @Test
    void cpu_reasonStringMentionsThresholdAndWindow() {
        AnomalyDetector d = detector();
        d.recordCpuSample(0.95, T0);
        d.recordCpuSample(0.95, T0.plusSeconds(1));
        AnomalyDetector.AnomalyEvent fired = d.recordCpuSample(0.95, T0.plusSeconds(2));

        assertNotNull(fired);
        String reason = fired.reason();
        assertTrue(reason.contains("CPU"), reason);
        assertTrue(reason.contains("85%"), reason);
        assertTrue(reason.contains("3 consecutive"), reason);
    }

    // ── Allocation ─────────────────────────────────────────────────────────────

    @Test
    void alloc_sustainedOverThreshold_firesWithCorrectType() {
        AnomalyDetector d = detector();
        assertNull(d.recordAllocSample(600_000, T0));
        assertNull(d.recordAllocSample(700_000, T0.plusSeconds(1)));
        AnomalyDetector.AnomalyEvent fired = d.recordAllocSample(800_000, T0.plusSeconds(2));

        assertNotNull(fired);
        assertEquals(AnomalyDetector.AnomalyType.ALLOC, fired.type());
        assertEquals(800_000, fired.value(), 1e-6);
        assertTrue(fired.reason().contains("Allocation rate"), fired.reason());
        assertTrue(fired.reason().contains("KB/s"), fired.reason());
    }

    @Test
    void alloc_underThreshold_neverFires() {
        AnomalyDetector d = detector();
        for (int i = 0; i < 6; i++) {
            assertNull(d.recordAllocSample(100_000, T0.plusSeconds(i)));
        }
        assertTrue(d.recentAnomalies().isEmpty());
    }

    // ── Ring bound + recommendation ──────────────────────────────────────────────

    @Test
    void ringBound_isRespected() {
        // ring=2 so older anomalies are evicted; fire 4 times.
        AnomalyDetector d = new AnomalyDetector(0.85, 500_000.0, 1, 2, true);
        for (int i = 0; i < 4; i++) {
            assertNotNull(d.recordCpuSample(0.95, T0.plusSeconds(i)));
        }
        List<AnomalyDetector.AnomalyEvent> recent = d.recentAnomalies();
        assertEquals(2, recent.size());
        // The two retained anomalies are the most recent (oldest first).
        assertEquals(T0.plusSeconds(2), recent.get(0).timestamp());
        assertEquals(T0.plusSeconds(3), recent.get(1).timestamp());
    }

    @Test
    void captureRecommendation_setOnFire_andClearable() {
        AnomalyDetector d = new AnomalyDetector(0.85, 500_000.0, 1, 5, true);
        assertFalse(d.isCaptureRecommended());

        d.recordCpuSample(0.95, T0);
        assertTrue(d.isCaptureRecommended());
        AnomalyDetector.CaptureRecommendation rec = d.captureRecommendation();
        assertNotNull(rec);
        assertEquals(AnomalyDetector.AnomalyType.CPU, rec.triggerType());

        d.clearCaptureRecommendation();
        assertFalse(d.isCaptureRecommended());
        // Anomaly history is independent of the recommendation flag.
        assertEquals(1, d.recentAnomalies().size());
    }

    @Test
    void captureRecommendation_disabled_noFlagButHistoryKept() {
        AnomalyDetector d = new AnomalyDetector(0.85, 500_000.0, 1, 5, false);
        assertNotNull(d.recordCpuSample(0.95, T0));
        assertFalse(d.isCaptureRecommended());
        assertNull(d.captureRecommendation());
        assertEquals(1, d.recentAnomalies().size());
    }

    @Test
    void latest_returnsMostRecent() {
        AnomalyDetector d = new AnomalyDetector(0.85, 500_000.0, 1, 5, true);
        assertNull(d.latest());
        d.recordCpuSample(0.95, T0);
        d.recordAllocSample(600_000, T0.plusSeconds(1));
        AnomalyDetector.AnomalyEvent latest = d.latest();
        assertNotNull(latest);
        assertEquals(AnomalyDetector.AnomalyType.ALLOC, latest.type());
        assertEquals(T0.plusSeconds(1), latest.timestamp());
    }

    @Test
    void clear_resetsEverything() {
        AnomalyDetector d = new AnomalyDetector(0.85, 500_000.0, 1, 5, true);
        d.recordCpuSample(0.95, T0);
        assertFalse(d.recentAnomalies().isEmpty());
        d.clear();
        assertTrue(d.recentAnomalies().isEmpty());
        assertFalse(d.isCaptureRecommended());
        assertNull(d.latest());
    }
}
