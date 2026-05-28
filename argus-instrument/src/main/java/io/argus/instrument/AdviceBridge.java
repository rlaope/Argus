package io.argus.instrument;

import java.util.function.Consumer;

/**
 * The static landing pad that inlined ByteBuddy advice calls into.
 *
 * <p>ByteBuddy {@link net.bytebuddy.asm.Advice} bodies are <em>inlined</em> into
 * the target method's bytecode, so the only code they may legally call is
 * {@code public static} methods on a class that is resolvable from the target's
 * own classloader. AdviceBridge is exactly that class: injected into the
 * bootstrap classloader (see {@link InstrumentEngine}) so it is visible to every
 * target.
 *
 * <h2>Why this class references only JDK types</h2>
 * AdviceBridge is loaded by the <b>bootstrap</b> classloader, which cannot see
 * agent-loader classes such as {@link InstrumentEngine}. If any field or method
 * signature here referenced {@code InstrumentEngine}, the bootstrap copy would
 * fail to link with {@code NoClassDefFoundError}. We therefore decouple the
 * dispatch entirely through {@link java.util.function.Consumer} — a genuine JDK
 * type with a single classloader-agnostic identity. {@link InstrumentEngine}
 * registers three lambdas via {@link #bind}; each lambda is an agent-loader
 * object but is held and invoked here purely as a {@code Consumer}, so the
 * bootstrap copy never needs to <em>see</em> the engine class, only call it.
 *
 * <p>Payload layouts passed to the sinks (positional {@code Object[]}):
 * <ul>
 *   <li>enter: {@code [String clazz, String method, Object[] args]}</li>
 *   <li>exit:  {@code [String clazz, String method, Long startNanos, Object ret]}</li>
 *   <li>throw: {@code [String clazz, String method, Long startNanos, Throwable t]}</li>
 * </ul>
 * The small per-call array allocation is bounded by the hit/rate caps and only
 * occurs while a session is active.
 *
 * <p>Why {@link #active()} and the sinks are {@code volatile}: once detach
 * happens the advice must become a branch-predicted no-op immediately and across
 * threads, before the transformer reset lands. Why every hook body is wrapped in
 * {@code catch (Throwable)}: these methods run on the application's own threads
 * inside the instrumented method, so an escaping exception — even an
 * {@link Error} — would surface as a spurious failure of the user's code, the
 * cardinal sin of live instrumentation. We never let one out.
 */
public final class AdviceBridge {

    private static volatile Consumer<Object[]> enterSink;
    private static volatile Consumer<Object[]> exitSink;
    private static volatile Consumer<Object[]> throwSink;
    private static volatile boolean active;

    private AdviceBridge() {
    }

    /** Registers the dispatch lambdas and arms the fast-path flag. */
    public static void bind(Consumer<Object[]> enter, Consumer<Object[]> exit, Consumer<Object[]> thr) {
        enterSink = enter;
        exitSink = exit;
        throwSink = thr;
        active = enter != null;
    }

    /** Disarms the fast-path flag and drops the dispatch references. */
    public static void unbind() {
        active = false;
        enterSink = null;
        exitSink = null;
        throwSink = null;
    }

    /** Cheap volatile flag read so detached advice is a near-free no-op. */
    public static boolean active() {
        return active;
    }

    /** Monotonic start-time source the advice captures on entry. */
    public static long nanoTime() {
        return System.nanoTime();
    }

    /** Method-entry hook. Never throws into the application thread. */
    public static void onEnter(String clazz, String method, Object[] args) {
        try {
            if (!active) {
                return;
            }
            Consumer<Object[]> s = enterSink;
            if (s != null) {
                s.accept(new Object[]{clazz, method, args});
            }
        } catch (Throwable ignore) {
            // Instrumentation must never destabilise the target.
        }
    }

    /** Normal-return hook. Never throws into the application thread. */
    public static void onExit(String clazz, String method, long startNanos, Object returnOrNull) {
        try {
            if (!active) {
                return;
            }
            Consumer<Object[]> s = exitSink;
            if (s != null) {
                s.accept(new Object[]{clazz, method, startNanos, returnOrNull});
            }
        } catch (Throwable ignore) {
            // Instrumentation must never destabilise the target.
        }
    }

    /** Exceptional-return hook. Never throws into the application thread. */
    public static void onThrow(String clazz, String method, long startNanos, Throwable t) {
        try {
            if (!active) {
                return;
            }
            Consumer<Object[]> s = throwSink;
            if (s != null) {
                s.accept(new Object[]{clazz, method, startNanos, t});
            }
        } catch (Throwable ignore) {
            // Instrumentation must never destabilise the target.
        }
    }
}
