package io.argus.diagnostics.zgc;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated ZGC health snapshot built from a 30-second JFR capture.
 *
 * <p>Populated by {@link ZgcJfrCollector} from the following JFR events:
 * {@code jdk.ZAllocationStall}, {@code jdk.ZYoungGarbageCollection},
 * {@code jdk.ZOldGarbageCollection}, {@code jdk.ZGarbageCollection},
 * {@code jdk.GarbageCollection}, {@code jdk.GCHeapSummary},
 * {@code jdk.ObjectAllocationInNewTLAB}, {@code jdk.ObjectAllocationOutsideTLAB}.
 *
 * <p>Verdict logic ({@link #compute()}):
 * <ul>
 *   <li>{@link Verdict#UNHEALTHY} when allocation stalls are present OR cycles overlap.</li>
 *   <li>{@link Verdict#WARNING}   when soft-max heap is breached OR Pause Mark End &gt; 1.0 ms.</li>
 *   <li>{@link Verdict#HEALTHY}   otherwise.</li>
 * </ul>
 */
public final class ZgcDiagnosis {

    public enum Verdict { HEALTHY, WARNING, UNHEALTHY }

    /** Allocation stall captured from {@code jdk.ZAllocationStall}. */
    public static final class Stall {
        private final String thread;
        private final double durationMs;

        public Stall(String thread, double durationMs) {
            this.thread = thread;
            this.durationMs = durationMs;
        }

        public String thread() { return thread; }
        public double durationMs() { return durationMs; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Stall)) return false;
            Stall that = (Stall) o;
            return Double.compare(that.durationMs, durationMs) == 0
                    && java.util.Objects.equals(thread, that.thread);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(thread, durationMs);
        }

        @Override
        public String toString() {
            return "Stall[thread=" + thread + ", durationMs=" + durationMs + "]";
        }
    }

    /**
     * Top allocation call site correlated with stalls, derived from
     * {@code jdk.ObjectAllocationInNewTLAB} / {@code jdk.ObjectAllocationOutsideTLAB} events.
     */
    public static final class AllocHotspot {
        private final String frame;
        private final long count;
        private final double pct;

        public AllocHotspot(String frame, long count, double pct) {
            this.frame = frame;
            this.count = count;
            this.pct = pct;
        }

        public String frame() { return frame; }
        public long count() { return count; }
        public double pct() { return pct; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AllocHotspot)) return false;
            AllocHotspot that = (AllocHotspot) o;
            return count == that.count
                    && Double.compare(that.pct, pct) == 0
                    && java.util.Objects.equals(frame, that.frame);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(frame, count, pct);
        }

        @Override
        public String toString() {
            return "AllocHotspot[frame=" + frame + ", count=" + count + ", pct=" + pct + "]";
        }
    }

    // ── Process / GC identity ───────────────────────────────────────────────
    public boolean usingZgc;
    public boolean generational;
    public String  jvmVersion = "";

    // ── Heap (bytes) ────────────────────────────────────────────────────────
    public long heapCommittedBytes;
    public long maxHeapBytes;
    /** -1 when -XX:SoftMaxHeapSize is unknown or disabled (0). */
    public long softMaxHeapBytes = -1;

    // ── Cycle counters ──────────────────────────────────────────────────────
    public int    minorCycles;
    public int    majorCycles;
    public double avgCycleIntervalSec;
    public double avgCycleDurationSec;

    // ── STW phase averages (ms) ─────────────────────────────────────────────
    public double pauseMarkStartMs;
    public double pauseMarkEndMs;
    public double pauseRelocateStartMs;

    // ── Allocation stalls ───────────────────────────────────────────────────
    public final List<Stall> stalls = new ArrayList<>();

    /**
     * Top allocation hotspots from the same JFR capture, populated only when stalls
     * are present and allocation events were recorded. Empty otherwise.
     */
    public final List<AllocHotspot> stallAllocHotspots = new ArrayList<>();

    /** Total number of allocation events seen in the JFR capture (for the header n= label). */
    public long totalAllocEvents;

    // ── Derived booleans ────────────────────────────────────────────────────
    /** True when any GCHeapSummary sample shows committed > softMaxHeapBytes. */
    public boolean softMaxBreached;
    /** True when consecutive Z*GarbageCollection events overlap in time. */
    public boolean cycleOverlap;

    /** Computes the overall verdict from the populated fields. */
    public Verdict compute() {
        if (!stalls.isEmpty() || cycleOverlap) {
            return Verdict.UNHEALTHY;
        }
        if (softMaxBreached || pauseMarkEndMs > 1.0) {
            return Verdict.WARNING;
        }
        return Verdict.HEALTHY;
    }
}
