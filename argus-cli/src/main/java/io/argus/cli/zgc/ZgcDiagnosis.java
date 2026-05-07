package io.argus.cli.zgc;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated ZGC health snapshot built from a 30-second JFR capture.
 *
 * <p>Populated by {@link ZgcJfrCollector} from the following JFR events:
 * {@code jdk.ZAllocationStall}, {@code jdk.ZYoungGarbageCollection},
 * {@code jdk.ZOldGarbageCollection}, {@code jdk.ZGarbageCollection},
 * {@code jdk.GarbageCollection}, {@code jdk.GCHeapSummary}.
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
    public record Stall(String thread, double durationMs) {}

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
