package io.argus.instrument;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * The ByteBuddy {@link Advice} template inlined into every instrumented method.
 *
 * <p><b>One advice class for all three modes.</b> The spec permits a separate
 * lighter {@code MonitorAdvice}, but the entry/exit shape is identical across
 * WATCH, TRACE and MONITOR — only the engine's <em>handling</em> differs (per-call
 * events vs. windowed aggregation). Routing all modes through the same advice and
 * letting {@link InstrumentEngine} branch on {@link AgentOptions#mode()} keeps the
 * inlined-bytecode surface minimal (one template to audit) and avoids re-running
 * the whole AgentBuilder install for a near-identical second class. MONITOR pays
 * only the cost of the bridge call plus an aggregator update; arg rendering is
 * skipped inside the engine, not here, so the inlined footprint stays small.
 *
 * <p>This class is intentionally dependency-light: its method bodies reference
 * only {@link AdviceBridge} (bootstrap-injected, hence resolvable from any target
 * classloader) and ByteBuddy annotation/assigner types that exist at advice
 * <em>compile</em> time and are erased into the target at <em>inline</em> time.
 * It is never instantiated and never explicitly loaded by agent logic.
 */
public final class WatchAdvice {

    private WatchAdvice() {
    }

    /**
     * Inlined at method entry. Captures the start time as the {@code @Advice.Enter}
     * value so the exit advice can compute wall-clock cost without a field.
     */
    @Advice.OnMethodEnter
    public static long enter(@Advice.Origin("#t") String declaringType,
                             @Advice.Origin("#m") String methodName,
                             @Advice.AllArguments Object[] args) {
        AdviceBridge.onEnter(declaringType, methodName, args);
        return AdviceBridge.nanoTime();
    }

    /**
     * Inlined at every method exit (normal or exceptional). {@code onThrowable}
     * makes this run even when the method throws, so detach/reset accounting and
     * THROW events are never missed.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Origin("#t") String declaringType,
                            @Advice.Origin("#m") String methodName,
                            @Advice.Enter long startNanos,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                            @Advice.Thrown Throwable thrown) {
        if (thrown != null) {
            AdviceBridge.onThrow(declaringType, methodName, startNanos, thrown);
        } else {
            AdviceBridge.onExit(declaringType, methodName, startNanos, returned);
        }
    }
}
