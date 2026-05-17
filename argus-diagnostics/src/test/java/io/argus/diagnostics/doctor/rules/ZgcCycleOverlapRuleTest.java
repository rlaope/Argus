package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZgcCycleOverlapRuleTest {

    // --- fires CRITICAL when cycles overlap ---

    @Test
    void zgcCyclesOverlapping_criticalFired() {
        // 100 cycles, each taking 800ms avg, interval = uptimeMs/100
        // uptimeMs = 100_000ms → intervalMs = 1000ms
        // avgDuration 800ms > 1000ms * 0.8 = 800ms — exactly at boundary (>) means NOT triggered.
        // Use avgDuration 810ms to be strictly above threshold.
        long uptimeMs = 100_000L;
        long cycleCount = 100L;
        long avgDurationMs = 810L;
        long totalTimeMs = avgDurationMs * cycleCount;  // 81_000ms

        JvmSnapshot s = snapshot("ZGC", uptimeMs,
                List.of(new JvmSnapshot.GcInfo("ZGC Cycles", cycleCount, totalTimeMs)));

        List<Finding> findings = new ZgcCycleOverlapRule().evaluate(s);
        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals(Severity.CRITICAL, f.severity());
        assertEquals("GC", f.category());
        assertTrue(f.title().contains("overlap"), "Title should mention overlap risk");
        assertTrue(f.detail().contains("cycles"), "Detail should describe cycle count");
        assertFalse(f.recommendations().isEmpty(), "Should include recommendation");
    }

    // --- no finding when cycles are healthy ---

    @Test
    void zgcHealthyCycles_noFinding() {
        // avgDuration 200ms, interval 1000ms — well within headroom
        long uptimeMs = 100_000L;
        long cycleCount = 100L;
        long avgDurationMs = 200L;
        long totalTimeMs = avgDurationMs * cycleCount;

        JvmSnapshot s = snapshot("ZGC", uptimeMs,
                List.of(new JvmSnapshot.GcInfo("ZGC Cycles", cycleCount, totalTimeMs)));

        List<Finding> findings = new ZgcCycleOverlapRule().evaluate(s);
        assertTrue(findings.isEmpty(), "No finding expected for healthy ZGC cycles");
    }

    // --- no finding when cycle count too low for stable signal ---

    @Test
    void zgcTooFewCycles_noFinding() {
        // Only 3 cycles — below the MIN_CYCLE_COUNT=5 threshold
        long uptimeMs = 10_000L;
        long cycleCount = 3L;
        long totalTimeMs = 9_000L;  // would be overlapping ratio, but ignored due to low count

        JvmSnapshot s = snapshot("ZGC", uptimeMs,
                List.of(new JvmSnapshot.GcInfo("ZGC Cycles", cycleCount, totalTimeMs)));

        List<Finding> findings = new ZgcCycleOverlapRule().evaluate(s);
        assertTrue(findings.isEmpty(), "No finding when cycle count < MIN_CYCLE_COUNT");
    }

    // --- rule is ZGC-scoped ---

    @Test
    void g1Collector_noFinding() {
        long uptimeMs = 100_000L;
        long cycleCount = 100L;
        long totalTimeMs = 81_000L;

        // GcInfo name does NOT contain "ZGC" — rule skips non-ZGC collectors
        JvmSnapshot s = snapshot("G1", uptimeMs,
                List.of(new JvmSnapshot.GcInfo("G1 Young Generation", cycleCount, totalTimeMs)));

        List<Finding> findings = new ZgcCycleOverlapRule().evaluate(s);
        assertTrue(findings.isEmpty(), "Rule must be skipped for non-ZGC algorithm");
    }

    @Test
    void g1CollectorWithZgcAlgo_gcInfoNameNotZgc_noFinding() {
        // gcAlgorithm says ZGC but the GcInfo name is not "ZGC" — only ZGC-named infos trigger
        long uptimeMs = 100_000L;
        long cycleCount = 100L;
        long totalTimeMs = 81_000L;

        JvmSnapshot s = snapshot("ZGC", uptimeMs,
                List.of(new JvmSnapshot.GcInfo("Young Generation", cycleCount, totalTimeMs)));

        List<Finding> findings = new ZgcCycleOverlapRule().evaluate(s);
        assertTrue(findings.isEmpty(),
                "GcInfo without 'ZGC' in name should not trigger ZgcCycleOverlapRule");
    }

    // --- generational ZGC also triggers ---

    @Test
    void generationalZgcOverlapping_criticalFired() {
        long uptimeMs = 100_000L;
        long cycleCount = 100L;
        long totalTimeMs = 81_000L;  // avgDuration=810ms > interval(1000ms)*0.8

        JvmSnapshot s = snapshot("ZGC (Generational)", uptimeMs,
                List.of(new JvmSnapshot.GcInfo("ZGC Major Cycles", cycleCount, totalTimeMs)));

        List<Finding> findings = new ZgcCycleOverlapRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
    }

    // --- helpers ---

    private static JvmSnapshot snapshot(String gcAlgorithm, long uptimeMs,
                                         List<JvmSnapshot.GcInfo> gcInfos) {
        long totalCount = gcInfos.stream().mapToLong(JvmSnapshot.GcInfo::count).sum();
        long totalTime = gcInfos.stream().mapToLong(JvmSnapshot.GcInfo::timeMs).sum();
        long heapMax = 8L * 1024 * 1024 * 1024;
        return new JvmSnapshot(
                heapMax / 2, heapMax, heapMax / 2, 0,
                Map.of(), gcInfos,
                totalCount, totalTime, uptimeMs,
                0.1, 0.2, 4,
                10, 5, 10,
                Map.of("RUNNABLE", 10), 0,
                List.of(),
                1000, 1000, 0,
                0,
                "OpenJDK 64-Bit Server VM", "21.0.2", gcAlgorithm,
                List.of(),
                0L
        );
    }
}
