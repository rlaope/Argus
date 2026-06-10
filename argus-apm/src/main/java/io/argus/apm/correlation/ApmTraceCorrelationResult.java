package io.argus.apm.correlation;

import io.argus.apm.model.ApmTraceContext;
import io.argus.apm.model.JvmFinding;

import java.util.List;
import java.util.Objects;

public record ApmTraceCorrelationResult(
        ApmTraceContext trace,
        List<ApmFindingCorrelation> matches,
        List<JvmFinding> unmatchedFindings
) {
    public ApmTraceCorrelationResult {
        Objects.requireNonNull(trace, "trace");
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        unmatchedFindings = List.copyOf(Objects.requireNonNull(unmatchedFindings, "unmatchedFindings"));
    }
}
