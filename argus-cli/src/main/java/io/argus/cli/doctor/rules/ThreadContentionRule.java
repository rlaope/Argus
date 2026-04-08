package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects thread contention issues: blocked threads, deadlocks, excessive thread count.
 */
public final class ThreadContentionRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        List<Finding> findings = new ArrayList<>();

        // Deadlocks — always critical
        if (s.deadlockedThreads() > 0) {
            findings.add(Finding.builder(Severity.CRITICAL, "Threads",
                            s.deadlockedThreads() + " deadlocked thread(s) detected")
                    .detail("Application has threads permanently blocked waiting for each other. "
                            + "This will cause hangs and timeouts.")
                    .recommend("Run: argus deadlock <pid> for full deadlock chain analysis")
                    .recommend("Replace synchronized blocks with java.util.concurrent.locks.ReentrantLock")
                    .build());
        }

        // Blocked threads — use ratio to avoid false positives on large apps
        int blocked = s.blockedThreads();
        int total = s.threadCount();
        double blockedRatio = total > 0 ? (double) blocked / total * 100 : 0;
        if (blocked >= 3 && blockedRatio >= 5) {
            Severity sev = blockedRatio >= 15 ? Severity.CRITICAL : Severity.WARNING;
            findings.add(Finding.builder(sev, "Threads",
                            String.format("%d threads BLOCKED (%.0f%% of %d total)", blocked, blockedRatio, total))
                    .detail("Threads waiting to acquire object monitors. High contention reduces throughput "
                            + "and increases latency.")
                    .recommend("Run: argus threaddump <pid> to identify the contended monitor")
                    .recommend("Consider lock-free data structures or reducing critical section scope")
                    .build());
        }

        // Excessive thread count
        if (s.threadCount() > 500) {
            Severity sev = s.threadCount() > 1000 ? Severity.CRITICAL : Severity.WARNING;
            findings.add(Finding.builder(sev, "Threads",
                            s.threadCount() + " live threads (peak: " + s.peakThreadCount() + ")")
                    .detail("High thread count increases context switching overhead and memory usage "
                            + "(each thread uses ~1MB stack by default).")
                    .recommend("Consider using virtual threads (Java 21+) for I/O-bound work")
                    .recommend("Review thread pool sizes — they may be over-provisioned")
                    .flag("-XX:ThreadStackSize=512")
                    .build());
        }

        return findings;
    }
}
