package io.argus.diagnostics.g1;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated G1GC health snapshot built from a 30-second JFR capture.
 *
 * <p>Populated by {@link G1JfrCollector} from the following JFR events:
 * {@code jdk.G1GarbageCollection}, {@code jdk.G1HeapSummary},
 * {@code jdk.G1EvacuationYoungStatistics}, {@code jdk.G1EvacuationOldStatistics},
 * {@code jdk.G1MMU}, {@code jdk.G1AdaptiveIHOP}, {@code jdk.GarbageCollection}
 * (label-based phase samples), {@code jdk.ObjectAllocationOutsideTLAB}
 * (humongous hotspot attribution).
 *
 * <p>Verdict logic ({@link #compute()}):
 * <ul>
 *   <li>{@link Verdict#UNHEALTHY} when Full GC occurred OR evacuation failure was seen.</li>
 *   <li>{@link Verdict#WARNING}   when mixed-cycle starvation, IHOP mistiming,
 *       humongous allocation cycles, or max pause &gt; 2× target are observed.</li>
 *   <li>{@link Verdict#HEALTHY}   otherwise.</li>
 * </ul>
 */
public final class G1Diagnosis {

    public enum Verdict { HEALTHY, WARNING, UNHEALTHY }

    /**
     * Top humongous-class allocation site correlated with humongous cycles,
     * derived from {@code jdk.ObjectAllocationOutsideTLAB} events whose
     * allocation size is &ge; regionSize/2.
     */
    public static final class HumongousHotspot {
        private final String frame;
        private final long count;
        private final long maxBytes;

        public HumongousHotspot(String frame, long count, long maxBytes) {
            this.frame = frame;
            this.count = count;
            this.maxBytes = maxBytes;
        }

        public String frame() { return frame; }
        public long count() { return count; }
        public long maxBytes() { return maxBytes; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HumongousHotspot)) return false;
            HumongousHotspot that = (HumongousHotspot) o;
            return count == that.count
                    && maxBytes == that.maxBytes
                    && java.util.Objects.equals(frame, that.frame);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(frame, count, maxBytes);
        }

        @Override
        public String toString() {
            return "HumongousHotspot[frame=" + frame + ", count=" + count
                    + ", maxBytes=" + maxBytes + "]";
        }
    }

    // ── Process / GC identity ───────────────────────────────────────────────
    public boolean usingG1;
    public String  jvmVersion = "";
    /** -XX:G1HeapRegionSize in MB (0 = JVM default / unknown). */
    public int     regionSizeMb;
    /** -XX:MaxGCPauseMillis target (200 = JVM default). */
    public int     targetPauseMs;
    /** -XX:InitiatingHeapOccupancyPercent (45 = JVM default). */
    public int     ihopPercent;
    /** {@code true} when -XX:+G1UseAdaptiveIHOP (the JVM default since JDK 15). */
    public boolean adaptiveIhop;

    // ── Heap (bytes) ────────────────────────────────────────────────────────
    public long heapCommittedBytes;
    public long maxHeapBytes;

    // ── Region counts (from jdk.G1HeapSummary; last sample wins) ────────────
    public int totalRegions;
    public int edenRegions;
    public int survivorRegions;
    public int oldRegions;
    public int humongousRegions;

    // ── Cycle counters ──────────────────────────────────────────────────────
    public int    youngCycles;
    public int    mixedCycles;
    public int    concurrentCycles;
    public int    fullGcCycles;
    public double avgYoungPauseMs;
    public double avgMixedPauseMs;
    public double maxPauseMs;

    // ── Evacuation (from jdk.G1EvacuationYoung/OldStatistics) ───────────────
    public long bytesCopiedYoung;
    public long bytesCopiedOld;
    /** Count of evacuation failures (to-space exhausted) observed in the window. */
    public int  evacuationFailures;

    // ── MMU (from jdk.G1MMU) ────────────────────────────────────────────────
    public double avgMmuPercent;
    public double minMmuPercent;

    // ── IHOP (from jdk.G1AdaptiveIHOP) ──────────────────────────────────────
    public double predictedIhopPercent;
    public double actualIhopPercent;

    // ── Humongous ───────────────────────────────────────────────────────────
    /** Count of cycles whose cause field mentions "Humongous Allocation". */
    public int humongousAllocationCycles;

    /** Top humongous-class allocation call sites; populated only when humongous cycles seen. */
    public final List<HumongousHotspot> humongousHotspots = new ArrayList<>();

    /** Total number of humongous-sized allocation events seen during the capture. */
    public long totalHumongousAllocEvents;

    // ── Derived booleans ────────────────────────────────────────────────────
    /** True when any evacuation event reported failure. */
    public boolean evacuationFailureSeen;
    /** True when any Full GC pause was observed under G1. */
    public boolean fullGcSeen;
    /**
     * True when a concurrent cycle finished but no Mixed pause was observed
     * within the capture window — heuristic; only meaningful when at least
     * one concurrent cycle has completed.
     */
    public boolean mixedStarvation;
    /**
     * True when {@code |predictedIhop - actualIhop| > 15 percentage points}
     * AND at least one concurrent cycle finished during the window.
     */
    public boolean ihopMistimed;

    /** Computes the overall verdict from the populated fields. */
    public Verdict compute() {
        if (fullGcSeen || evacuationFailureSeen) {
            return Verdict.UNHEALTHY;
        }
        double pauseTarget = targetPauseMs > 0 ? targetPauseMs : 200.0;
        if (mixedStarvation || ihopMistimed
                || humongousAllocationCycles > 0
                || maxPauseMs > pauseTarget * 2.0) {
            return Verdict.WARNING;
        }
        return Verdict.HEALTHY;
    }
}
