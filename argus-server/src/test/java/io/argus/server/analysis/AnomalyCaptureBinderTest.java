package io.argus.server.analysis;

import io.argus.server.analysis.AnomalyCaptureBinder.ProfileCaptureRequest;
import io.argus.server.analysis.AnomalyCaptureBinder.ProfileCaptureResult;
import io.argus.server.analysis.AnomalyDetector.AnomalyType;
import io.argus.server.analysis.AnomalyDetector.CaptureRecommendation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnomalyCaptureBinderTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void bind_carriesTriggeringReasonOntoRequest() {
        String reason = "JVM CPU 97% over threshold 85% for 3 consecutive samples";
        CaptureRecommendation rec = new CaptureRecommendation(T0, AnomalyType.CPU, reason);

        ProfileCaptureRequest req = new AnomalyCaptureBinder().bind(rec);

        // The capture request must carry the recommendation's reason verbatim.
        assertEquals(reason, req.reason());
        assertEquals(AnomalyType.CPU, req.triggerType());
        assertEquals("cpu", req.eventType());
        assertEquals(T0, req.requestedAt());
        assertEquals(AnomalyCaptureBinder.DEFAULT_DURATION, req.duration());
    }

    @Test
    void bind_allocAnomaly_requestsAllocCapture() {
        String reason = "Allocation rate 900000 KB/s over threshold 500000 KB/s for 3 consecutive samples";
        CaptureRecommendation rec = new CaptureRecommendation(T0, AnomalyType.ALLOC, reason);

        ProfileCaptureRequest req = new AnomalyCaptureBinder(Duration.ofSeconds(10)).bind(rec);

        assertEquals("alloc", req.eventType());
        assertEquals(reason, req.reason());
        assertEquals(Duration.ofSeconds(10), req.duration());
    }

    @Test
    void completedArtifact_keepsTheReason() {
        CaptureRecommendation rec = new CaptureRecommendation(T0, AnomalyType.ALLOC, "alloc spike");
        ProfileCaptureRequest req = new AnomalyCaptureBinder().bind(rec);

        ProfileCaptureResult result = req.completed("/captures/abc.collapsed");

        // The captured artifact stays attributable to the triggering anomaly.
        assertEquals("alloc spike", result.reason());
        assertEquals("/captures/abc.collapsed", result.artifactRef());
        assertSame(req, result.request());
    }

    @Test
    void bindActive_usesDetectorRecommendation_endToEnd() {
        // Use the real AnomalyDetector seam: drive it to fire, then bind its
        // recommendation and assert the reason flows through unchanged.
        AnomalyDetector d = new AnomalyDetector(0.85, 500_000.0, 3, 5, true);
        for (int i = 0; i < 3; i++) {
            d.recordCpuSample(0.95, T0.plusSeconds(i));
        }
        CaptureRecommendation rec = d.captureRecommendation();
        assertEquals(rec.reason(), new AnomalyCaptureBinder().bindActive(d).reason());
    }

    @Test
    void bindActive_returnsNullWhenNoRecommendation() {
        AnomalyDetector d = new AnomalyDetector(0.85, 500_000.0, 3, 5, true);
        assertNull(new AnomalyCaptureBinder().bindActive(d));
    }

    @Test
    void bind_rejectsNullRecommendation() {
        assertThrows(IllegalArgumentException.class, () -> new AnomalyCaptureBinder().bind(null));
    }

    @Test
    void constructor_rejectsNonPositiveDuration() {
        assertThrows(IllegalArgumentException.class, () -> new AnomalyCaptureBinder(Duration.ZERO));
    }
}
