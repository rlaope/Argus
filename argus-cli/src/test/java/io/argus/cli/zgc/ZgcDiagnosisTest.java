package io.argus.cli.zgc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZgcDiagnosisTest {

    @Test
    void healthyWhenNoStallsNoBreachNoOverlap() {
        ZgcDiagnosis d = new ZgcDiagnosis();
        d.usingZgc = true;
        d.softMaxBreached = false;
        d.cycleOverlap = false;
        d.pauseMarkEndMs = 0.5;

        assertEquals(ZgcDiagnosis.Verdict.HEALTHY, d.compute());
    }

    @Test
    void unhealthyWhenAtLeastOneStall() {
        ZgcDiagnosis d = new ZgcDiagnosis();
        d.usingZgc = true;
        d.softMaxBreached = false;
        d.cycleOverlap = false;
        d.stalls.add(new ZgcDiagnosis.Stall("http-worker-1", 12.5));

        assertEquals(ZgcDiagnosis.Verdict.UNHEALTHY, d.compute());
    }

    @Test
    void warningWhenOnlySoftMaxBreached() {
        ZgcDiagnosis d = new ZgcDiagnosis();
        d.usingZgc = true;
        d.softMaxBreached = true;
        d.cycleOverlap = false;
        d.pauseMarkEndMs = 0.4;

        assertEquals(ZgcDiagnosis.Verdict.WARNING, d.compute());
    }
}
