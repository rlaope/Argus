package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.JvmSnapshot;
import io.argus.diagnostics.doctor.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class G1RulesTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JvmSnapshot snapshot(String gcAlgorithm,
                                        long heapMax,
                                        List<JvmSnapshot.GcInfo> collectors,
                                        List<String> flags) {
        return new JvmSnapshot(
                /* heapUsed     */ 0,
                /* heapMax      */ heapMax,
                /* heapCommitted*/ 0,
                /* nonHeapUsed  */ 0,
                /* memoryPools  */ Map.of(),
                collectors,
                /* totalGcCount */ collectors.stream().mapToLong(JvmSnapshot.GcInfo::count).sum(),
                /* totalGcTimeMs*/ collectors.stream().mapToLong(JvmSnapshot.GcInfo::timeMs).sum(),
                /* uptimeMs     */ 60_000,
                /* processCpu   */ 0, /* systemCpu */ 0, /* procs */ 4,
                /* threadCount  */ 0, /* daemon */ 0, /* peak */ 0,
                /* threadStates */ Map.of(), /* deadlocked */ 0,
                /* buffers      */ List.of(),
                /* loadedClasses*/ 0, /* totalLoadedCl */ 0, /* unloadedCl */ 0,
                /* pendingFin   */ 0,
                "HotSpot 64-Bit Server VM", "21", gcAlgorithm, flags);
    }

    // ── G1FullGcRule ────────────────────────────────────────────────────────

    @Test
    void g1FullGc_fires_when_old_generation_count_positive() {
        List<JvmSnapshot.GcInfo> gcs = List.of(
                new JvmSnapshot.GcInfo("G1 Young Generation", 200, 1500),
                new JvmSnapshot.GcInfo("G1 Old Generation", 1, 800)
        );
        JvmSnapshot s = snapshot("G1 Young Generation, G1 Old Generation",
                4L * 1024 * 1024 * 1024, gcs, List.of());

        List<Finding> findings = new G1FullGcRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
        assertTrue(findings.get(0).title().contains("Full GC"));
    }

    @Test
    void g1FullGc_silent_when_old_generation_count_zero() {
        List<JvmSnapshot.GcInfo> gcs = List.of(
                new JvmSnapshot.GcInfo("G1 Young Generation", 200, 1500),
                new JvmSnapshot.GcInfo("G1 Old Generation", 0, 0)
        );
        JvmSnapshot s = snapshot("G1 Young Generation, G1 Old Generation",
                4L * 1024 * 1024 * 1024, gcs, List.of());

        assertTrue(new G1FullGcRule().evaluate(s).isEmpty());
    }

    @Test
    void g1FullGc_skipped_for_non_g1_collector() {
        List<JvmSnapshot.GcInfo> gcs = List.of(
                new JvmSnapshot.GcInfo("ZGC Major Cycles", 5, 50)
        );
        JvmSnapshot s = snapshot("ZGC", 4L * 1024 * 1024 * 1024, gcs, List.of());
        assertTrue(new G1FullGcRule().evaluate(s).isEmpty());
    }

    // ── G1RegionSizeRule ────────────────────────────────────────────────────

    @Test
    void regionSize_warns_when_large_heap_without_explicit_region() {
        JvmSnapshot s = snapshot("G1 Young Generation",
                64L * 1024 * 1024 * 1024,
                List.of(new JvmSnapshot.GcInfo("G1 Young Generation", 10, 100)),
                List.of() // no -XX:G1HeapRegionSize
        );
        List<Finding> findings = new G1RegionSizeRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    @Test
    void regionSize_warns_on_tiny_region_with_nontiny_heap() {
        JvmSnapshot s = snapshot("G1 Young Generation",
                8L * 1024 * 1024 * 1024,
                List.of(new JvmSnapshot.GcInfo("G1 Young Generation", 10, 100)),
                List.of("-XX:G1HeapRegionSize=1m")
        );
        List<Finding> findings = new G1RegionSizeRule().evaluate(s);
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("Tiny"));
    }

    @Test
    void regionSize_silent_on_well_configured() {
        JvmSnapshot s = snapshot("G1 Young Generation",
                8L * 1024 * 1024 * 1024,
                List.of(new JvmSnapshot.GcInfo("G1 Young Generation", 10, 100)),
                List.of("-XX:G1HeapRegionSize=16m")
        );
        assertTrue(new G1RegionSizeRule().evaluate(s).isEmpty());
    }

    @Test
    void regionSize_skipped_for_non_g1() {
        JvmSnapshot s = snapshot("ZGC", 64L * 1024 * 1024 * 1024,
                List.of(), List.of());
        assertTrue(new G1RegionSizeRule().evaluate(s).isEmpty());
    }

    // ── G1IhopConfigurationRule ─────────────────────────────────────────────

    @Test
    void ihop_warns_when_adaptive_off_and_manual_high() {
        JvmSnapshot s = snapshot("G1 Young Generation",
                4L * 1024 * 1024 * 1024,
                List.of(new JvmSnapshot.GcInfo("G1 Young Generation", 10, 100)),
                List.of("-XX:-G1UseAdaptiveIHOP", "-XX:InitiatingHeapOccupancyPercent=80")
        );
        List<Finding> findings = new G1IhopConfigurationRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    @Test
    void ihop_silent_when_adaptive_on() {
        JvmSnapshot s = snapshot("G1 Young Generation",
                4L * 1024 * 1024 * 1024,
                List.of(new JvmSnapshot.GcInfo("G1 Young Generation", 10, 100)),
                List.of("-XX:+G1UseAdaptiveIHOP", "-XX:InitiatingHeapOccupancyPercent=80")
        );
        assertTrue(new G1IhopConfigurationRule().evaluate(s).isEmpty());
    }

    @Test
    void ihop_silent_when_no_explicit_flag() {
        // default: adaptive on, IHOP 45 — both healthy
        JvmSnapshot s = snapshot("G1 Young Generation",
                4L * 1024 * 1024 * 1024,
                List.of(new JvmSnapshot.GcInfo("G1 Young Generation", 10, 100)),
                List.of()
        );
        assertTrue(new G1IhopConfigurationRule().evaluate(s).isEmpty());
    }

    @Test
    void ihop_silent_when_adaptive_off_but_threshold_safe() {
        JvmSnapshot s = snapshot("G1 Young Generation",
                4L * 1024 * 1024 * 1024,
                List.of(new JvmSnapshot.GcInfo("G1 Young Generation", 10, 100)),
                List.of("-XX:-G1UseAdaptiveIHOP", "-XX:InitiatingHeapOccupancyPercent=45")
        );
        assertTrue(new G1IhopConfigurationRule().evaluate(s).isEmpty());
    }

    @Test
    void ihop_skipped_for_non_g1() {
        JvmSnapshot s = snapshot("ZGC", 4L * 1024 * 1024 * 1024,
                List.of(),
                List.of("-XX:-G1UseAdaptiveIHOP", "-XX:InitiatingHeapOccupancyPercent=90")
        );
        assertTrue(new G1IhopConfigurationRule().evaluate(s).isEmpty());
    }
}
