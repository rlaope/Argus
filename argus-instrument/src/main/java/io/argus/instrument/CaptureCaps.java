package io.argus.instrument;

/**
 * Bounded limits that keep instrumentation cheap and self-terminating. These
 * are the quantitative half of the safety story (the qualitative half is
 * {@link SafetyGuard}): they cap how much data is captured, how long the agent
 * stays attached, and how fast events may flow.
 *
 * <p>Immutable; build via {@link #defaults()} then the {@code with*} copying
 * setters, or via {@link Builder}.
 */
public final class CaptureCaps {

    /** Detach after this many matched invocations (0 = unlimited by count). */
    private final int maxHits;
    /** Detach this many milliseconds after attach regardless of hit count. */
    private final long timeoutMs;
    /** Truncate each rendered argument/return value to this many characters. */
    private final int maxValueLen;
    /** Capture at most this many leading arguments per call. */
    private final int maxArgs;
    /** Maximum call-tree depth recorded in TRACE mode. */
    private final int maxDepth;
    /** Soft ceiling on events emitted per second (backpressure → drop). */
    private final int maxEventsPerSecond;

    private CaptureCaps(int maxHits, long timeoutMs, int maxValueLen,
                        int maxArgs, int maxDepth, int maxEventsPerSecond) {
        this.maxHits = maxHits;
        this.timeoutMs = timeoutMs;
        this.maxValueLen = maxValueLen;
        this.maxArgs = maxArgs;
        this.maxDepth = maxDepth;
        this.maxEventsPerSecond = maxEventsPerSecond;
    }

    /** Conservative defaults: 100 hits, 60s, 256-char values, 16 args, depth 20, 500 ev/s. */
    public static CaptureCaps defaults() {
        return new CaptureCaps(100, 60_000L, 256, 16, 20, 500);
    }

    public int maxHits() {
        return maxHits;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public int maxValueLen() {
        return maxValueLen;
    }

    public int maxArgs() {
        return maxArgs;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int maxEventsPerSecond() {
        return maxEventsPerSecond;
    }

    public CaptureCaps withMaxHits(int v) {
        return new CaptureCaps(sanitizeCount(v), timeoutMs, maxValueLen, maxArgs, maxDepth, maxEventsPerSecond);
    }

    public CaptureCaps withTimeoutMs(long v) {
        long t = v <= 0 ? 60_000L : Math.min(v, 3_600_000L);
        return new CaptureCaps(maxHits, t, maxValueLen, maxArgs, maxDepth, maxEventsPerSecond);
    }

    public CaptureCaps withMaxValueLen(int v) {
        int len = v <= 0 ? 256 : Math.min(v, 8_192);
        return new CaptureCaps(maxHits, timeoutMs, len, maxArgs, maxDepth, maxEventsPerSecond);
    }

    public CaptureCaps withMaxArgs(int v) {
        int n = v < 0 ? 0 : Math.min(v, 255);
        return new CaptureCaps(maxHits, timeoutMs, maxValueLen, n, maxDepth, maxEventsPerSecond);
    }

    public CaptureCaps withMaxDepth(int v) {
        int d = v <= 0 ? 1 : Math.min(v, 256);
        return new CaptureCaps(maxHits, timeoutMs, maxValueLen, maxArgs, d, maxEventsPerSecond);
    }

    public CaptureCaps withMaxEventsPerSecond(int v) {
        int e = v <= 0 ? 0 : Math.min(v, 100_000);
        return new CaptureCaps(maxHits, timeoutMs, maxValueLen, maxArgs, maxDepth, e);
    }

    /** Counts are clamped to {@code [0, 1_000_000]}; 0 means "no count limit". */
    private static int sanitizeCount(int v) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, 1_000_000);
    }

    /** Mutable builder for tests and the options decoder. */
    public static final class Builder {
        private CaptureCaps caps = defaults();

        public Builder maxHits(int v) {
            caps = caps.withMaxHits(v);
            return this;
        }

        public Builder timeoutMs(long v) {
            caps = caps.withTimeoutMs(v);
            return this;
        }

        public Builder maxValueLen(int v) {
            caps = caps.withMaxValueLen(v);
            return this;
        }

        public Builder maxArgs(int v) {
            caps = caps.withMaxArgs(v);
            return this;
        }

        public Builder maxDepth(int v) {
            caps = caps.withMaxDepth(v);
            return this;
        }

        public Builder maxEventsPerSecond(int v) {
            caps = caps.withMaxEventsPerSecond(v);
            return this;
        }

        public CaptureCaps build() {
            return caps;
        }
    }
}
