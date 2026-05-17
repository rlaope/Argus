package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.JvmSnapshot;
import io.argus.diagnostics.doctor.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectBufferRuleTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void emptyBufferPools_noNmt_noFinding() {
        assertTrue(new DirectBufferRule().evaluate(snapshot(List.of(), Map.of())).isEmpty());
    }

    @Test
    void smallDirectPool_noFinding() {
        JvmSnapshot s = snapshot(
                List.of(new JvmSnapshot.BufferInfo("direct", 100, 50 * MB, 50 * MB)),
                Map.of());
        assertTrue(new DirectBufferRule().evaluate(s).isEmpty());
    }

    @Test
    void largeDirectPool_emitsWarning() {
        JvmSnapshot s = snapshot(
                List.of(new JvmSnapshot.BufferInfo("direct", 100, 400 * MB, 400 * MB)),
                Map.of());
        List<Finding> findings = new DirectBufferRule().evaluate(s);
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
        assertTrue(findings.get(0).title().contains("400MB"));
    }

    @Test
    void hugeDirectPool_emitsCritical() {
        JvmSnapshot s = snapshot(
                List.of(new JvmSnapshot.BufferInfo("direct", 100, 900 * MB, 900 * MB)),
                Map.of());
        Finding f = new DirectBufferRule().evaluate(s).get(0);
        assertEquals(Severity.CRITICAL, f.severity());
    }

    // --- NMT fallback (JDK 16+ jcmd path) ---

    @Test
    void nmtFallback_otherCategory_emitsWarning() {
        // 400 MiB committed under "Other" — typical for DirectByteBuffer on JDK 17
        JvmSnapshot s = snapshot(List.of(), Map.of("Other", 400L * 1024));
        List<Finding> findings = new DirectBufferRule().evaluate(s);
        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals(Severity.WARNING, f.severity());
        assertTrue(f.title().contains("via NMT"),
                "NMT-derived finding should label itself: " + f.title());
        assertTrue(f.detail().toLowerCase().contains("nmt"));
    }

    @Test
    void nmtFallback_internalCategory_emitsCritical() {
        JvmSnapshot s = snapshot(List.of(), Map.of("Internal", 900L * 1024));
        Finding f = new DirectBufferRule().evaluate(s).get(0);
        assertEquals(Severity.CRITICAL, f.severity());
    }

    @Test
    void nmtFallback_smallCategory_noFinding() {
        JvmSnapshot s = snapshot(List.of(), Map.of("Other", 50L * 1024));
        assertTrue(new DirectBufferRule().evaluate(s).isEmpty());
    }

    @Test
    void nmtFallback_ignoresUnrelatedCategories() {
        JvmSnapshot s = snapshot(List.of(),
                Map.of("Java Heap", 800L * 1024, "Thread", 500L * 1024));
        assertTrue(new DirectBufferRule().evaluate(s).isEmpty(),
                "Java Heap / Thread are not direct-buffer territory");
    }

    @Test
    void bufferPoolsTakePrecedenceOverNmt() {
        // Both present but bufferPools small → no finding (NMT path not consulted)
        JvmSnapshot s = snapshot(
                List.of(new JvmSnapshot.BufferInfo("direct", 10, 10 * MB, 10 * MB)),
                Map.of("Other", 900L * 1024));
        assertTrue(new DirectBufferRule().evaluate(s).isEmpty(),
                "Reported pool dominates; fallback should not double-flag");
    }

    private static JvmSnapshot snapshot(List<JvmSnapshot.BufferInfo> bufferPools,
                                        Map<String, Long> nmtCommittedKbByCategory) {
        return new JvmSnapshot(
                100 * MB, 512 * MB, 512 * MB, 0,
                Map.of(), List.of(),
                1L, 100L, 120_000L,
                0.1, 0.2, 4,
                10, 5, 10,
                Map.of("RUNNABLE", 10), 0,
                bufferPools,
                1000, 1000, 0,
                0,
                "OpenJDK 64-Bit Server VM", "21.0.1", "G1",
                List.of(),
                0L,
                0L, 0L, nmtCommittedKbByCategory
        );
    }
}
