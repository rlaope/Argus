package io.argus.diagnostics.gclog;

/**
 * G1-specific aggregate stats extracted from a GC log file.
 *
 * <p>Populated by {@link GcLogParser} alongside the generic event list, using
 * the G1 pattern set in {@link GcLogPatterns}. Surfaces signals that the
 * generic {@link GcEvent} stream loses (evacuation failure, humongous-region
 * deltas, mixed vs. prepare-mixed pause classification, concurrent-cycle
 * markers).
 *
 * <p>Empty for non-G1 logs ({@link #present()} returns {@code false}). Callers
 * that branch on G1-specific recommendations should gate on {@code present()}.
 */
public final class G1Stats {

    private final int evacuationFailures;
    private final int humongousAllocationCycles;
    private final int humongousRegionsPeak;
    private final int mixedPauses;
    private final int prepareMixedPauses;
    private final int concurrentCycleMarkers;
    private final boolean fullGcSeen;

    public G1Stats(int evacuationFailures,
                   int humongousAllocationCycles,
                   int humongousRegionsPeak,
                   int mixedPauses,
                   int prepareMixedPauses,
                   int concurrentCycleMarkers,
                   boolean fullGcSeen) {
        this.evacuationFailures        = evacuationFailures;
        this.humongousAllocationCycles = humongousAllocationCycles;
        this.humongousRegionsPeak      = humongousRegionsPeak;
        this.mixedPauses               = mixedPauses;
        this.prepareMixedPauses        = prepareMixedPauses;
        this.concurrentCycleMarkers    = concurrentCycleMarkers;
        this.fullGcSeen                = fullGcSeen;
    }

    public static G1Stats empty() {
        return new G1Stats(0, 0, 0, 0, 0, 0, false);
    }

    public int  evacuationFailures()        { return evacuationFailures; }
    public int  humongousAllocationCycles() { return humongousAllocationCycles; }
    public int  humongousRegionsPeak()      { return humongousRegionsPeak; }
    public int  mixedPauses()               { return mixedPauses; }
    public int  prepareMixedPauses()        { return prepareMixedPauses; }
    public int  concurrentCycleMarkers()    { return concurrentCycleMarkers; }
    public boolean fullGcSeen()             { return fullGcSeen; }

    /** True when at least one G1-specific signal was extracted from the log. */
    public boolean present() {
        return evacuationFailures > 0 || humongousAllocationCycles > 0
                || humongousRegionsPeak > 0 || mixedPauses > 0
                || prepareMixedPauses > 0 || concurrentCycleMarkers > 0
                || fullGcSeen;
    }

    /**
     * Heuristic: a concurrent cycle was observed but no Mixed pause followed
     * within the log. Caller must verify the log window is long enough; a
     * sub-second log fragment will trip this falsely.
     */
    public boolean mixedStarvationSuspected() {
        return concurrentCycleMarkers > 0 && mixedPauses == 0;
    }

    @Override
    public String toString() {
        return "G1Stats[evacFailures=" + evacuationFailures
                + ", humongousCycles=" + humongousAllocationCycles
                + ", humongousPeak=" + humongousRegionsPeak
                + ", mixed=" + mixedPauses
                + ", prepareMixed=" + prepareMixedPauses
                + ", concurrent=" + concurrentCycleMarkers
                + ", fullGc=" + fullGcSeen + "]";
    }
}
