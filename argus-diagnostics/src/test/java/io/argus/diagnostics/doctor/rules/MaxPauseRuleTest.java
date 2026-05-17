package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaxPauseRuleTest {

    private static final long MB = 1024L * 1024;

    // --- below threshold ---

    @Test
    void belowWarningThreshold_noFinding() {
        JvmSnapshot s = snapshotWithPause(200L); // 200ms < 500ms default warn
        List<Finding> findings = new MaxPauseRule().evaluate(s);
        assertTrue(findings.isEmpty(), "No finding expected below warning threshold");
    }

    @Test
    void zeroPause_noFinding() {
        JvmSnapshot s = snapshotWithPause(0L); // 0 = unknown / no GC yet
        List<Finding> findings = new MaxPauseRule().evaluate(s);
        assertTrue(findings.isEmpty(), "No finding expected when pause is 0 (missing data)");
    }

    // --- warning band ---

    @Test
    void atWarningThreshold_warningFinding() {
        JvmSnapshot s = snapshotWithPause(500L); // exactly at default warn threshold
        List<Finding> findings = new MaxPauseRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
        assertEquals("GC", findings.get(0).category());
    }

    @Test
    void aboveWarningBelowCritical_warningFinding() {
        JvmSnapshot s = snapshotWithPause(1000L); // 1000ms: warn >= 500, critical < 2000
        List<Finding> findings = new MaxPauseRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
        assertTrue(findings.get(0).title().contains("1000ms"));
    }

    // --- critical band ---

    @Test
    void atCriticalThreshold_criticalFinding() {
        JvmSnapshot s = snapshotWithPause(2000L); // exactly at default critical threshold
        List<Finding> findings = new MaxPauseRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
    }

    @Test
    void wellAboveCritical_criticalFinding() {
        JvmSnapshot s = snapshotWithPause(5000L);
        List<Finding> findings = new MaxPauseRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
        assertTrue(findings.get(0).title().contains("5000ms"));
    }

    // --- custom threshold via constructor ---

    @Test
    void customWarnThreshold_belowCustom_noFinding() {
        MaxPauseRule rule = new MaxPauseRule(1000L, 4000L);
        JvmSnapshot s = snapshotWithPause(800L); // 800ms < custom warn of 1000ms
        List<Finding> findings = rule.evaluate(s);
        assertTrue(findings.isEmpty(), "800ms should not fire with custom warn=1000ms");
    }

    @Test
    void customWarnThreshold_aboveCustom_warningFinding() {
        MaxPauseRule rule = new MaxPauseRule(1000L, 4000L);
        JvmSnapshot s = snapshotWithPause(1500L); // 1500ms >= custom warn of 1000ms, < critical 4000ms
        List<Finding> findings = rule.evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    @Test
    void customCriticalThreshold_aboveCustomCritical_criticalFinding() {
        MaxPauseRule rule = new MaxPauseRule(1000L, 4000L);
        JvmSnapshot s = snapshotWithPause(4500L); // 4500ms >= custom critical of 4000ms
        List<Finding> findings = rule.evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
    }

    // --- missing data ---

    @Test
    void negativePause_noFinding() {
        JvmSnapshot s = snapshotWithPause(-1L);
        List<Finding> findings = new MaxPauseRule().evaluate(s);
        assertTrue(findings.isEmpty(), "Negative pause (unknown) should produce no finding");
    }

    // --- finding content ---

    @Test
    void warningFinding_containsRecommendations() {
        JvmSnapshot s = snapshotWithPause(600L);
        Finding f = new MaxPauseRule().evaluate(s).get(0);
        assertFalse(f.recommendations().isEmpty(), "Finding should include recommendations");
        assertTrue(f.recommendations().stream().anyMatch(r -> r.contains("ZGC")),
                "Should recommend ZGC for sub-ms pauses");
        assertFalse(f.suggestedFlags().isEmpty(), "Finding should include suggested flags");
        assertTrue(f.suggestedFlags().stream().anyMatch(fl -> fl.contains("MaxGCPauseMillis")));
    }

    // --- helpers ---

    private static JvmSnapshot snapshotWithPause(long maxRecentPauseMs) {
        Map<String, Integer> states = Map.of("RUNNABLE", 10);
        return new JvmSnapshot(
                100 * MB, 512 * MB, 512 * MB, 0,
                Map.of(), List.of(),
                1L, 100L, 120_000L,
                0.1, 0.2, 4,
                10, 5, 10,
                states, 0,
                List.of(),
                1000, 1000, 0,
                0,
                "OpenJDK 64-Bit Server VM", "21.0.1", "G1",
                List.of(),
                maxRecentPauseMs
        );
    }
}
