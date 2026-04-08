package io.argus.cli.doctor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DoctorEngineTest {

    @Test
    void healthyJvm_noFindings() {
        JvmSnapshot healthy = snapshot(
                100 * MB, 512 * MB,  // heap: 20% used
                200, 120_000,        // gc: 200ms / 120s = 0.17% (healthy)
                0.3, 4,              // cpu: 30%, 4 cores
                50, 0, 0,            // threads: 50 total, 0 blocked, 0 deadlocked
                0                    // no pending finalization
        );
        List<Finding> findings = DoctorEngine.diagnose(healthy);
        assertTrue(findings.isEmpty(), "Healthy JVM should have 0 findings but got: " + findings);
        assertEquals(0, DoctorEngine.exitCode(findings));
    }

    @Test
    void highGcOverhead_warning() {
        JvmSnapshot s = snapshot(
                200 * MB, 512 * MB,
                9600, 120_000,  // gc: 9600ms / 120000ms = 8% overhead
                0.3, 4, 50, 0, 0, 0
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        assertTrue(findings.stream().anyMatch(f ->
                f.severity() == Severity.WARNING && f.category().equals("GC")));
        assertEquals(1, DoctorEngine.exitCode(findings));
    }

    @Test
    void criticalGcOverhead() {
        JvmSnapshot s = snapshot(
                400 * MB, 512 * MB,
                24000, 120_000,  // gc: 24000ms / 120000ms = 20% overhead → CRITICAL
                0.3, 4, 50, 0, 0, 0
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        assertTrue(findings.stream().anyMatch(f ->
                f.severity() == Severity.CRITICAL && f.category().equals("GC")));
        assertEquals(2, DoctorEngine.exitCode(findings));
    }

    @Test
    void highHeapUsage_warning() {
        JvmSnapshot s = snapshot(
                420 * MB, 512 * MB,  // heap: 82%
                1, 120_000, 0.3, 4, 50, 0, 0, 0
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        assertTrue(findings.stream().anyMatch(f ->
                f.category().equals("Memory") && f.severity() == Severity.WARNING));
    }

    @Test
    void criticalHeapUsage() {
        JvmSnapshot s = snapshot(
                490 * MB, 512 * MB,  // heap: 96% → CRITICAL
                1, 120_000, 0.3, 4, 50, 0, 0, 0
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        assertTrue(findings.stream().anyMatch(f ->
                f.category().equals("Memory") && f.severity() == Severity.CRITICAL));
    }

    @Test
    void deadlock_critical() {
        JvmSnapshot s = snapshot(
                100 * MB, 512 * MB,
                1, 120_000, 0.3, 4,
                50, 0, 2,  // 2 deadlocked threads
                0
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        assertTrue(findings.stream().anyMatch(f ->
                f.severity() == Severity.CRITICAL && f.title().contains("deadlocked")));
    }

    @Test
    void blockedThreads_warning() {
        JvmSnapshot s = snapshot(
                100 * MB, 512 * MB,
                1, 120_000, 0.3, 4,
                50, 10, 0,  // 10 blocked / 50 total = 20%
                0
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        assertTrue(findings.stream().anyMatch(f ->
                f.category().equals("Threads") && f.title().contains("BLOCKED")));
    }

    @Test
    void highCpu_warning() {
        JvmSnapshot s = snapshot(
                100 * MB, 512 * MB,
                1, 120_000,
                0.85, 4,  // CPU 85%
                50, 0, 0, 0
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        assertTrue(findings.stream().anyMatch(f ->
                f.category().equals("CPU")));
    }

    @Test
    void finalizerQueue_warning() {
        JvmSnapshot s = snapshot(
                100 * MB, 512 * MB,
                1, 120_000, 0.3, 4,
                50, 0, 0,
                500  // 500 pending finalization
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        assertTrue(findings.stream().anyMatch(f ->
                f.title().contains("pending finalization")));
    }

    @Test
    void findingsSortedBySeverity() {
        JvmSnapshot s = snapshot(
                490 * MB, 512 * MB,  // CRITICAL heap
                24000, 120_000,      // CRITICAL gc (20%)
                0.95, 4,             // CRITICAL cpu
                50, 0, 2,            // CRITICAL deadlock
                500                  // WARNING finalizer
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        assertFalse(findings.isEmpty());
        // First finding should be CRITICAL
        assertEquals(Severity.CRITICAL, findings.getFirst().severity());
    }

    @Test
    void suggestedFlags_collected() {
        JvmSnapshot s = snapshot(
                490 * MB, 512 * MB,
                24000, 120_000, 0.3, 4, 50, 0, 0, 0
        );
        List<Finding> findings = DoctorEngine.diagnose(s);
        List<String> flags = DoctorEngine.collectSuggestedFlags(findings);
        assertFalse(flags.isEmpty(), "Should have suggested JVM flags");
        assertTrue(flags.stream().anyMatch(f -> f.startsWith("-X")));
    }

    // --- Helper ---

    private static final long MB = 1024L * 1024;

    private static JvmSnapshot snapshot(long heapUsed, long heapMax,
                                         long gcTimeMs, long uptimeMs,
                                         double cpuLoad, int processors,
                                         int threads, int blocked, int deadlocked,
                                         int pendingFinalization) {
        Map<String, Integer> states = new java.util.LinkedHashMap<>();
        states.put("RUNNABLE", threads - blocked);
        states.put("BLOCKED", blocked);

        return new JvmSnapshot(
                heapUsed, heapMax, heapMax, 0,
                Map.of(), List.of(),
                gcTimeMs > 0 ? 1 : 0, gcTimeMs, uptimeMs,
                cpuLoad, 0.5, processors,
                threads, threads / 2, threads,
                states, deadlocked,
                List.of(),
                1000, 1000, 0,
                pendingFinalization,
                "OpenJDK 64-Bit Server VM", "21.0.1", "G1",
                List.of()
        );
    }
}
