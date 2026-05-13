package io.argus.cli.provider.jdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable value object carrying advanced asprof flag overrides.
 *
 * <p>All fields default to "no override" (null / false / empty list).
 * Use {@link #builder()} to construct instances; {@link #defaults()} returns a
 * fully-unset instance that preserves legacy asprof behaviour.
 */
public final class AsProfOptions {

    /** Raw sampling interval token, e.g. {@code "1ms"}, {@code "100us"} — or null. */
    public final String interval;

    /** Max Java stack depth (1–2048), or null for the asprof default. */
    public final Integer jstackdepth;

    /**
     * C-stack unwinding mode: {@code fp}, {@code dwarf}, {@code lbr}, {@code vm}, {@code no},
     * or null to leave the asprof default intact.
     */
    public final String cstack;

    /** Pass {@code -t} to asprof for per-thread output. */
    public final boolean perThread;

    /** Pass {@code --alluser} to asprof. Mutually exclusive with {@link #allKernel}. */
    public final boolean allUser;

    /** Pass {@code --allkernel} to asprof. Mutually exclusive with {@link #allUser}. */
    public final boolean allKernel;

    /** Raw allocation threshold token, e.g. {@code "512k"} — or null. Only for alloc events. */
    public final String allocBytes;

    /** Pass {@code --live} to asprof (only meaningful for alloc events). */
    public final boolean live;

    /** Frame include patterns (each maps to {@code -I PATTERN}). Never null; may be empty. */
    public final List<String> include;

    /** Frame exclude patterns (each maps to {@code -X PATTERN}). Never null; may be empty. */
    public final List<String> exclude;

    /**
     * Output format override. Values: {@code flamegraph}, {@code collapsed}, {@code jfr},
     * {@code tree}, {@code text}, or null to keep the caller's default.
     */
    public final String outputFormat;

    /** Pass {@code --reverse} to asprof — generate a stack-reversed (icicle) flame graph. */
    public final boolean reverse;

    /** Minimum frame width percentage to render, e.g. {@code "0.5"} — or null. Maps to {@code --minwidth pct}. */
    public final String minwidth;

    /** Pass {@code --sched} to asprof — group threads by scheduling policy (Linux only). */
    public final boolean sched;

    /** Clock source for JFR timestamps: {@code tsc} or {@code monotonic} — or null. Maps to {@code --clock source}. */
    public final String clock;

    /** Alternative signal number for cpu/wall profiling — or null. Maps to {@code --signal num}. */
    public final String signal;

    /** Process sampling interval, e.g. {@code "60s"} — or null. Maps to {@code --proc interval}. */
    public final String procInterval;

    /** Pass {@code --nofree} to asprof — exclude free() events from native allocation profiling. */
    public final boolean nofree;

    /** Pass {@code --ttsp} to asprof for time-to-safepoint profiling. */
    public final boolean ttsp;

    /** Function name passed to {@code --begin}, or null. Profiling starts when the function is executed. */
    public final String beginFunction;

    /** Function name passed to {@code --end}, or null. Profiling stops when the function is executed. */
    public final String endFunction;

    private AsProfOptions(Builder b) {
        this.interval      = b.interval;
        this.jstackdepth   = b.jstackdepth;
        this.cstack        = b.cstack;
        this.perThread     = b.perThread;
        this.allUser       = b.allUser;
        this.allKernel     = b.allKernel;
        this.allocBytes    = b.allocBytes;
        this.live          = b.live;
        this.include       = Collections.unmodifiableList(new ArrayList<>(b.include));
        this.exclude       = Collections.unmodifiableList(new ArrayList<>(b.exclude));
        this.outputFormat  = b.outputFormat;
        this.reverse       = b.reverse;
        this.minwidth      = b.minwidth;
        this.sched         = b.sched;
        this.clock         = b.clock;
        this.signal        = b.signal;
        this.procInterval  = b.procInterval;
        this.nofree        = b.nofree;
        this.ttsp          = b.ttsp;
        this.beginFunction = b.beginFunction;
        this.endFunction   = b.endFunction;
    }

    /** Returns an instance with all fields at their "no override" defaults. */
    public static AsProfOptions defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String interval;
        private Integer jstackdepth;
        private String cstack;
        private boolean perThread;
        private boolean allUser;
        private boolean allKernel;
        private String allocBytes;
        private boolean live;
        private final List<String> include = new ArrayList<>();
        private final List<String> exclude = new ArrayList<>();
        private String outputFormat;
        private boolean reverse;
        private String minwidth;
        private boolean sched;
        private String clock;
        private String signal;
        private String procInterval;
        private boolean nofree;
        private boolean ttsp;
        private String beginFunction;
        private String endFunction;

        public Builder interval(String v)      { this.interval = v;      return this; }
        public Builder jstackdepth(int v)      { this.jstackdepth = v;   return this; }
        public Builder cstack(String v)        { this.cstack = v;        return this; }
        public Builder perThread(boolean v)    { this.perThread = v;     return this; }
        public Builder allUser(boolean v)      { this.allUser = v;       return this; }
        public Builder allKernel(boolean v)    { this.allKernel = v;     return this; }
        public Builder allocBytes(String v)    { this.allocBytes = v;    return this; }
        public Builder live(boolean v)         { this.live = v;          return this; }
        public Builder addInclude(String v)    { this.include.add(v);    return this; }
        public Builder addExclude(String v)    { this.exclude.add(v);    return this; }
        public Builder outputFormat(String v)  { this.outputFormat = v;  return this; }
        public Builder reverse(boolean v)      { this.reverse = v;       return this; }
        public Builder minwidth(String v)      { this.minwidth = v;      return this; }
        public Builder sched(boolean v)        { this.sched = v;         return this; }
        public Builder clock(String v)         { this.clock = v;         return this; }
        public Builder signal(String v)        { this.signal = v;        return this; }
        public Builder procInterval(String v)  { this.procInterval = v;  return this; }
        public Builder nofree(boolean v)       { this.nofree = v;        return this; }
        public Builder ttsp(boolean v)         { this.ttsp = v;          return this; }
        public Builder beginFunction(String v) { this.beginFunction = v; return this; }
        public Builder endFunction(String v)   { this.endFunction = v;   return this; }

        public AsProfOptions build() {
            return new AsProfOptions(this);
        }
    }
}
