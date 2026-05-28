package io.argus.server.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies graceful degradation of the reflective OTel trace-context capture.
 *
 * <p>The test classpath has no OpenTelemetry SDK, so the holder must report the
 * API as unavailable and return {@code null} from every accessor without ever
 * throwing.
 */
class TraceContextHolderTest {

    @Test
    void otel_reported_absent_on_clean_classpath() {
        assertFalse(TraceContextHolder.isOtelAvailable(),
                "no OTel SDK on the test classpath, so it must report absent");
    }

    @Test
    void currentTraceId_returns_null_when_otel_absent() {
        assertNull(TraceContextHolder.currentTraceId(),
                "trace id must be null with no OTel SDK present");
    }

    @Test
    void currentSpanId_returns_null_when_otel_absent() {
        assertNull(TraceContextHolder.currentSpanId(),
                "span id must be null with no OTel SDK present");
    }

    @Test
    void repeated_lookups_never_throw() {
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 1000; i++) {
                TraceContextHolder.currentTraceId();
                TraceContextHolder.currentSpanId();
            }
        });
    }
}
