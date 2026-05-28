package io.argus.instrument;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Owns the time-based auto-detach guarantee: the agent always lets go on its own
 * even if the operator walks away and the CLI never sends STOP.
 *
 * <p>Scope is deliberately narrow — this class only handles the
 * {@link CaptureCaps#timeoutMs()} one-shot timer. The complementary
 * <em>hit-count</em> limit lives in {@link InstrumentEngine} because it must
 * observe per-invocation ENTER counts; when that limit trips the engine calls
 * {@code stop("hit limit")} directly. Keeping the two triggers in their natural
 * owners (timer here, counter there) avoids a tangled shared-state design while
 * both ultimately funnel into the single idempotent {@link InstrumentEngine#stop}.
 *
 * <p>The timer runs on a daemon scheduler so a pending detach can never keep the
 * target JVM alive at shutdown.
 */
public final class Detacher {

    private final InstrumentEngine engine;
    private final long timeoutMs;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> timeoutTask;

    public Detacher(InstrumentEngine engine, long timeoutMs) {
        this.engine = engine;
        this.timeoutMs = timeoutMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory());
    }

    private static ThreadFactory daemonFactory() {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "argus-instrument-detacher");
                t.setDaemon(true);
                return t;
            }
        };
    }

    /** Schedules the one-shot timeout detach. No-op if {@code timeoutMs <= 0}. */
    public void arm() {
        if (timeoutMs <= 0) {
            return;
        }
        timeoutTask = scheduler.schedule(
                () -> engine.stop("timeout"), timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels the pending timeout (if any) and shuts the scheduler down. Idempotent.
     *
     * <p>Uses a graceful {@code shutdown()} rather than {@code shutdownNow()}: on
     * the timeout path {@code cancel()} is invoked from inside
     * {@link InstrumentEngine#stop} which is itself running on this scheduler's
     * thread. {@code shutdownNow()} would set that thread's interrupt flag and the
     * subsequent transformer reset / socket writes would run interrupted, risking a
     * {@code ClosedByInterruptException} mid-reset. {@code shutdown()} lets the
     * in-flight {@code stop()} finish cleanly; the already-fired one-shot task needs
     * no interruption.
     */
    public void cancel() {
        ScheduledFuture<?> t = timeoutTask;
        if (t != null) {
            t.cancel(false);
        }
        scheduler.shutdown();
    }
}
