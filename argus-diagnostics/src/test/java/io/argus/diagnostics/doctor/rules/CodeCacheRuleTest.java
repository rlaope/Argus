package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.JvmSnapshot;
import io.argus.diagnostics.doctor.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodeCacheRuleTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void unknownCodeCacheSize_noFinding() {
        assertTrue(new CodeCacheRule().evaluate(snapshot(0L, 0L, List.of())).isEmpty());
    }

    @Test
    void halfFull_noFinding() {
        assertTrue(new CodeCacheRule().evaluate(snapshot(120L * 1024, 240L * 1024, List.of())).isEmpty());
    }

    @Test
    void atWarnThreshold_emitsWarning() {
        List<Finding> findings = new CodeCacheRule()
                .evaluate(snapshot(80L * 1024, 100L * 1024, List.of()));
        assertEquals(1, findings.size());
        assertEquals(Severity.WARNING, findings.get(0).severity());
        assertTrue(findings.get(0).title().contains("CodeCache pressure"));
    }

    @Test
    void atCriticalThreshold_emitsCritical() {
        List<Finding> findings = new CodeCacheRule()
                .evaluate(snapshot(96L * 1024, 100L * 1024, List.of()));
        assertEquals(1, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
    }

    @Test
    void recommendsDoublingReservedSize() {
        Finding f = new CodeCacheRule()
                .evaluate(snapshot(80L * 1024, 100L * 1024, List.of())).get(0);
        assertTrue(f.suggestedFlags().stream().anyMatch(fl -> fl.equals("-XX:ReservedCodeCacheSize=256m")),
                "Should suggest doubling current size (100MB -> 200MB), clamped to 256m floor; got: " + f.suggestedFlags());
    }

    @Test
    void suggestsFlushingOnlyWhenDisabled() {
        Finding withoutDisabledFlag = new CodeCacheRule()
                .evaluate(snapshot(80L * 1024, 100L * 1024, List.of())).get(0);
        assertFalse(withoutDisabledFlag.suggestedFlags().stream()
                        .anyMatch(fl -> fl.contains("UseCodeCacheFlushing")),
                "Should not nag about flushing when user hasn't disabled it");

        Finding withDisabledFlag = new CodeCacheRule()
                .evaluate(snapshot(80L * 1024, 100L * 1024, List.of("-XX:-UseCodeCacheFlushing"))).get(0);
        assertTrue(withDisabledFlag.suggestedFlags().stream()
                        .anyMatch(fl -> fl.equals("-XX:+UseCodeCacheFlushing")),
                "Should recommend re-enabling flushing when user has disabled it");
    }

    private static JvmSnapshot snapshot(long codeCacheUsedKb, long codeCacheSizeKb, List<String> vmFlags) {
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
                "OpenJDK 64-Bit Server VM", "21.0.1", "G1",
                vmFlags,
                0L,
                codeCacheUsedKb, codeCacheSizeKb, Map.of()
        );
    }
}
