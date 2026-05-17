package io.argus.spring.diagnostics;

import io.argus.diagnostics.doctor.DoctorEngine;
import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.JvmSnapshot;
import io.argus.diagnostics.doctor.JvmSnapshotCollector;

import java.util.List;

/**
 * Programmatic entry point for the Argus doctor engine.
 *
 * <p>Wraps the static facades in {@link DoctorEngine} and
 * {@link JvmSnapshotCollector} so applications can use Spring DI to
 * inject and override diagnosis behavior.
 *
 * <pre>{@code
 * @Autowired DoctorService doctor;
 *
 * @GetMapping("/admin/health/jvm")
 * public List<Finding> jvmHealth() {
 *     return doctor.diagnoseLocal();
 * }
 * }</pre>
 */
public class DoctorService {

    /**
     * Diagnose the JVM hosting the current process.
     *
     * @return sorted findings (CRITICAL first), never null
     */
    public List<Finding> diagnoseLocal() {
        return DoctorEngine.diagnose(JvmSnapshotCollector.collectLocal());
    }

    /**
     * Diagnose a remote JVM by PID via {@code jcmd}.
     *
     * @param pid target JVM process id (0 or current pid routes to local)
     * @return sorted findings (CRITICAL first), never null
     */
    public List<Finding> diagnoseRemote(long pid) {
        return DoctorEngine.diagnose(JvmSnapshotCollector.collect(pid));
    }

    /**
     * Diagnose a pre-collected snapshot — useful for tests or for callers
     * that source snapshots from somewhere other than this JVM / jcmd.
     */
    public List<Finding> diagnose(JvmSnapshot snapshot) {
        return DoctorEngine.diagnose(snapshot);
    }

    /**
     * Same as {@link #diagnose(JvmSnapshot)} but lets callers override the
     * MaxPauseRule warning threshold (critical is set to {@code warning × 4}).
     */
    public List<Finding> diagnose(JvmSnapshot snapshot, long pauseThresholdMs) {
        return DoctorEngine.diagnose(snapshot, pauseThresholdMs);
    }

    /** Warnings emitted by the most recent {@code diagnoseRemote} call (jcmd failures, etc.). */
    public List<String> lastCollectionWarnings() {
        return JvmSnapshotCollector.lastWarnings();
    }
}
