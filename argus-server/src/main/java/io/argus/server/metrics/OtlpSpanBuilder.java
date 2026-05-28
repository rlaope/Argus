package io.argus.server.metrics;

import java.security.SecureRandom;

/**
 * Hand-coded OTLP/JSON span encoder for significant JVM-internal events.
 *
 * <p>Argus observes the JVM globally via JFR streaming, so a GC pause is a
 * stop-the-world event, not a per-request hook. This builder emits one OTel
 * span per significant GC pause ("GC Pause") with the JVM as the resource.
 * When a W3C trace context happens to be active on the recording thread, the
 * span carries that trace id so a backend (Tempo/Jaeger/Grafana) can place the
 * pause on the same trace timeline as in-flight work; otherwise the span gets a
 * fresh, self-generated trace id and stands alone (timing-only).
 *
 * <p>Follows the project's zero-dependency philosophy: no protobuf, no OTel
 * SDK — just the OTLP JSON shape that an OTLP/HTTP traces endpoint accepts at
 * {@code /v1/traces}.
 */
public final class OtlpSpanBuilder {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String serviceName;
    private final String version;

    public OtlpSpanBuilder(String serviceName, String version) {
        this.serviceName = serviceName != null ? serviceName : "argus";
        this.version = version != null ? version : "dev";
    }

    /**
     * Builds a complete OTLP/JSON {@code resourceSpans} payload wrapping a single
     * GC-pause span. Suitable for POST to an OTLP/HTTP {@code /v1/traces} endpoint.
     *
     * @param endTimeMillis    wall-clock end time of the pause (epoch millis)
     * @param gcName           collector name (e.g. "G1 Young Generation")
     * @param gcCause          GC cause (e.g. "G1 Evacuation Pause")
     * @param pauseMs          pause duration in milliseconds
     * @param reclaimedBytes   heap bytes reclaimed by this GC (may be 0)
     * @param traceId          active W3C trace id (32-hex) or {@code null}; when
     *                         {@code null} a fresh trace id is generated
     * @return OTLP/JSON traces payload
     */
    public String buildGcPauseSpan(long endTimeMillis, String gcName, String gcCause,
                                   double pauseMs, long reclaimedBytes, String traceId) {
        long endNano = endTimeMillis * 1_000_000L;
        long startNano = endNano - (long) (pauseMs * 1_000_000.0);
        String tid = normalizeTraceId(traceId);
        String spanId = randomHex(16);

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"resourceSpans\":[{");
        appendResource(sb);
        sb.append(",\"scopeSpans\":[{");
        sb.append("\"scope\":{\"name\":\"io.argus.tracing\",\"version\":\"")
                .append(escape(version)).append("\"},");
        sb.append("\"spans\":[");
        appendGcPauseSpanObject(sb, tid, spanId, startNano, endNano,
                gcName, gcCause, pauseMs, reclaimedBytes);
        sb.append("]}]}]}");
        return sb.toString();
    }

    /**
     * Appends a single GC-pause span object to {@code sb} (no surrounding
     * commas). Exposed package-private so the exporter can batch many spans
     * into one {@code scopeSpans} entry.
     */
    void appendGcPauseSpanObject(StringBuilder sb, String traceId, String spanId,
                                 long startNano, long endNano, String gcName, String gcCause,
                                 double pauseMs, long reclaimedBytes) {
        sb.append("{");
        sb.append("\"traceId\":\"").append(normalizeTraceId(traceId)).append("\",");
        sb.append("\"spanId\":\"").append(spanId != null ? spanId : randomHex(16)).append("\",");
        sb.append("\"name\":\"GC Pause\",");
        // SPAN_KIND_INTERNAL = 1
        sb.append("\"kind\":1,");
        sb.append("\"startTimeUnixNano\":\"").append(startNano).append("\",");
        sb.append("\"endTimeUnixNano\":\"").append(endNano).append("\",");
        sb.append("\"attributes\":[");
        appendStringAttribute(sb, "gc.name", gcName != null ? gcName : "Unknown");
        sb.append(',');
        appendStringAttribute(sb, "gc.cause", gcCause != null ? gcCause : "Unknown");
        sb.append(',');
        appendDoubleAttribute(sb, "gc.pause_ms", pauseMs);
        sb.append(',');
        appendIntAttribute(sb, "heap.reclaimed_bytes", reclaimedBytes);
        sb.append("]}");
    }

    private void appendResource(StringBuilder sb) {
        sb.append("\"resource\":{\"attributes\":[");
        appendStringAttribute(sb, "service.name", serviceName);
        sb.append(',');
        appendStringAttribute(sb, "service.version", version);
        sb.append(',');
        appendStringAttribute(sb, "telemetry.sdk.name", "argus");
        sb.append(',');
        appendStringAttribute(sb, "telemetry.sdk.language", "java");
        sb.append("]}");
    }

    private void appendStringAttribute(StringBuilder sb, String key, String value) {
        sb.append("{\"key\":\"").append(escape(key))
                .append("\",\"value\":{\"stringValue\":\"").append(escape(value)).append("\"}}");
    }

    private void appendDoubleAttribute(StringBuilder sb, String key, double value) {
        sb.append("{\"key\":\"").append(escape(key))
                .append("\",\"value\":{\"doubleValue\":").append(value).append("}}");
    }

    private void appendIntAttribute(StringBuilder sb, String key, long value) {
        sb.append("{\"key\":\"").append(escape(key))
                .append("\",\"value\":{\"intValue\":\"").append(value).append("\"}}");
    }

    /**
     * Returns a valid 32-hex-char trace id: the supplied one when it already
     * matches, otherwise a freshly generated random id. OTel/OTLP rejects spans
     * with malformed or all-zero trace ids, so we never pass those through.
     */
    static String normalizeTraceId(String traceId) {
        if (traceId != null) {
            String t = traceId.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.length() == 32 && t.matches("[0-9a-f]{32}") && !t.equals("0".repeat(32))) {
                return t;
            }
        }
        return randomHex(32);
    }

    private static String randomHex(int chars) {
        byte[] bytes = new byte[chars / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(chars);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
