package io.argus.aggregator.profile;

/**
 * Immutable carrier for an active W3C trace context (trace id + span id) to be
 * stamped onto OTLP profile samples.
 *
 * <p>This mirrors the trace/span ids that the GC-pause↔trace correlation work
 * (#245, {@code io.argus.server.metrics.TraceContextHolder}) resolves reflectively
 * from an OpenTelemetry SDK. {@code argus-aggregator} keeps zero compile-time
 * dependency on {@code argus-server} and on OpenTelemetry, so the caller passes a
 * resolved context in rather than this module reaching across the seam. When no
 * context is active, callers pass {@link #none()} (or {@code null}) and the
 * encoder omits the link attributes from samples.
 *
 * <p>The OTLP Profiles signal models trace linkage as sample attributes keyed by
 * the semantic-convention attributes {@code trace_id} / {@code span_id}; we attach
 * those when {@link #isPresent()} is {@code true}.
 */
public final class ProfileTraceContext {

    private static final ProfileTraceContext NONE = new ProfileTraceContext(null, null);

    private final String traceId;
    private final String spanId;

    private ProfileTraceContext(String traceId, String spanId) {
        this.traceId = normalize(traceId, 32);
        this.spanId = normalize(spanId, 16);
    }

    /** The empty context: no trace linkage. */
    public static ProfileTraceContext none() {
        return NONE;
    }

    /**
     * Builds a context from the supplied W3C ids. Either may be {@code null} or
     * malformed; malformed/blank ids are dropped (treated as absent). A context
     * with neither a valid trace id nor a valid span id is equivalent to
     * {@link #none()}.
     */
    public static ProfileTraceContext of(String traceId, String spanId) {
        ProfileTraceContext ctx = new ProfileTraceContext(traceId, spanId);
        return ctx.isPresent() ? ctx : NONE;
    }

    /** The active trace id (32-hex lowercase) or {@code null} when absent. */
    public String traceId() {
        return traceId;
    }

    /** The active span id (16-hex lowercase) or {@code null} when absent. */
    public String spanId() {
        return spanId;
    }

    /** {@code true} when at least one of trace id / span id is present and valid. */
    public boolean isPresent() {
        return traceId != null || spanId != null;
    }

    /** Validates a W3C id: must be {@code hexLen} lowercase hex and not all-zero. */
    private static String normalize(String id, int hexLen) {
        if (id == null) {
            return null;
        }
        String t = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.length() == hexLen && t.matches("[0-9a-f]{" + hexLen + "}") && !t.equals("0".repeat(hexLen))) {
            return t;
        }
        return null;
    }
}
