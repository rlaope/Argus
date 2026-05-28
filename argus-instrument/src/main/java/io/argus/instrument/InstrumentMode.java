package io.argus.instrument;

/**
 * The instrumentation flavour requested by the operator.
 *
 * <ul>
 *   <li>{@link #WATCH} — capture arguments, return value, thrown exception and
 *       wall-clock cost for each invocation of the target method.</li>
 *   <li>{@link #TRACE} — like watch but tracks the nested call depth so the CLI
 *       can render an instrumented call tree.</li>
 *   <li>{@link #MONITOR} — aggregate invocation count / success / failure /
 *       average latency over fixed windows instead of per-call events.</li>
 * </ul>
 */
public enum InstrumentMode {
    WATCH,
    TRACE,
    MONITOR;

    /**
     * Parses a mode token case-insensitively.
     *
     * @throws IllegalArgumentException if {@code token} is null or not a known mode
     */
    public static InstrumentMode fromString(String token) {
        if (token == null) {
            throw new IllegalArgumentException("instrument mode is required");
        }
        switch (token.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "watch":
                return WATCH;
            case "trace":
                return TRACE;
            case "monitor":
                return MONITOR;
            default:
                throw new IllegalArgumentException("unknown instrument mode: " + token);
        }
    }

    /** Lower-case wire token used in the agent options string. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
