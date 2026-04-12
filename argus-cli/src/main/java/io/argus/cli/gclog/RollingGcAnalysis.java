package io.argus.cli.gclog;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Maintains a rolling window of the last N GcEvents and computes live metrics.
 * Exposes the same key statistics as GcLogAnalysis but over the rolling window.
 * Also tracks events/sec rate and time-since-last-full-GC.
 */
public final class RollingGcAnalysis {

    private final int maxEvents;
    private final Deque<GcEvent> window;

    // Event rate tracking
    private int totalEventsEver = 0;
    private final Instant startTime = Instant.now();

    // Last full GC tracking
    private Instant lastFullGcTime = null;

    public RollingGcAnalysis(int maxEvents) {
        this.maxEvents = maxEvents;
        this.window = new ArrayDeque<>(maxEvents + 1);
    }

    public RollingGcAnalysis() {
        this(1000);
    }

    /**
     * Adds new events to the rolling window, evicting oldest when capacity exceeded.
     */
    public synchronized void addEvents(List<GcEvent> newEvents) {
        for (GcEvent e : newEvents) {
            window.addLast(e);
            totalEventsEver++;
            if (e.isFullGc()) {
                lastFullGcTime = Instant.now();
            }
            if (window.size() > maxEvents) {
                window.pollFirst();
            }
        }
    }

    /** Total events ever seen (not just in window). */
    public synchronized int totalEventsEver() { return totalEventsEver; }

    /** Events currently in rolling window. */
    public synchronized int windowSize() { return window.size(); }

    /** Events per second since analysis started. */
    public synchronized double eventsPerSec() {
        double elapsedSec = (Instant.now().toEpochMilli() - startTime.toEpochMilli()) / 1000.0;
        return elapsedSec > 0 ? totalEventsEver / elapsedSec : 0;
    }

    /** Seconds since the last Full GC, or -1 if none observed. */
    public synchronized double secsSinceLastFullGc() {
        if (lastFullGcTime == null) return -1;
        return (Instant.now().toEpochMilli() - lastFullGcTime.toEpochMilli()) / 1000.0;
    }

    /** Snapshot of current computed metrics over the rolling window. */
    public synchronized Snapshot snapshot() {
        List<GcEvent> events = new ArrayList<>(window);
        if (events.isEmpty()) {
            return new Snapshot(0, 0, 0, 0, 0, 0, 100.0, 0, 0,
                    eventsPerSec(), secsSinceLastFullGc(), totalEventsEver);
        }

        List<GcEvent> pauses = new ArrayList<>();
        int fullGcCount = 0;
        for (GcEvent e : events) {
            if (!e.isConcurrent()) {
                pauses.add(e);
                if (e.isFullGc()) fullGcCount++;
            }
        }

        if (pauses.isEmpty()) {
            return new Snapshot(0, 0, 0, 0, 0, 0, 100.0, fullGcCount, 0,
                    eventsPerSec(), secsSinceLastFullGc(), totalEventsEver);
        }

        double[] sorted = pauses.stream().mapToDouble(GcEvent::pauseMs).sorted().toArray();
        long totalPauseMs = (long) Arrays.stream(sorted).sum();

        double firstTs = events.getFirst().timestampSec();
        double lastTs  = events.getLast().timestampSec();
        double durationSec = Math.max(lastTs - firstTs, 0.001);
        double throughput = Math.max(0, Math.min(100,
                (1.0 - totalPauseMs / (durationSec * 1000)) * 100));

        long peakHeapKB = pauses.stream().mapToLong(GcEvent::heapBeforeKB).max().orElse(0);

        return new Snapshot(
                (long) percentile(sorted, 50),
                (long) percentile(sorted, 95),
                (long) percentile(sorted, 99),
                (long) sorted[sorted.length - 1],
                (long) (totalPauseMs / pauses.size()),
                totalPauseMs,
                throughput,
                fullGcCount,
                peakHeapKB,
                eventsPerSec(),
                secsSinceLastFullGc(),
                totalEventsEver
        );
    }

    private static double percentile(double[] sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    public record Snapshot(
            long p50PauseMs,
            long p95PauseMs,
            long p99PauseMs,
            long maxPauseMs,
            long avgPauseMs,
            long totalPauseMs,
            double throughputPercent,
            int fullGcCount,
            long peakHeapKB,
            double eventsPerSec,
            double secsSinceLastFullGc,
            int totalEventsEver
    ) {}
}
