package io.argus.cli.harness;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.Severity;

import java.util.List;
import java.util.Map;

/**
 * Final result of a completed harness session: timing, accumulated findings,
 * top-triggered rules, and per-severity counts.
 *
 * <p>Created once when the engine finishes; intended to be immutable.
 */
public final class HarnessResult {

    private final long pid;
    private final long startTimeMs;
    private final long endTimeMs;
    private final int sampleCount;
    private final HarnessProfile profile;
    private final List<Finding> findings;
    private final Map<String, Integer> ruleHitCounts;
    private final long criticalCount;
    private final long warningCount;
    private final long infoCount;

    public HarnessResult(long pid, long startTimeMs, long endTimeMs, int sampleCount,
                         HarnessProfile profile, List<Finding> findings,
                         Map<String, Integer> ruleHitCounts) {
        this.pid = pid;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.sampleCount = sampleCount;
        this.profile = profile;
        this.findings = List.copyOf(findings);
        this.ruleHitCounts = Map.copyOf(ruleHitCounts);
        this.criticalCount = findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        this.warningCount  = findings.stream().filter(f -> f.severity() == Severity.WARNING).count();
        this.infoCount     = findings.stream().filter(f -> f.severity() == Severity.INFO).count();
    }

    public long pid() { return pid; }
    public long startTimeMs() { return startTimeMs; }
    public long endTimeMs() { return endTimeMs; }
    public long durationMs() { return endTimeMs - startTimeMs; }
    public int sampleCount() { return sampleCount; }
    public HarnessProfile profile() { return profile; }
    public List<Finding> findings() { return findings; }
    public Map<String, Integer> ruleHitCounts() { return ruleHitCounts; }
    public long criticalCount() { return criticalCount; }
    public long warningCount() { return warningCount; }
    public long infoCount() { return infoCount; }

    /** Doctor-style exit code: 0 = healthy, 1 = warnings, 2 = critical. */
    public int exitCode() {
        if (criticalCount > 0) return 2;
        if (warningCount > 0) return 1;
        return 0;
    }
}
