package io.argus.cli.provider.jdk;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Agent-side continuous (loop) capture mode for async-profiler.
 *
 * <p>Runs async-profiler on a fixed cadence, parsing each collapsed-output cycle
 * into a {@code stack -> sampleCount} map and handing it (with the capture
 * timestamp) to a {@link Cycle} callback. The callback is the seam the W1 profile
 * store hooks into to persist continuous per-pod profiles.
 *
 * <h2>Default-off</h2>
 * Construction never starts the loop. {@link #start()} is a no-op unless
 * {@code continuousEnabled} was passed {@code true} to the constructor — so the
 * roadmap's "continuous capture defaults OFF" requirement holds even if a caller
 * wires {@link #start()} unconditionally.
 *
 * <p>Each cycle profiles for {@code captureSeconds} and the loop fires every
 * {@code cadenceSeconds}; {@code cadence >= capture} is expected so cycles do not
 * overlap. The captured event is parsed via {@link #parseCollapsedCounts}.
 */
public final class AsProfLoopCapture {

    /** Receives one parsed capture cycle. */
    @FunctionalInterface
    public interface Cycle extends BiConsumer<Map<String, Long>, Long> {
        /**
         * @param collapsedCounts {@code stack -> sampleCount} for this cycle (never null)
         * @param timestampMillis wall-clock time the cycle finished
         */
        @Override
        void accept(Map<String, Long> collapsedCounts, Long timestampMillis);
    }

    private final long pid;
    private final String eventType;
    private final int captureSeconds;
    private final long cadenceSeconds;
    private final boolean continuousEnabled;
    private final Cycle callback;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    /**
     * @param pid               target JVM pid
     * @param eventType         async-profiler event ("cpu", "alloc", "lock", …)
     * @param captureSeconds    profiling duration per cycle
     * @param cadenceSeconds    seconds between cycle starts (default cadence is 60s)
     * @param continuousEnabled gate — when {@code false}, {@link #start()} is a no-op
     * @param callback          invoked once per cycle with parsed counts + timestamp
     */
    public AsProfLoopCapture(long pid, String eventType, int captureSeconds,
                             long cadenceSeconds, boolean continuousEnabled, Cycle callback) {
        if (captureSeconds <= 0) {
            throw new IllegalArgumentException("captureSeconds must be positive");
        }
        if (cadenceSeconds <= 0) {
            throw new IllegalArgumentException("cadenceSeconds must be positive");
        }
        this.pid = pid;
        this.eventType = eventType;
        this.captureSeconds = captureSeconds;
        this.cadenceSeconds = cadenceSeconds;
        this.continuousEnabled = continuousEnabled;
        this.callback = callback;
    }

    public boolean isContinuousEnabled() {
        return continuousEnabled;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Starts the repeating capture loop on a daemon scheduler.
     *
     * <p>No-op (returns {@code false}) when {@code continuousEnabled} is false or the
     * loop is already running. Auto-start is therefore never possible without an
     * explicit opt-in.
     */
    public boolean start() {
        if (!continuousEnabled) {
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "asprof-loop-capture");
            t.setDaemon(true);
            return t;
        });
        task = scheduler.scheduleAtFixedRate(this::captureOnce, 0, cadenceSeconds, TimeUnit.SECONDS);
        return true;
    }

    /** Stops the loop and shuts down the scheduler. Idempotent. */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (task != null) {
            task.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Runs one capture cycle and dispatches the parsed counts. Swallows errors so the loop survives. */
    private void captureOnce() {
        try {
            String asProfPath = AsProfDownloader.asProfPath();
            String[] command = new String[]{
                asProfPath,
                "-d", String.valueOf(captureSeconds),
                "-o", "collapsed",
                "-e", eventType,
                String.valueOf(pid)
            };
            AsProfExecutor.Result result =
                    AsProfExecutor.execute(command, captureSeconds + 30, null);
            if (result.exitCode() != 0) {
                return;
            }
            Map<String, Long> counts = parseCollapsedCounts(result.stdout());
            if (!counts.isEmpty() && callback != null) {
                callback.accept(counts, System.currentTimeMillis());
            }
        } catch (RuntimeException ignored) {
            // never let one bad cycle kill the loop
        }
    }

    /**
     * Parses async-profiler collapsed output into a {@code stack -> sampleCount} map.
     *
     * <p>Collapsed format is one record per line: {@code frame1;frame2;…;leaf count}
     * where the count is the trailing space-separated token. Blank lines are skipped,
     * malformed lines are skipped, and duplicate stacks are summed. Semicolons inside
     * frames are preserved as part of the stack key (the split is on the last space).
     */
    public static Map<String, Long> parseCollapsedCounts(String output) {
        Map<String, Long> counts = new HashMap<>();
        if (output == null || output.isEmpty()) {
            return counts;
        }
        for (String line : output.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace < 0) {
                continue;
            }
            String stack = line.substring(0, lastSpace);
            String countStr = line.substring(lastSpace + 1).trim();
            if (stack.isEmpty() || countStr.isEmpty()) {
                continue;
            }
            long count;
            try {
                count = Long.parseLong(countStr);
            } catch (NumberFormatException e) {
                continue;
            }
            counts.merge(stack, count, Long::sum);
        }
        return counts;
    }
}
