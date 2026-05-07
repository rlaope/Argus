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

    private AsProfOptions(Builder b) {
        this.interval     = b.interval;
        this.jstackdepth  = b.jstackdepth;
        this.cstack       = b.cstack;
        this.perThread    = b.perThread;
        this.allUser      = b.allUser;
        this.allKernel    = b.allKernel;
        this.allocBytes   = b.allocBytes;
        this.live         = b.live;
        this.include      = Collections.unmodifiableList(new ArrayList<>(b.include));
        this.exclude      = Collections.unmodifiableList(new ArrayList<>(b.exclude));
        this.outputFormat = b.outputFormat;
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

        public Builder interval(String v)     { this.interval = v;    return this; }
        public Builder jstackdepth(int v)     { this.jstackdepth = v; return this; }
        public Builder cstack(String v)       { this.cstack = v;      return this; }
        public Builder perThread(boolean v)   { this.perThread = v;   return this; }
        public Builder allUser(boolean v)     { this.allUser = v;     return this; }
        public Builder allKernel(boolean v)   { this.allKernel = v;   return this; }
        public Builder allocBytes(String v)   { this.allocBytes = v;  return this; }
        public Builder live(boolean v)        { this.live = v;        return this; }
        public Builder addInclude(String v)   { this.include.add(v);  return this; }
        public Builder addExclude(String v)   { this.exclude.add(v);  return this; }
        public Builder outputFormat(String v) { this.outputFormat = v; return this; }

        public AsProfOptions build() {
            return new AsProfOptions(this);
        }
    }
}
