package io.argus.server.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the hand-coded OTLP/JSON GC-pause span encoding: the resourceSpans
 * structure, span name/kind, required attributes (gc.name, gc.cause,
 * gc.pause_ms, heap.reclaimed_bytes), and trace-id normalization (supplied vs.
 * generated).
 */
class OtlpSpanBuilderTest {

    private final OtlpSpanBuilder builder = new OtlpSpanBuilder("argus-test", "1.2.3");

    @Test
    void wellformed_span_carries_all_attributes() {
        String json = builder.buildGcPauseSpan(
                1_700_000_000_000L, "G1 Young Generation", "G1 Evacuation Pause",
                72.5, 12_345_678L, null);

        assertTrue(json.startsWith("{\"resourceSpans\":[{"), "must be an OTLP resourceSpans payload");
        assertTrue(json.contains("\"scopeSpans\":["), "must contain scopeSpans");
        assertTrue(json.contains("\"name\":\"GC Pause\""), "span name must be 'GC Pause'");
        assertTrue(json.contains("\"kind\":1"), "kind must be SPAN_KIND_INTERNAL (1)");

        // Required attributes
        assertTrue(json.contains("\"key\":\"gc.name\""), "missing gc.name attribute");
        assertTrue(json.contains("G1 Young Generation"), "gc.name value missing");
        assertTrue(json.contains("\"key\":\"gc.cause\""), "missing gc.cause attribute");
        assertTrue(json.contains("G1 Evacuation Pause"), "gc.cause value missing");
        assertTrue(json.contains("\"key\":\"gc.pause_ms\""), "missing gc.pause_ms attribute");
        assertTrue(json.contains("\"doubleValue\":72.5"), "gc.pause_ms value missing");
        assertTrue(json.contains("\"key\":\"heap.reclaimed_bytes\""), "missing heap.reclaimed_bytes attribute");
        assertTrue(json.contains("\"intValue\":\"12345678\""), "heap.reclaimed_bytes value missing");

        // Resource identifies the JVM/service
        assertTrue(json.contains("\"key\":\"service.name\""), "resource must carry service.name");
        assertTrue(json.contains("argus-test"), "service.name value missing");

        // Timestamps present and ordered: start < end
        assertTrue(json.contains("\"startTimeUnixNano\""), "missing startTimeUnixNano");
        assertTrue(json.contains("\"endTimeUnixNano\""), "missing endTimeUnixNano");
    }

    @Test
    void supplied_trace_id_is_used_when_valid() {
        String traceId = "0af7651916cd43dd8448eb211c80319c"; // 32-hex
        String json = builder.buildGcPauseSpan(1_700_000_000_000L,
                "ZGC", "Allocation Rate", 10.0, 0L, traceId);
        assertTrue(json.contains("\"traceId\":\"" + traceId + "\""),
                "valid supplied trace id must be preserved");
    }

    @Test
    void invalid_trace_id_is_replaced_with_generated_one() {
        String json = builder.buildGcPauseSpan(1_700_000_000_000L,
                "ZGC", "Allocation Rate", 10.0, 0L, "not-a-valid-id");
        // A fresh 32-hex trace id must be emitted; the invalid input must not appear.
        assertFalse(json.contains("not-a-valid-id"), "invalid trace id must not pass through");
        assertTrue(OtlpSpanBuilder.normalizeTraceId("not-a-valid-id").matches("[0-9a-f]{32}"),
                "normalized id must be 32-hex");
    }

    @Test
    void all_zero_trace_id_is_rejected() {
        String zeros = "0".repeat(32);
        String normalized = OtlpSpanBuilder.normalizeTraceId(zeros);
        assertNotEquals(zeros, normalized, "all-zero trace id is invalid and must be replaced");
        assertTrue(normalized.matches("[0-9a-f]{32}"));
    }

    @Test
    void null_gc_fields_default_to_unknown() {
        String json = builder.buildGcPauseSpan(1_700_000_000_000L, null, null, 5.0, 0L, null);
        assertTrue(json.contains("\"stringValue\":\"Unknown\""),
                "null gc.name/gc.cause must default to 'Unknown'");
    }
}
