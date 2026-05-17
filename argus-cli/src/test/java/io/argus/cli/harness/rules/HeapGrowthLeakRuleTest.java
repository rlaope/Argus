package io.argus.cli.harness.rules;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.JvmSnapshot;
import io.argus.diagnostics.doctor.Severity;
import io.argus.cli.harness.HarnessSession;
import io.argus.cli.harness.TimedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeapGrowthLeakRuleTest {

    private final HeapGrowthLeakRule rule = new HeapGrowthLeakRule();

    @Test
    void firesOnSustainedLinearGrowth() {
        // 10 samples, 1 minute apart, heap rising 5 MB/min — well above 1 MB/min threshold.
        HarnessSession s = new HarnessSession(20, 60 * 60_000L);
        long base = 100L * 1024 * 1024;
        for (int i = 0; i < 10; i++) {
            long heap = base + (long) i * 5L * 1024 * 1024;
            s.record(new TimedSnapshot(i * 60_000L, snap(heap)));
        }
        List<Finding> findings = rule.evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
        assertTrue(findings.get(0).title().toLowerCase().contains("leak"));
    }

    @Test
    void doesNotFireOnFlatHeap() {
        HarnessSession s = new HarnessSession(20, 60 * 60_000L);
        for (int i = 0; i < 10; i++) {
            s.record(new TimedSnapshot(i * 60_000L, snap(100L * 1024 * 1024)));
        }
        assertEquals(List.of(), rule.evaluate(s));
    }

    @Test
    void doesNotFireOnNoisyButStableHeap() {
        HarnessSession s = new HarnessSession(20, 60 * 60_000L);
        long base = 100L * 1024 * 1024;
        // Saw-tooth: alternating up/down 10 MB — high variance, no slope.
        for (int i = 0; i < 10; i++) {
            long heap = base + ((i % 2 == 0) ? 0 : 10L * 1024 * 1024);
            s.record(new TimedSnapshot(i * 60_000L, snap(heap)));
        }
        assertEquals(List.of(), rule.evaluate(s));
    }

    @Test
    void doesNotFireBelowMinSamples() {
        HarnessSession s = new HarnessSession(20, 60 * 60_000L);
        for (int i = 0; i < 4; i++) {
            s.record(new TimedSnapshot(i * 60_000L, snap(100L * 1024 * 1024 + i * 5L * 1024 * 1024)));
        }
        assertEquals(List.of(), rule.evaluate(s));
    }

    private static JvmSnapshot snap(long heapUsed) {
        return new JvmSnapshot(
                heapUsed, 1024L * 1024 * 1024, heapUsed, 0,
                Map.of(),
                List.of(), 0, 0, 0,
                0, 0, 1,
                0, 0, 0,
                Map.of(), 0,
                List.of(),
                0, 0, 0, 0,
                "", "", "", List.of(),
                0L);
    }
}
