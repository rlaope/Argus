package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.JvmSnapshot;
import io.argus.diagnostics.doctor.Severity;
import io.argus.diagnostics.gclog.G1Stats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the three JFR/log-aware G1 doctor rules backed by {@link G1Stats}
 * read from the GC log via {@code JvmSnapshotCollector.extractG1Stats}.
 */
class G1JfrRulesTest {

    private static JvmSnapshot snapshot(String gcAlgorithm, List<String> flags, G1Stats g1) {
        return new JvmSnapshot(
                /* heapUsed     */ 0,
                /* heapMax      */ 4L * 1024 * 1024 * 1024,
                /* heapCommitted*/ 0,
                /* nonHeapUsed  */ 0,
                /* memoryPools  */ Map.of(),
                /* collectors   */ List.of(new JvmSnapshot.GcInfo("G1 Young Generation", 100, 1500)),
                /* totalGcCount */ 100,
                /* totalGcTimeMs*/ 1500,
                /* uptimeMs     */ 60_000,
                /* processCpu   */ 0, /* systemCpu */ 0, /* procs */ 4,
                /* threadCount  */ 0, /* daemon */ 0, /* peak */ 0,
                /* threadStates */ Map.of(), /* deadlocked */ 0,
                /* buffers      */ List.of(),
                /* loadedClasses*/ 0, /* totalLoadedCl */ 0, /* unloadedCl */ 0,
                /* pendingFin   */ 0,
                "HotSpot 64-Bit Server VM", "21", gcAlgorithm, flags,
                /* maxRecentPauseMs */ 0,
                /* codeCacheUsedKb  */ 0, /* codeCacheSizeKb */ 0,
                /* nmt              */ Map.of(),
                g1);
    }

    // ── G1EvacuationFailureRule ──────────────────────────────────────────────

    @Test
    void evacuationFailure_fires_on_any_failure() {
        G1Stats s = new G1Stats(/*evac*/ 2, 0, 0, 0, 0, 0, false);
        List<Finding> f = new G1EvacuationFailureRule().evaluate(snapshot("G1", List.of(), s));
        assertEquals(1, f.size());
        assertEquals(Severity.CRITICAL, f.get(0).severity());
        assertTrue(f.get(0).title().contains("evacuation"));
    }

    @Test
    void evacuationFailure_silent_when_no_failure() {
        G1Stats s = new G1Stats(0, 5, 8, 4, 1, 1, false);
        assertTrue(new G1EvacuationFailureRule().evaluate(snapshot("G1", List.of(), s)).isEmpty());
    }

    @Test
    void evacuationFailure_skipped_for_non_g1() {
        G1Stats s = new G1Stats(2, 0, 0, 0, 0, 0, false);
        assertTrue(new G1EvacuationFailureRule().evaluate(snapshot("ZGC", List.of(), s)).isEmpty());
    }

    // ── G1MixedStarvationRule ────────────────────────────────────────────────

    @Test
    void mixedStarvation_fires_when_concurrent_without_mixed() {
        // 3 concurrent cycles, 0 mixed pauses → starvation suspected.
        G1Stats s = new G1Stats(0, 0, 0, /*mixed*/ 0, 0, /*concurrent*/ 3, false);
        List<Finding> f = new G1MixedStarvationRule().evaluate(snapshot("G1", List.of(), s));
        assertEquals(1, f.size());
        assertEquals(Severity.WARNING, f.get(0).severity());
        assertTrue(f.get(0).title().contains("starvation"));
    }

    @Test
    void mixedStarvation_silent_when_mixed_pauses_seen() {
        G1Stats s = new G1Stats(0, 0, 0, /*mixed*/ 4, 0, /*concurrent*/ 3, false);
        assertTrue(new G1MixedStarvationRule().evaluate(snapshot("G1", List.of(), s)).isEmpty());
    }

    @Test
    void mixedStarvation_silent_when_concurrent_cycle_count_too_low() {
        // Only 1 concurrent cycle — not enough data for confident starvation call.
        G1Stats s = new G1Stats(0, 0, 0, 0, 0, /*concurrent*/ 1, false);
        assertTrue(new G1MixedStarvationRule().evaluate(snapshot("G1", List.of(), s)).isEmpty());
    }

    @Test
    void mixedStarvation_skipped_for_non_g1() {
        G1Stats s = new G1Stats(0, 0, 0, 0, 0, 5, false);
        assertTrue(new G1MixedStarvationRule().evaluate(snapshot("ZGC", List.of(), s)).isEmpty());
    }

    // ── G1HumongousPressureRule ──────────────────────────────────────────────

    @Test
    void humongous_fires_on_high_cycle_count() {
        G1Stats s = new G1Stats(0, /*humCycles*/ 5, 0, 0, 0, 0, false);
        List<Finding> f = new G1HumongousPressureRule().evaluate(snapshot("G1", List.of(), s));
        assertEquals(1, f.size());
        assertEquals(Severity.WARNING, f.get(0).severity());
    }

    @Test
    void humongous_fires_on_high_peak_with_default_region_size() {
        G1Stats s = new G1Stats(0, 0, /*humPeak*/ 20, 0, 0, 0, false);
        List<Finding> f = new G1HumongousPressureRule().evaluate(snapshot("G1", List.of(), s));
        assertEquals(1, f.size());
    }

    @Test
    void humongous_silent_when_high_peak_with_explicit_region_size() {
        // Operator already tuned region size — don't double-warn.
        G1Stats s = new G1Stats(0, 0, 20, 0, 0, 0, false);
        assertTrue(new G1HumongousPressureRule()
                .evaluate(snapshot("G1", List.of("-XX:G1HeapRegionSize=16m"), s))
                .isEmpty());
    }

    @Test
    void humongous_silent_on_low_pressure() {
        G1Stats s = new G1Stats(0, 1, 2, 0, 0, 0, false);
        assertTrue(new G1HumongousPressureRule().evaluate(snapshot("G1", List.of(), s)).isEmpty());
    }

    @Test
    void humongous_skipped_for_non_g1() {
        G1Stats s = new G1Stats(0, 10, 30, 0, 0, 0, false);
        assertTrue(new G1HumongousPressureRule().evaluate(snapshot("ZGC", List.of(), s)).isEmpty());
    }
}
