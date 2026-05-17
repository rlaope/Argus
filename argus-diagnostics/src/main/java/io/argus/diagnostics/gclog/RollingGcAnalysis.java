package io.argus.diagnostics.gclog;

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

        double firstTs = events.get(0).timestampSec();
        double lastTs  = events.get(events.size() - 1).timestampSec();
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

    public static final class Snapshot {
        private final long p50PauseMs;
        private final long p95PauseMs;
        private final long p99PauseMs;
        private final long maxPauseMs;
        private final long avgPauseMs;
        private final long totalPauseMs;
        private final double throughputPercent;
        private final int fullGcCount;
        private final long peakHeapKB;
        private final double eventsPerSec;
        private final double secsSinceLastFullGc;
        private final int totalEventsEver;

        public Snapshot(long p50PauseMs, long p95PauseMs, long p99PauseMs,
                        long maxPauseMs, long avgPauseMs, long totalPauseMs,
                        double throughputPercent, int fullGcCount, long peakHeapKB,
                        double eventsPerSec, double secsSinceLastFullGc,
                        int totalEventsEver) {
            this.p50PauseMs = p50PauseMs;
            this.p95PauseMs = p95PauseMs;
            this.p99PauseMs = p99PauseMs;
            this.maxPauseMs = maxPauseMs;
            this.avgPauseMs = avgPauseMs;
            this.totalPauseMs = totalPauseMs;
            this.throughputPercent = throughputPercent;
            this.fullGcCount = fullGcCount;
            this.peakHeapKB = peakHeapKB;
            this.eventsPerSec = eventsPerSec;
            this.secsSinceLastFullGc = secsSinceLastFullGc;
            this.totalEventsEver = totalEventsEver;
        }

        public long p50PauseMs() { return p50PauseMs; }
        public long p95PauseMs() { return p95PauseMs; }
        public long p99PauseMs() { return p99PauseMs; }
        public long maxPauseMs() { return maxPauseMs; }
        public long avgPauseMs() { return avgPauseMs; }
        public long totalPauseMs() { return totalPauseMs; }
        public double throughputPercent() { return throughputPercent; }
        public int fullGcCount() { return fullGcCount; }
        public long peakHeapKB() { return peakHeapKB; }
        public double eventsPerSec() { return eventsPerSec; }
        public double secsSinceLastFullGc() { return secsSinceLastFullGc; }
        public int totalEventsEver() { return totalEventsEver; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Snapshot)) return false;
            Snapshot that = (Snapshot) o;
            return p50PauseMs == that.p50PauseMs
                    && p95PauseMs == that.p95PauseMs
                    && p99PauseMs == that.p99PauseMs
                    && maxPauseMs == that.maxPauseMs
                    && avgPauseMs == that.avgPauseMs
                    && totalPauseMs == that.totalPauseMs
                    && Double.compare(that.throughputPercent, throughputPercent) == 0
                    && fullGcCount == that.fullGcCount
                    && peakHeapKB == that.peakHeapKB
                    && Double.compare(that.eventsPerSec, eventsPerSec) == 0
                    && Double.compare(that.secsSinceLastFullGc, secsSinceLastFullGc) == 0
                    && totalEventsEver == that.totalEventsEver;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(p50PauseMs, p95PauseMs, p99PauseMs, maxPauseMs,
                    avgPauseMs, totalPauseMs, throughputPercent, fullGcCount, peakHeapKB,
                    eventsPerSec, secsSinceLastFullGc, totalEventsEver);
        }

        @Override
        public String toString() {
            return "Snapshot[p50PauseMs=" + p50PauseMs + ", p95PauseMs=" + p95PauseMs
                    + ", p99PauseMs=" + p99PauseMs + ", maxPauseMs=" + maxPauseMs
                    + ", avgPauseMs=" + avgPauseMs + ", totalPauseMs=" + totalPauseMs
                    + ", throughputPercent=" + throughputPercent + ", fullGcCount=" + fullGcCount
                    + ", peakHeapKB=" + peakHeapKB + ", eventsPerSec=" + eventsPerSec
                    + ", secsSinceLastFullGc=" + secsSinceLastFullGc
                    + ", totalEventsEver=" + totalEventsEver + "]";
        }
    }
}
