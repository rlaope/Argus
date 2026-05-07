package io.argus.cli.zgc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void stallsWithHotspotsVerdictUnhealthyAndHotspotsPopulated() {
        ZgcDiagnosis d = new ZgcDiagnosis();
        d.usingZgc = true;
        d.stalls.add(new ZgcDiagnosis.Stall("http-worker-1", 8.0));
        d.totalAllocEvents = 300;
        d.stallAllocHotspots.add(new ZgcDiagnosis.AllocHotspot(
                "com.example.Foo.bar(Foo.java:42)", 120, 40.0));
        d.stallAllocHotspots.add(new ZgcDiagnosis.AllocHotspot(
                "com.example.Bar.baz(Bar.java:99)", 90, 30.0));
        d.stallAllocHotspots.add(new ZgcDiagnosis.AllocHotspot(
                "com.example.Qux.run(Qux.java:7)", 60, 20.0));

        assertEquals(ZgcDiagnosis.Verdict.UNHEALTHY, d.compute());
        assertEquals(3, d.stallAllocHotspots.size());
        assertEquals("com.example.Foo.bar(Foo.java:42)", d.stallAllocHotspots.get(0).frame());
        assertEquals(120L, d.stallAllocHotspots.get(0).count());
        assertEquals(40.0, d.stallAllocHotspots.get(0).pct(), 0.001);
    }

    @Test
    void stallsWithNoAllocEventsVerdictUnhealthyAndHotspotsEmpty() {
        ZgcDiagnosis d = new ZgcDiagnosis();
        d.usingZgc = true;
        d.stalls.add(new ZgcDiagnosis.Stall("gc-worker-2", 5.5));
        // totalAllocEvents stays 0, stallAllocHotspots stays empty

        assertEquals(ZgcDiagnosis.Verdict.UNHEALTHY, d.compute());
        assertTrue(d.stallAllocHotspots.isEmpty(), "hotspots should be empty when no alloc events");
    }
}
