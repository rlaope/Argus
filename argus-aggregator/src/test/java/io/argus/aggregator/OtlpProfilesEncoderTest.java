package io.argus.aggregator;

import io.argus.aggregator.profile.OtlpProfilesEncoder;
import io.argus.aggregator.profile.OtlpProfilesEncoder.ProfilesData;
import io.argus.aggregator.profile.ProfileTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the OTLP Profiles (pprof-shaped) encoder.
 *
 * <p>Covers the W5 acceptance criteria: lossless collapsed→ProfilesData→collapsed
 * round-trip, trace/span linkage presence/absence, the experimental flag gate
 * ({@code argus.profiles.otlp.enabled}, default off ⇒ no-op), and the hand-coded
 * OTLP/JSON shape (string/function/location tables + samples).
 */
class OtlpProfilesEncoderTest {

    @AfterEach
    void clearFlag() {
        System.clearProperty(OtlpProfilesEncoder.FLAG_ENABLED);
    }

    // ── Criterion 2: lossless round-trip ──────────────────────────────────────

    @Test
    void roundTripIsLosslessOnSampleValuesAndFrames() {
        Map<String, Long> collapsed = new LinkedHashMap<>();
        collapsed.put("main;a;b", 10L);
        collapsed.put("main;a;c", 5L);
        collapsed.put("main;d", 3L);

        ProfilesData data = OtlpProfilesEncoder.toProfilesData(collapsed);
        Map<String, Long> back = OtlpProfilesEncoder.toCollapsed(data);

        assertEquals(collapsed, back, "round-trip must preserve every stack and count");
    }

    @Test
    void roundTripInternsSharedFramesOnceAndKeepsLeafFirstOrdering() {
        Map<String, Long> collapsed = new LinkedHashMap<>();
        collapsed.put("main;a;b", 7L);
        collapsed.put("main;a;d", 4L);

        ProfilesData data = OtlpProfilesEncoder.toProfilesData(collapsed);

        // String table: index 0 is "" (pprof convention) + {main,a,b,d} = 5 distinct.
        assertEquals("", data.stringTable().get(0));
        assertEquals(5, data.stringTable().size(),
                "shared frames main/a interned once: ''+main+a+b+d");

        Map<String, Long> back = OtlpProfilesEncoder.toCollapsed(data);
        assertEquals(collapsed, back);
    }

    @Test
    void roundTripSkipsNonPositiveCounts() {
        Map<String, Long> collapsed = new LinkedHashMap<>();
        collapsed.put("main;a", 5L);
        collapsed.put("main;zero", 0L);
        collapsed.put("main;neg", -3L);

        ProfilesData data = OtlpProfilesEncoder.toProfilesData(collapsed);
        Map<String, Long> back = OtlpProfilesEncoder.toCollapsed(data);

        assertEquals(Map.of("main;a", 5L), back);
        assertEquals(1, data.samples().size());
    }

    // ── Criterion 3: trace/span linkage ───────────────────────────────────────

    @Test
    void samplesCarryTraceAndSpanIdWhenContextPresent() {
        Map<String, Long> collapsed = Map.of("main;a", 4L);
        String traceId = "0123456789abcdef0123456789abcdef";
        String spanId = "fedcba9876543210";

        String json = OtlpProfilesEncoder.encodeUnconditionally(
                collapsed, ProfileTraceContext.of(traceId, spanId));

        assertTrue(json.contains("\"key\":\"trace_id\""), json);
        assertTrue(json.contains("\"stringValue\":\"" + traceId + "\""), json);
        assertTrue(json.contains("\"key\":\"span_id\""), json);
        assertTrue(json.contains("\"stringValue\":\"" + spanId + "\""), json);
    }

    @Test
    void samplesOmitLinkageWhenContextAbsent() {
        Map<String, Long> collapsed = Map.of("main;a", 4L);

        String json = OtlpProfilesEncoder.encodeUnconditionally(
                collapsed, ProfileTraceContext.none());

        assertFalse(json.contains("trace_id"), json);
        assertFalse(json.contains("span_id"), json);
        assertFalse(json.contains("\"attributes\":[{\"key\":\"trace_id\""), json);
    }

    @Test
    void malformedTraceContextIsTreatedAsAbsent() {
        Map<String, Long> collapsed = Map.of("main;a", 4L);

        // Too short / all-zero ids are invalid -> none().
        ProfileTraceContext ctx = ProfileTraceContext.of("not-hex", "0000000000000000");
        assertFalse(ctx.isPresent());

        String json = OtlpProfilesEncoder.encodeUnconditionally(collapsed, ctx);
        assertFalse(json.contains("trace_id"), json);
        assertFalse(json.contains("span_id"), json);
    }

    // ── Criterion 4: experimental flag gate (default off ⇒ no-op) ─────────────

    @Test
    void encodeIsNoOpWhenFlagDisabledByDefault() {
        assertFalse(OtlpProfilesEncoder.isEnabled());
        String json = OtlpProfilesEncoder.encode(Map.of("main;a", 4L), ProfileTraceContext.none());
        assertNull(json, "encode must be a no-op (null) when the experimental flag is off");
    }

    @Test
    void encodeEmitsWhenFlagEnabled() {
        System.setProperty(OtlpProfilesEncoder.FLAG_ENABLED, "true");
        assertTrue(OtlpProfilesEncoder.isEnabled());
        String json = OtlpProfilesEncoder.encode(Map.of("main;a", 4L), ProfileTraceContext.none());
        assertNotNull(json);
        assertTrue(json.startsWith("{\"resourceProfiles\":["), json);
    }

    // ── Criterion 1: OTLP/JSON ProfilesData shape ─────────────────────────────

    @Test
    void emitsProfilesDataShapeWithTables() {
        Map<String, Long> collapsed = new LinkedHashMap<>();
        collapsed.put("main;a", 2L);

        String json = OtlpProfilesEncoder.encodeUnconditionally(collapsed, ProfileTraceContext.none());

        assertTrue(json.startsWith("{\"resourceProfiles\":[{"), json);
        assertTrue(json.contains("\"scopeProfiles\":["), json);
        assertTrue(json.contains("\"scope\":{\"name\":\"io.argus.profiles\""), json);
        assertTrue(json.contains("\"sampleType\":[{\"type\":\"samples\",\"unit\":\"count\"}]"), json);
        assertTrue(json.contains("\"stringTable\":["), json);
        assertTrue(json.contains("\"function\":["), json);
        assertTrue(json.contains("\"location\":["), json);
        assertTrue(json.contains("\"sample\":[{\"locationIndex\":["), json);
        assertTrue(json.contains("\"value\":[\"2\"]"), json);
    }
}
