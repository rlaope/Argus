package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.List;

/**
 * Detects backed-up finalizer queue — objects waiting for finalization
 * can prevent GC from reclaiming memory.
 */
public final class FinalizerQueueRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        int pending = s.pendingFinalization();
        if (pending < 100) return List.of();

        Severity sev = pending >= 1000 ? Severity.CRITICAL : Severity.WARNING;
        return List.of(Finding.builder(sev, "Memory",
                        pending + " objects pending finalization")
                .detail("Objects with finalize() methods are queued for the Finalizer thread. "
                        + "A backed-up queue means the Finalizer thread can't keep up, "
                        + "delaying memory reclamation and potentially causing OOM.")
                .recommend("Migrate from finalize() to java.lang.ref.Cleaner or try-with-resources")
                .recommend("Run: argus finalizer <pid> to monitor the queue")
                .build());
    }
}
