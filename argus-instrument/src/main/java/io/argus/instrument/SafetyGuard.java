package io.argus.instrument;

import java.util.Locale;

/**
 * The hard safety boundary for live instrumentation. Every retransformation
 * request must pass through here before any class is touched.
 *
 * <p>Two independent gates:
 * <ol>
 *   <li><b>Enable gate</b> — instrumentation is refused outright unless the
 *       operator explicitly opted in. The CLI asserts this by passing
 *       {@code enabled=true} in the agent options; a forged or absent flag
 *       means {@link #assertEnabled(boolean)} throws.</li>
 *   <li><b>Target gate</b> — JDK-internal and Argus-internal namespaces are
 *       never instrumentable. Retransforming {@code java.*} / {@code jdk.*} /
 *       {@code sun.*} / {@code com.sun.*} risks destabilising the runtime
 *       itself; instrumenting {@code io.argus.instrument.*} would recurse into
 *       the agent.</li>
 * </ol>
 *
 * <p>All methods are static and side-effect free except for throwing.
 */
public final class SafetyGuard {

    /** Namespaces that must never be retransformed. Matched as dotted prefixes. */
    private static final String[] FORBIDDEN_PREFIXES = {
            "java.",
            "javax.crypto.",
            "jdk.",
            "sun.",
            "com.sun.",
            "io.argus.instrument.",
            "net.bytebuddy.",
    };

    private SafetyGuard() {
    }

    /**
     * Refuses instrumentation unless the operator explicitly enabled it.
     *
     * @param enabled the opt-in flag carried in the agent options
     * @throws InstrumentationRefusedException when {@code enabled} is false
     */
    public static void assertEnabled(boolean enabled) {
        if (!enabled) {
            throw new InstrumentationRefusedException(
                    "live instrumentation is disabled; pass --enable-instrument "
                            + "(or set argus.instrument.enabled=true) to opt in");
        }
    }

    /**
     * Returns {@code true} if {@code classBinaryName} (dotted form, e.g.
     * {@code com.foo.Bar}) lies in a namespace that must never be instrumented.
     *
     * <p>Null/blank names are treated as forbidden (fail closed).
     */
    public static boolean isForbidden(String classBinaryName) {
        if (classBinaryName == null) {
            return true;
        }
        String name = classBinaryName.trim();
        if (name.isEmpty()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refuses instrumentation of a forbidden (JDK-internal / agent-internal)
     * target.
     *
     * @param classBinaryName the dotted class name the operator wants to touch
     * @throws InstrumentationRefusedException when the target is forbidden
     */
    public static void assertInstrumentable(String classBinaryName) {
        if (isForbidden(classBinaryName)) {
            throw new InstrumentationRefusedException(
                    "refusing to instrument JDK-internal or agent-internal class: "
                            + classBinaryName);
        }
    }
}
