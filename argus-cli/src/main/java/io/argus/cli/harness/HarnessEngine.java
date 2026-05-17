package io.argus.cli.harness;

import io.argus.diagnostics.doctor.DoctorEngine;
import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.JvmSnapshot;
import io.argus.diagnostics.doctor.JvmSnapshotCollector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The harness orchestrator. Once {@link #runUntilDone()} is called it samples
 * the target JVM at the configured interval, accumulates samples in a
 * {@link HarnessSession}, runs the existing snapshot-based
 * {@link DoctorEngine} plus all configured {@link TrendRule}s on every tick,
 * and feeds findings to a live callback.
 *
 * <p>The engine deduplicates findings by category+title across the whole
 * session (a leak rule that fires every tick should appear once in the final
 * report, not 600 times) but still surfaces them to the live callback every
 * tick so the TUI can show "fired this tick".
 *
 * <p>Single-use: build a new engine for every session.
 */
public final class HarnessEngine {

    private final long pid;
    private final HarnessProfile profile;
    private final Duration interval;
    private final Duration duration;
    private final List<TrendRule> trendRules;
    private final Consumer<TickEvent> tickCallback;
    private final HarnessSession session;

    private final long startTimeMs;
    private long endTimeMs;
    private final Map<String, Finding> dedupedFindings = new LinkedHashMap<>();
    private final Map<String, Integer> ruleHitCounts = new LinkedHashMap<>();
    private int tickCount;

    public static final class TickEvent {
        public final int tickNumber;
        public final TimedSnapshot timed;
        public final List<Finding> findingsThisTick;
        public final HarnessSession session;
        public TickEvent(int tickNumber, TimedSnapshot timed, List<Finding> findingsThisTick,
                         HarnessSession session) {
            this.tickNumber = tickNumber;
            this.timed = timed;
            this.findingsThisTick = findingsThisTick;
            this.session = session;
        }
    }

    public HarnessEngine(long pid, HarnessProfile profile, Duration interval, Duration duration,
                         List<TrendRule> trendRules, Consumer<TickEvent> tickCallback) {
        if (pid < 0) throw new IllegalArgumentException("pid must be >= 0");
        if (interval == null || interval.isNegative() || interval.isZero())
            throw new IllegalArgumentException("interval must be positive");
        if (duration == null || duration.isNegative() || duration.isZero())
            throw new IllegalArgumentException("duration must be positive");
        this.pid = pid;
        this.profile = profile == null ? HarnessProfile.DEEP : profile;
        this.interval = interval;
        this.duration = duration;
        this.trendRules = List.copyOf(trendRules);
        this.tickCallback = tickCallback == null ? e -> {} : tickCallback;
        long maxAgeMs = Math.max(duration.toMillis(), Duration.ofMinutes(15).toMillis());
        int maxSamples = (int) Math.min(2000L, Math.max(60L, duration.toMillis() / interval.toMillis() + 4));
        this.session = new HarnessSession(maxSamples, maxAgeMs);
        this.startTimeMs = System.currentTimeMillis();
    }

    /** Run the harness on the calling thread until {@code duration} elapses. */
    public HarnessResult runUntilDone() {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "argus-harness");
            t.setDaemon(true);
            return t;
        });
        CountDownLatch done = new CountDownLatch(1);
        long intervalMs = interval.toMillis();
        long durationMs = duration.toMillis();
        long deadline = startTimeMs + durationMs;

        ScheduledFuture<?> task = exec.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Throwable t) {
                System.err.println("[Argus harness] tick failed: " + t.getMessage());
            } finally {
                if (System.currentTimeMillis() >= deadline) {
                    done.countDown();
                }
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        try {
            done.await(durationMs + intervalMs * 2, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            task.cancel(false);
            exec.shutdownNow();
        }

        endTimeMs = System.currentTimeMillis();
        return new HarnessResult(pid, startTimeMs, endTimeMs, tickCount, profile,
                new ArrayList<>(dedupedFindings.values()), ruleHitCounts);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        JvmSnapshot snap = JvmSnapshotCollector.collect(pid);
        TimedSnapshot timed = new TimedSnapshot(now, snap);
        session.record(timed);
        tickCount++;

        List<Finding> tickFindings = new ArrayList<>();
        try {
            tickFindings.addAll(DoctorEngine.diagnose(snap));
        } catch (Throwable t) {
            // Doctor rules already isolate per-rule failures internally. A throw
            // here means a deeper bug — surface but don't kill the harness.
            System.err.println("[Argus harness] doctor rule pass failed: " + t.getMessage());
        }
        for (TrendRule r : trendRules) {
            try {
                List<Finding> out = r.evaluate(session);
                if (out != null) tickFindings.addAll(out);
            } catch (Throwable t) {
                System.err.println("[Argus harness] trend rule "
                        + r.getClass().getSimpleName() + " failed: " + t.getMessage());
            }
        }

        for (Finding f : tickFindings) {
            String key = f.category() + "|" + f.title();
            dedupedFindings.putIfAbsent(key, f);
            ruleHitCounts.merge(key, 1, Integer::sum);
        }

        try {
            tickCallback.accept(new TickEvent(tickCount, timed, tickFindings, session));
        } catch (Throwable t) {
            System.err.println("[Argus harness] tick callback failed: " + t.getMessage());
        }
    }

    public HarnessSession session() { return session; }
    public long pid() { return pid; }
    public Duration interval() { return interval; }
    public Duration duration() { return duration; }
    public HarnessProfile profile() { return profile; }
}
