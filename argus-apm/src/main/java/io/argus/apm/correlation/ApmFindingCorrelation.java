package io.argus.apm.correlation;

import io.argus.apm.model.ApmSpanSummary;
import io.argus.apm.model.JvmFinding;

import java.util.Objects;

public record ApmFindingCorrelation(
        JvmFinding finding,
        ApmSpanSummary span,
        ApmCorrelationReason reason
) {
    public ApmFindingCorrelation {
        Objects.requireNonNull(finding, "finding");
        Objects.requireNonNull(reason, "reason");
    }
}
