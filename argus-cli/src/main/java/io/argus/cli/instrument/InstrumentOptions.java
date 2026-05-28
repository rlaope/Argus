package io.argus.cli.instrument;

/**
 * Parsed knobs for an {@code argus instrument} session.
 *
 * <p>Java 11 source level — no records — so this is a plain immutable holder.
 * Defaults match the documented CLI contract.
 */
public final class InstrumentOptions {

    public static final int DEFAULT_MAX_HITS = 100;
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_MAX_VALUE_LEN = 256;
    public static final int DEFAULT_MAX_ARGS = 16;
    public static final int DEFAULT_MAX_DEPTH = 20;

    private final int maxHits;
    private final long timeoutMs;
    private final int maxValueLen;
    private final int maxArgs;
    private final int maxDepth;
    private final String format;

    public InstrumentOptions(int maxHits, long timeoutMs, int maxValueLen,
                             int maxArgs, int maxDepth, String format) {
        this.maxHits = maxHits;
        this.timeoutMs = timeoutMs;
        this.maxValueLen = maxValueLen;
        this.maxArgs = maxArgs;
        this.maxDepth = maxDepth;
        this.format = format;
    }

    public int maxHits() { return maxHits; }
    public long timeoutMs() { return timeoutMs; }
    public int maxValueLen() { return maxValueLen; }
    public int maxArgs() { return maxArgs; }
    public int maxDepth() { return maxDepth; }
    public String format() { return format; }
}
