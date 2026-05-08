package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZgcSoftMaxBreachRuleTest {

    private static final long GB = 1024L * 1024 * 1024;

    // --- fires when committed > softMax ---

    @Test
    void zgcCommittedExceedsSoftMax_warningFired() {
        // softMax=4G, committed=5G — should fire WARNING
        JvmSnapshot s = snapshot("ZGC", 5 * GB, List.of("-XX:SoftMaxHeapSize=" + (4 * GB)));
        List<Finding> findings = new ZgcSoftMaxBreachRule().evaluate(s);
        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals(Severity.WARNING, f.severity());
        assertEquals("GC", f.category());
        assertTrue(f.title().contains("SoftMaxHeapSize"), "Title should mention SoftMaxHeapSize");
        assertTrue(f.detail().contains("soft max"), "Detail should describe the breach");
        assertFalse(f.recommendations().isEmpty(), "Should include a recommendation");
    }

    // --- no finding when committed <= softMax ---

    @Test
    void zgcCommittedBelowSoftMax_noFinding() {
        // softMax=4G, committed=3G — no breach
        JvmSnapshot s = snapshot("ZGC", 3 * GB, List.of("-XX:SoftMaxHeapSize=" + (4 * GB)));
        List<Finding> findings = new ZgcSoftMaxBreachRule().evaluate(s);
        assertTrue(findings.isEmpty(), "No finding expected when committed < softMax");
    }

    @Test
    void zgcCommittedEqualsSoftMax_noFinding() {
        // committed == softMax — not a breach
        JvmSnapshot s = snapshot("ZGC", 4 * GB, List.of("-XX:SoftMaxHeapSize=" + (4 * GB)));
        List<Finding> findings = new ZgcSoftMaxBreachRule().evaluate(s);
        assertTrue(findings.isEmpty(), "No finding when committed == softMax");
    }

    // --- rule is ZGC-scoped ---

    @Test
    void g1WithSoftMaxFlag_noFinding() {
        // G1 with same committed/softMax setup — rule should not fire for non-ZGC
        JvmSnapshot s = snapshot("G1", 5 * GB, List.of("-XX:SoftMaxHeapSize=" + (4 * GB)));
        List<Finding> findings = new ZgcSoftMaxBreachRule().evaluate(s);
        assertTrue(findings.isEmpty(), "Rule must be skipped for non-ZGC collectors");
    }

    // --- no finding when SoftMaxHeapSize not set ---

    @Test
    void zgcNoSoftMaxFlag_noFinding() {
        JvmSnapshot s = snapshot("ZGC", 5 * GB, List.of());
        List<Finding> findings = new ZgcSoftMaxBreachRule().evaluate(s);
        assertTrue(findings.isEmpty(), "No finding when SoftMaxHeapSize is not configured");
    }

    // --- generational ZGC also triggers ---

    @Test
    void generationalZgcCommittedExceedsSoftMax_warningFired() {
        JvmSnapshot s = snapshot("ZGC (Generational)", 5 * GB,
                List.of("-XX:SoftMaxHeapSize=" + (4 * GB)));
        List<Finding> findings = new ZgcSoftMaxBreachRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    // --- parseSoftMaxHeapSize helper ---

    @Test
    void parseSoftMaxHeapSize_rawBytes() {
        long expected = 4L * GB;
        long parsed = ZgcSoftMaxBreachRule.parseSoftMaxHeapSize(
                List.of("-XX:SoftMaxHeapSize=" + expected));
        assertEquals(expected, parsed);
    }

    @Test
    void parseSoftMaxHeapSize_absent() {
        assertEquals(0L, ZgcSoftMaxBreachRule.parseSoftMaxHeapSize(List.of()));
    }

    // --- helpers ---

    private static JvmSnapshot snapshot(String gcAlgorithm, long heapCommitted,
                                         List<String> vmFlags) {
        long heapMax = 8 * GB;
        return new JvmSnapshot(
                heapCommitted / 2, heapMax, heapCommitted, 0,
                Map.of(), List.of(),
                0L, 0L, 120_000L,
                0.1, 0.2, 4,
                10, 5, 10,
                Map.of("RUNNABLE", 10), 0,
                List.of(),
                1000, 1000, 0,
                0,
                "OpenJDK 64-Bit Server VM", "21.0.2", gcAlgorithm,
                vmFlags,
                0L
        );
    }
}
