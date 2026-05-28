package io.argus.instrument;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The orchestrator that wires ByteBuddy retransformation, the advice bridge, the
 * capture caps and the event sink into one self-terminating instrumentation
 * session.
 *
 * <p>Lifecycle: {@link #start()} installs the transformer and arms the auto-detach
 * timers; the advice fires {@link #onEnter}/{@link #onExit}/{@link #onThrow}
 * through {@link AdviceBridge}; {@link #stop(String)} resets the transformer to
 * restore the original bytecode (the headline safety property — zero residual)
 * and closes the channel. {@code stop} is idempotent and is reachable from four
 * directions: CLI STOP, timeout, hit-limit, and refusal.
 *
 * <h2>Why a reflective bridge bind ({@code bindBridge})</h2>
 * To make the inlined advice resolvable from <em>any</em> target classloader we
 * inject {@link AdviceBridge} into the bootstrap classloader. The subtle hazard:
 * after injection there are <b>two</b> {@code AdviceBridge} classes — the copy
 * loaded by the agent's own classloader (the one this engine sees via a direct
 * {@code AdviceBridge.bind(...)} call) and the freshly-injected bootstrap copy
 * that the inlined advice actually links against. Binding the agent-loader copy
 * would leave the bootstrap copy's static {@code engine} null forever, so the
 * advice would no-op. We therefore resolve the bootstrap copy with
 * {@code Class.forName("...AdviceBridge", true, null)} and invoke {@code bind}
 * reflectively on <em>that</em> Class.
 *
 * <p>Bootstrap injection is <b>mandatory</b>: if it fails we refuse the attach
 * rather than fall back to system-classpath visibility. A target class in an
 * isolated classloader (app server, Spring Boot fat-jar, OSGi) that cannot
 * resolve {@code AdviceBridge} would throw a linkage error from the inlined
 * advice call site <em>on the application thread</em> — outside the bridge's own
 * {@code try/catch}, since the failure is in linking, not in the hook body. That
 * is precisely the "never destabilise the target" invariant, so we fail closed.
 */
public final class InstrumentEngine {

    private static final Logger LOG = System.getLogger("io.argus.instrument");
    private static final String BRIDGE_FQCN = "io.argus.instrument.AdviceBridge";
    private static final long SNAPSHOT_INTERVAL_MS = 1000L;

    private final AgentOptions options;
    private final Instrumentation inst;
    private final EventSink sink;
    private final CaptureCaps caps;
    private final InstrumentMode mode;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicLong hitCount = new AtomicLong(0);

    /** Per-thread call depth for TRACE mode; index 0 is the live counter. */
    private final ThreadLocal<int[]> depthCounter = ThreadLocal.withInitial(() -> new int[]{0});

    private final MonitorAggregator aggregator = new MonitorAggregator();

    /** Bootstrap copy of AdviceBridge bound reflectively, if injection succeeded. */
    private volatile Class<?> boundBridgeClass;

    private volatile ResettableClassFileTransformer transformer;
    private volatile Detacher detacher;
    private volatile ScheduledExecutorService monitorScheduler;
    private volatile ScheduledFuture<?> monitorTask;
    /** Off-app-thread executor for detach work that may not run inside advice. */
    private volatile ExecutorService detachExecutor;

    public InstrumentEngine(AgentOptions options, Instrumentation inst, EventSink sink) {
        this.options = options;
        this.inst = inst;
        this.sink = sink;
        this.caps = options.caps();
        this.mode = options.mode();
    }

    /**
     * Installs instrumentation. Independently re-checks the safety gates (the CLI
     * also checks, but the agent must never trust the caller) before touching any
     * class.
     *
     * @throws InstrumentationRefusedException if disabled or the target is forbidden
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // Defense in depth: refuse independently of the CLI.
        SafetyGuard.assertEnabled(options.enabled());
        SafetyGuard.assertInstrumentable(options.spec().classBinaryName());

        bindBridge();

        this.detachExecutor = Executors.newSingleThreadExecutor(daemonFactory("argus-instrument-detach"));

        String targetType = options.spec().classBinaryName();
        ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> methodMatcher = methodMatcher();

        this.transformer = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .type(ElementMatchers.named(targetType))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
                            net.bytebuddy.dynamic.DynamicType.Builder<?> builder,
                            TypeDescription typeDescription,
                            ClassLoader classLoader,
                            net.bytebuddy.utility.JavaModule module,
                            java.security.ProtectionDomain protectionDomain) {
                        return builder.visit(Advice.to(WatchAdvice.class).on(methodMatcher));
                    }
                })
                .installOn(inst);

        retransformIfLoaded(targetType);

        if (mode == InstrumentMode.MONITOR) {
            startMonitorSnapshots();
        }

        Detacher d = new Detacher(this, caps.timeoutMs());
        this.detacher = d;
        d.arm();
    }

    /**
     * Injects {@link AdviceBridge} into the bootstrap classloader and binds the
     * bootstrap copy reflectively (see class Javadoc). Injection is mandatory: on
     * failure this throws {@link InstrumentationRefusedException} so the attach is
     * refused cleanly rather than risking an advice linkage error on an
     * application thread under an isolated classloader.
     */
    private void bindBridge() {
        try {
            File temp = Files.createTempDirectory("argus-instrument").toFile();
            temp.deleteOnExit();
            ClassInjector.UsingInstrumentation
                    .of(temp, ClassInjector.UsingInstrumentation.Target.BOOTSTRAP, inst)
                    .inject(Collections.singletonMap(
                            new TypeDescription.ForLoadedType(AdviceBridge.class),
                            ClassFileLocator.ForClassLoader.read(AdviceBridge.class)));

            // The bridge is decoupled from this engine through java.util.function
            // .Consumer (a JDK type the bootstrap loader owns), so the bootstrap
            // copy never references InstrumentEngine — it only invokes these
            // agent-loader lambdas as plain Consumers. See AdviceBridge for the
            // positional Object[] payload layouts.
            Consumer<Object[]> enter = p -> onEnter((String) p[0], (String) p[1], (Object[]) p[2]);
            Consumer<Object[]> exit = p -> onExit((String) p[0], (String) p[1], (Long) p[2], p[3]);
            Consumer<Object[]> thr = p -> onThrow((String) p[0], (String) p[1], (Long) p[2], (Throwable) p[3]);

            // Bind BOTH AdviceBridge copies, because injection creates a second
            // class identity and the inlined advice resolves whichever copy its
            // target's classloader yields:
            //   • The BOOTSTRAP copy (loader == null) — what a target under an
            //     isolated classloader (delegates to bootstrap only) resolves.
            //   • The near/agent-loader copy (referenced as AdviceBridge.class
            //     above) — what a target loaded by the same loader that defined
            //     this engine resolves, which it won't re-delegate to bootstrap.
            // Binding both is idempotent when they happen to be the same class.
            Class<?> bootstrapBridge = Class.forName(BRIDGE_FQCN, true, null);
            bootstrapBridge.getMethod("bind", Consumer.class, Consumer.class, Consumer.class)
                    .invoke(null, enter, exit, thr);
            AdviceBridge.bind(enter, exit, thr);
            this.boundBridgeClass = bootstrapBridge;
        } catch (Throwable t) {
            throw new InstrumentationRefusedException(
                    "cannot inject AdviceBridge into the bootstrap classloader; refusing to "
                            + "instrument so a target under an isolated classloader can never hit "
                            + "an advice linkage error: " + t);
        }
    }

    private void unbindBridge() {
        // Mirror bindBridge: unbind BOTH copies. Idempotent when they coincide.
        Class<?> c = this.boundBridgeClass;
        if (c != null) {
            try {
                c.getMethod("unbind").invoke(null);
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "reflective unbind failed", t);
            }
        }
        try {
            AdviceBridge.unbind();
        } catch (Throwable ignore) {
            // best effort
        }
    }

    private ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> methodMatcher() {
        ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> base =
                ElementMatchers.<net.bytebuddy.description.method.MethodDescription>isMethod()
                        .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                        .and(ElementMatchers.not(ElementMatchers.isNative()));
        MethodSpec spec = options.spec();
        if (spec.matchesAllMethods()) {
            return base;
        }
        if ("<init>".equals(spec.methodPattern())) {
            return ElementMatchers.<net.bytebuddy.description.method.MethodDescription>isConstructor()
                    .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                    .and(ElementMatchers.not(ElementMatchers.isNative()));
        }
        return base.and(ElementMatchers.named(spec.methodPattern()));
    }

    /** Retransforms the target class if it is already loaded so it gets instrumented now. */
    private void retransformIfLoaded(String targetType) {
        if (!inst.isRetransformClassesSupported()) {
            return;
        }
        List<Class<?>> matches = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            try {
                if (targetType.equals(c.getName()) && inst.isModifiableClass(c)) {
                    matches.add(c);
                }
            } catch (Throwable ignore) {
                // getName() / isModifiableClass() are defensive; skip on any oddity.
            }
        }
        for (Class<?> c : matches) {
            try {
                inst.retransformClasses(c);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "retransform of already-loaded " + targetType + " failed", t);
            }
        }
    }

    private void startMonitorSnapshots() {
        ScheduledExecutorService s =
                Executors.newSingleThreadScheduledExecutor(daemonFactory("argus-instrument-monitor"));
        this.monitorScheduler = s;
        String clazz = options.spec().classBinaryName();
        String method = options.spec().methodPattern();
        this.monitorTask = s.scheduleAtFixedRate(() -> {
            try {
                sink.emit(aggregator.snapshotAndReset(System.currentTimeMillis(), clazz, method));
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "monitor snapshot failed", t);
            }
        }, SNAPSHOT_INTERVAL_MS, SNAPSHOT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ---- Advice-bridge hooks (run on application threads) ----

    void onEnter(String clazz, String method, Object[] args) {
        long hits = hitCount.incrementAndGet();
        if (mode == InstrumentMode.MONITOR) {
            // No per-call event; the exit hook records into the aggregator.
            return;
        }
        int depth = 0;
        if (mode == InstrumentMode.TRACE) {
            depth = depthCounter.get()[0]++;
            if (depth >= caps.maxDepth()) {
                maybeDetachOnHits(hits);
                return;
            }
        }
        List<String> rendered = renderArgs(args);
        sink.emit(CaptureEvent.enter(System.currentTimeMillis(), Thread.currentThread().getName(),
                clazz, method, depth, rendered));
        maybeDetachOnHits(hits);
    }

    void onExit(String clazz, String method, long startNanos, Object returnOrNull) {
        long wall = elapsed(startNanos);
        if (mode == InstrumentMode.MONITOR) {
            aggregator.record(true, wall);
            return;
        }
        int depth = currentDepthOnExit();
        if (mode == InstrumentMode.TRACE && depth >= caps.maxDepth()) {
            return;
        }
        String ret = ValueRenderer.render(returnOrNull, caps);
        sink.emit(CaptureEvent.exit(System.currentTimeMillis(), Thread.currentThread().getName(),
                clazz, method, depth, ret, wall));
    }

    void onThrow(String clazz, String method, long startNanos, Throwable t) {
        long wall = elapsed(startNanos);
        if (mode == InstrumentMode.MONITOR) {
            aggregator.record(false, wall);
            return;
        }
        int depth = currentDepthOnExit();
        if (mode == InstrumentMode.TRACE && depth >= caps.maxDepth()) {
            return;
        }
        String ex = describeThrowable(t);
        sink.emit(CaptureEvent.thrown(System.currentTimeMillis(), Thread.currentThread().getName(),
                clazz, method, depth, ex, wall));
    }

    /** Decrements and returns the post-exit depth for TRACE; 0 otherwise. */
    private int currentDepthOnExit() {
        if (mode != InstrumentMode.TRACE) {
            return 0;
        }
        int[] d = depthCounter.get();
        if (d[0] > 0) {
            d[0]--;
        }
        return d[0];
    }

    private List<String> renderArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return Collections.emptyList();
        }
        int limit = Math.min(args.length, caps.maxArgs());
        List<String> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            out.add(ValueRenderer.render(args[i], caps));
        }
        return out;
    }

    private static long elapsed(long startNanos) {
        long now = System.nanoTime();
        long d = now - startNanos;
        return d < 0 ? 0L : d;
    }

    private static String describeThrowable(Throwable t) {
        if (t == null) {
            return "null";
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getName() : t.getClass().getName() + ": " + msg;
    }

    /** Fires hit-limit detach off the application thread (never reset from advice). */
    private void maybeDetachOnHits(long hits) {
        int max = caps.maxHits();
        if (max > 0 && hits >= max && !stopped.get()) {
            ExecutorService ex = this.detachExecutor;
            if (ex != null && !ex.isShutdown()) {
                try {
                    ex.submit(() -> stop("hit limit"));
                } catch (Throwable t) {
                    LOG.log(Level.DEBUG, "could not schedule hit-limit detach", t);
                }
            }
        }
    }

    /**
     * Detaches and restores original bytecode. Idempotent; safe to call from the
     * CLI reader thread, timer, hit-limit executor, or refusal path. Each step is
     * isolated so one failure cannot skip the others — the transformer reset in
     * particular must always be attempted.
     */
    public void stop(String reason) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        // 1) Disarm the fast path so in-flight advice becomes a no-op immediately.
        try {
            unbindBridge();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "unbind failed", t);
        }
        // 2) Cancel auto-detach timers / monitor scheduler.
        try {
            Detacher d = this.detacher;
            if (d != null) {
                d.cancel();
            }
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "detacher cancel failed", t);
        }
        try {
            ScheduledFuture<?> mt = this.monitorTask;
            if (mt != null) {
                mt.cancel(false);
            }
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "monitor task cancel failed", t);
        }
        // 3) For MONITOR, flush a final window before tearing down.
        try {
            if (mode == InstrumentMode.MONITOR) {
                sink.emit(aggregator.snapshotAndReset(System.currentTimeMillis(),
                        options.spec().classBinaryName(), options.spec().methodPattern()));
            }
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "final monitor flush failed", t);
        }
        try {
            ScheduledExecutorService ms = this.monitorScheduler;
            if (ms != null) {
                ms.shutdownNow();
            }
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "monitor scheduler shutdown failed", t);
        }
        // 4) Reset the transformer → restore ORIGINAL bytecode (zero residual).
        try {
            ResettableClassFileTransformer tr = this.transformer;
            if (tr != null) {
                tr.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "transformer reset failed; bytecode may not be restored", t);
        }
        // 5) Final notice, then close the channel.
        try {
            sink.emit(CaptureEvent.notice(System.currentTimeMillis(), "detached: " + reason));
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "final notice emit failed", t);
        }
        try {
            sink.close();
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "sink close failed", t);
        }
        // 6) Shut down the detach executor last so a hit-limit submission can complete.
        try {
            ExecutorService de = this.detachExecutor;
            if (de != null) {
                de.shutdown();
            }
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "detach executor shutdown failed", t);
        }
    }

    private static ThreadFactory daemonFactory(String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }
}
