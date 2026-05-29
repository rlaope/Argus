package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.JvmSnapshot;
import io.argus.diagnostics.doctor.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CarrierSaturationRuleTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void noVtTelemetry_staysSilent() {
        // parallelism 0 means VT telemetry was never collected
        assertTrue(new CarrierSaturationRule()
                .evaluate(snapshot(0, 0, 0, 0)).isEmpty());
    }

    @Test
    void saturatedWithFailuresAndBacklog_fires() {
        // 8/8 carriers active, 3 submit failures, backlog of 40 queued VTs
        List<Finding> findings = new CarrierSaturationRule()
                .evaluate(snapshot(8, 8, 3, 40));
        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("VirtualThreads", f.category());
        assertTrue(f.title().contains("Carrier pool saturated"), f.title());
    }

    @Test
    void submitFailuresAtOrAboveParallelism_isCritical() {
        // 8 submit failures with parallelism 8 -> critical
        Finding f = new CarrierSaturationRule()
                .evaluate(snapshot(8, 8, 8, 50)).get(0);
        assertEquals(Severity.CRITICAL, f.severity());
    }

    @Test
    void fewFailuresButSaturated_isWarning() {
        Finding f = new CarrierSaturationRule()
                .evaluate(snapshot(8, 8, 2, 10)).get(0);
        assertEquals(Severity.WARNING, f.severity());
    }

    @Test
    void failuresButNoBacklog_staysQuiet() {
        // transient submit failures with no standing backlog -> not the saturation signal
        assertTrue(new CarrierSaturationRule()
                .evaluate(snapshot(8, 8, 5, 0)).isEmpty());
    }

    @Test
    void backlogButCarriersIdle_staysQuiet() {
        // carriers nowhere near parallelism -> bottleneck is elsewhere, not the pool
        assertTrue(new CarrierSaturationRule()
                .evaluate(snapshot(8, 2, 5, 40)).isEmpty());
    }

    @Test
    void noSubmitFailures_staysQuiet() {
        assertTrue(new CarrierSaturationRule()
                .evaluate(snapshot(8, 8, 0, 40)).isEmpty());
    }

    private static JvmSnapshot snapshot(int parallelism, int activeCarriers,
                                        long submitFailures, long backlog) {
        return new JvmSnapshot(
                100 * MB, 512 * MB, 512 * MB, 0,
                Map.of(), List.of(),
                1L, 100L, 120_000L,
                0.1, 0.2, 4,
                10, 5, 10,
                Map.of("RUNNABLE", 10), 0,
                List.of(),
                1000, 1000, 0,
                0,
                "OpenJDK 64-Bit Server VM", "24", "G1",
                List.of(),
                0L,
                0L, 0L, Map.of(),
                null,
                parallelism, activeCarriers, submitFailures, backlog
        );
    }
}
