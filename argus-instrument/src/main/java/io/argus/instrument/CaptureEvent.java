package io.argus.instrument;

import java.util.List;

/**
 * One instrumentation observation, serialised to a single line of JSON and
 * streamed to the CLI over the event socket. The JSON shape is the contract the
 * CLI parses (the CLI has no compile dependency on this module), so field names
 * here must stay stable.
 *
 * <p>Event kinds:
 * <ul>
 *   <li>{@code ENTER} — method entry; carries {@code args}, {@code depth}.</li>
 *   <li>{@code EXIT} — normal return; carries {@code ret}, {@code wallNanos}.</li>
 *   <li>{@code THROW} — exceptional return; carries {@code ex}, {@code wallNanos}.</li>
 *   <li>{@code MONITOR} — a windowed aggregate; carries {@code count}/{@code success}/
 *       {@code failure}/{@code avgMs}/{@code maxMs}.</li>
 *   <li>{@code NOTICE} — a control/lifecycle message (e.g. "detached: hit limit");
 *       carries {@code message}.</li>
 * </ul>
 *
 * <p>Unused fields are omitted from the JSON. Records are unavailable at the
 * module's Java 11 baseline, so this is a plain immutable class with per-kind
 * static factories.
 */
public final class CaptureEvent {

    public enum Kind {
        ENTER, EXIT, THROW, MONITOR, NOTICE
    }

    private final Kind kind;
    private final long epochMillis;
    private final String threadName;
    private final String className;
    private final String methodName;
    private final int depth;
    private final List<String> args;   // ENTER
    private final String ret;          // EXIT
    private final String exception;    // THROW
    private final long wallNanos;      // EXIT / THROW
    // MONITOR aggregate fields:
    private final long count;
    private final long success;
    private final long failure;
    private final double avgMs;
    private final double maxMs;
    // NOTICE:
    private final String message;

    private CaptureEvent(Kind kind, long epochMillis, String threadName, String className,
                         String methodName, int depth, List<String> args, String ret,
                         String exception, long wallNanos, long count, long success,
                         long failure, double avgMs, double maxMs, String message) {
        this.kind = kind;
        this.epochMillis = epochMillis;
        this.threadName = threadName;
        this.className = className;
        this.methodName = methodName;
        this.depth = depth;
        this.args = args;
        this.ret = ret;
        this.exception = exception;
        this.wallNanos = wallNanos;
        this.count = count;
        this.success = success;
        this.failure = failure;
        this.avgMs = avgMs;
        this.maxMs = maxMs;
        this.message = message;
    }

    public static CaptureEvent enter(long ts, String thread, String clazz, String method,
                                     int depth, List<String> args) {
        return new CaptureEvent(Kind.ENTER, ts, thread, clazz, method, depth, args,
                null, null, 0L, 0L, 0L, 0L, 0.0, 0.0, null);
    }

    public static CaptureEvent exit(long ts, String thread, String clazz, String method,
                                    int depth, String ret, long wallNanos) {
        return new CaptureEvent(Kind.EXIT, ts, thread, clazz, method, depth, null,
                ret, null, wallNanos, 0L, 0L, 0L, 0.0, 0.0, null);
    }

    public static CaptureEvent thrown(long ts, String thread, String clazz, String method,
                                      int depth, String exception, long wallNanos) {
        return new CaptureEvent(Kind.THROW, ts, thread, clazz, method, depth, null,
                null, exception, wallNanos, 0L, 0L, 0L, 0.0, 0.0, null);
    }

    public static CaptureEvent monitor(long ts, String clazz, String method, long count,
                                       long success, long failure, double avgMs, double maxMs) {
        return new CaptureEvent(Kind.MONITOR, ts, null, clazz, method, 0, null, null, null,
                0L, count, success, failure, avgMs, maxMs, null);
    }

    public static CaptureEvent notice(long ts, String message) {
        return new CaptureEvent(Kind.NOTICE, ts, null, null, null, 0, null, null, null,
                0L, 0L, 0L, 0L, 0.0, 0.0, message);
    }

    public Kind kind() {
        return kind;
    }

    /** Serialises to a single line of JSON (no embedded newlines). */
    public String toJson() {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        sb.append("\"type\":\"").append(kind.name()).append('"');
        sb.append(",\"ts\":").append(epochMillis);
        appendString(sb, "thread", threadName);
        appendString(sb, "clazz", className);
        appendString(sb, "method", methodName);
        if (kind == Kind.ENTER || kind == Kind.EXIT || kind == Kind.THROW) {
            sb.append(",\"depth\":").append(depth);
        }
        if (args != null) {
            sb.append(",\"args\":[");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escape(args.get(i))).append('"');
            }
            sb.append(']');
        }
        appendString(sb, "ret", ret);
        appendString(sb, "ex", exception);
        if (kind == Kind.EXIT || kind == Kind.THROW) {
            sb.append(",\"wallNanos\":").append(wallNanos);
        }
        if (kind == Kind.MONITOR) {
            sb.append(",\"count\":").append(count);
            sb.append(",\"success\":").append(success);
            sb.append(",\"failure\":").append(failure);
            sb.append(",\"avgMs\":").append(num(avgMs));
            sb.append(",\"maxMs\":").append(num(maxMs));
        }
        appendString(sb, "message", message);
        sb.append('}');
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(',').append('"').append(key).append("\":\"").append(escape(value)).append('"');
        }
    }

    private static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "0";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    /** Minimal JSON string escaping; also strips control chars that would break line framing. */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
