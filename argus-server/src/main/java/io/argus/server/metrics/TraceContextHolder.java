package io.argus.server.metrics;

import java.lang.reflect.Method;

/**
 * Best-effort, reflective capture of the active W3C trace context.
 *
 * <p>If an OpenTelemetry SDK is on the target's classpath, this reads the
 * current span's trace id via {@code io.opentelemetry.api.trace.Span.current()}
 * → {@code getSpanContext()} → {@code getTraceId()}, all through reflection so
 * that {@code argus-server} keeps <strong>zero</strong> compile-time dependency
 * on OpenTelemetry. When the SDK is absent (or no span is active, or the span
 * is invalid), every accessor returns {@code null} and never throws.
 *
 * <p>This is the source wired into
 * {@link PrometheusMetricsCollector#setTraceIdSupplier(java.util.function.Supplier)}
 * so the Phase-1 exemplar plumbing populates only when a real OTel context
 * exists. It is also consulted at GC-pause time to stamp a trace id onto
 * exported spans / correlation windows.
 */
public final class TraceContextHolder {

    private static final System.Logger LOG = System.getLogger(TraceContextHolder.class.getName());

    /** Reflective handles, resolved once. All {@code null} when OTel is absent. */
    private static final Method SPAN_CURRENT;
    private static final Method SPAN_GET_CONTEXT;
    private static final Method CONTEXT_GET_TRACE_ID;
    private static final Method CONTEXT_GET_SPAN_ID;
    private static final Method CONTEXT_IS_VALID;

    /** Whether the OTel trace API resolved on this classpath. */
    private static final boolean AVAILABLE;

    static {
        Method spanCurrent = null;
        Method spanGetContext = null;
        Method ctxGetTraceId = null;
        Method ctxGetSpanId = null;
        Method ctxIsValid = null;
        boolean available = false;
        try {
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            Class<?> spanContextClass = Class.forName("io.opentelemetry.api.trace.SpanContext");
            spanCurrent = spanClass.getMethod("current");
            spanGetContext = spanClass.getMethod("getSpanContext");
            ctxGetTraceId = spanContextClass.getMethod("getTraceId");
            ctxGetSpanId = spanContextClass.getMethod("getSpanId");
            ctxIsValid = spanContextClass.getMethod("isValid");
            available = true;
        } catch (Throwable t) {
            // OTel SDK not present — degrade silently to timing-only mode.
            LOG.log(System.Logger.Level.DEBUG,
                    "OpenTelemetry trace API not on classpath; trace correlation degrades to timing-only");
        }
        SPAN_CURRENT = spanCurrent;
        SPAN_GET_CONTEXT = spanGetContext;
        CONTEXT_GET_TRACE_ID = ctxGetTraceId;
        CONTEXT_GET_SPAN_ID = ctxGetSpanId;
        CONTEXT_IS_VALID = ctxIsValid;
        AVAILABLE = available;
    }

    private TraceContextHolder() {
    }

    /**
     * Returns {@code true} when an OpenTelemetry trace API was found on the
     * classpath. Even when {@code true}, individual lookups may still return
     * {@code null} if no span is currently active.
     */
    public static boolean isOtelAvailable() {
        return AVAILABLE;
    }

    /**
     * Returns the active W3C trace id (32-char lowercase hex) for the current
     * thread, or {@code null} when OTel is absent, no span is active, or the
     * span context is invalid. Never throws.
     */
    public static String currentTraceId() {
        Object ctx = currentSpanContext();
        if (ctx == null) {
            return null;
        }
        try {
            Object id = CONTEXT_GET_TRACE_ID.invoke(ctx);
            return id instanceof String s && !s.isBlank() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns the active W3C span id (16-char lowercase hex) for the current
     * thread, or {@code null} when unavailable. Never throws.
     */
    public static String currentSpanId() {
        Object ctx = currentSpanContext();
        if (ctx == null) {
            return null;
        }
        try {
            Object id = CONTEXT_GET_SPAN_ID.invoke(ctx);
            return id instanceof String s && !s.isBlank() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Resolves and validates the current SpanContext, or {@code null}. */
    private static Object currentSpanContext() {
        if (!AVAILABLE) {
            return null;
        }
        try {
            Object span = SPAN_CURRENT.invoke(null);
            if (span == null) {
                return null;
            }
            Object ctx = SPAN_GET_CONTEXT.invoke(span);
            if (ctx == null) {
                return null;
            }
            Object valid = CONTEXT_IS_VALID.invoke(ctx);
            if (valid instanceof Boolean b && !b) {
                return null;
            }
            return ctx;
        } catch (Throwable t) {
            return null;
        }
    }
}
