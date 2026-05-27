package io.argus.diagnostics.g1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class G1DiagnosisVerdictTest {

    @Test
    void healthy_when_no_pathology() {
        G1Diagnosis d = new G1Diagnosis();
        d.targetPauseMs = 200;
        d.maxPauseMs = 80; // < target
        d.youngCycles = 50;
        d.mixedCycles = 4;
        assertEquals(G1Diagnosis.Verdict.HEALTHY, d.compute());
    }

    @Test
    void unhealthy_when_full_gc_seen() {
        G1Diagnosis d = new G1Diagnosis();
        d.targetPauseMs = 200;
        d.fullGcSeen = true;
        d.fullGcCycles = 1;
        assertEquals(G1Diagnosis.Verdict.UNHEALTHY, d.compute());
    }

    @Test
    void unhealthy_when_evacuation_failure() {
        G1Diagnosis d = new G1Diagnosis();
        d.targetPauseMs = 200;
        d.evacuationFailureSeen = true;
        d.evacuationFailures = 1;
        assertEquals(G1Diagnosis.Verdict.UNHEALTHY, d.compute());
    }

    @Test
    void warning_when_mixed_starvation() {
        G1Diagnosis d = new G1Diagnosis();
        d.targetPauseMs = 200;
        d.mixedStarvation = true;
        assertEquals(G1Diagnosis.Verdict.WARNING, d.compute());
    }

    @Test
    void warning_when_ihop_mistimed() {
        G1Diagnosis d = new G1Diagnosis();
        d.targetPauseMs = 200;
        d.ihopMistimed = true;
        assertEquals(G1Diagnosis.Verdict.WARNING, d.compute());
    }

    @Test
    void warning_when_humongous_cycles_present() {
        G1Diagnosis d = new G1Diagnosis();
        d.targetPauseMs = 200;
        d.humongousAllocationCycles = 3;
        assertEquals(G1Diagnosis.Verdict.WARNING, d.compute());
    }

    @Test
    void warning_when_max_pause_exceeds_2x_target() {
        G1Diagnosis d = new G1Diagnosis();
        d.targetPauseMs = 200;
        d.maxPauseMs = 450; // > 2 × 200
        assertEquals(G1Diagnosis.Verdict.WARNING, d.compute());
    }

    @Test
    void full_gc_dominates_other_warnings() {
        G1Diagnosis d = new G1Diagnosis();
        d.targetPauseMs = 200;
        d.fullGcSeen = true;
        d.mixedStarvation = true;
        d.humongousAllocationCycles = 5;
        assertEquals(G1Diagnosis.Verdict.UNHEALTHY, d.compute());
    }

    @Test
    void default_target_pause_falls_back_to_200_when_zero() {
        G1Diagnosis d = new G1Diagnosis();
        d.targetPauseMs = 0; // unknown
        d.maxPauseMs = 350; // < 400 (2 × default 200) → still HEALTHY
        assertEquals(G1Diagnosis.Verdict.HEALTHY, d.compute());

        d.maxPauseMs = 410; // > 400
        assertEquals(G1Diagnosis.Verdict.WARNING, d.compute());
    }
}
