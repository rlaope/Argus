package io.argus.apm.correlation;

import io.argus.apm.model.ApmSpanSummary;
import io.argus.apm.model.ApmTraceContext;
import io.argus.apm.model.JvmFinding;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Correlates Argus JVM findings with OTel trace/span summaries.
 */
public final class ApmTraceFindingCorrelator {
    private final Duration timingWindow;

    public ApmTraceFindingCorrelator() {
        this(Duration.ofSeconds(1));
    }

    public ApmTraceFindingCorrelator(Duration timingWindow) {
        this.timingWindow = Objects.requireNonNull(timingWindow, "timingWindow");
        if (timingWindow.isNegative()) {
            throw new IllegalArgumentException("timingWindow must not be negative");
        }
    }

    public ApmTraceCorrelationResult correlate(ApmTraceContext trace, List<JvmFinding> candidateFindings) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(candidateFindings, "candidateFindings");
        List<ApmFindingCorrelation> matches = new ArrayList<>();
        List<JvmFinding> unmatched = new ArrayList<>();

        for (JvmFinding finding : candidateFindings) {
            Objects.requireNonNull(finding, "candidate finding");
            ApmFindingCorrelation match = match(trace, finding);
            if (match == null) {
                unmatched.add(finding);
            } else {
                matches.add(match);
            }
        }
        return new ApmTraceCorrelationResult(trace, matches, unmatched);
    }

    private ApmFindingCorrelation match(ApmTraceContext trace, JvmFinding finding) {
        if (!isBlank(finding.traceId()) && !finding.traceId().equals(trace.traceId())) {
            return null;
        }
        if (!isBlank(finding.spanId())) {
            for (ApmSpanSummary span : trace.spans()) {
                if (finding.spanId().equals(span.spanId())) {
                    return new ApmFindingCorrelation(finding, span, ApmCorrelationReason.TRACE_AND_SPAN_ID);
                }
            }
        }
        if (!isBlank(finding.traceId()) && finding.traceId().equals(trace.traceId())) {
            return new ApmFindingCorrelation(finding, null, ApmCorrelationReason.TRACE_ID);
        }
        for (ApmSpanSummary span : trace.spans()) {
            if (finding.service().equals(span.service()) && overlaps(finding.observedAt(), span)) {
                return new ApmFindingCorrelation(finding, span, ApmCorrelationReason.SERVICE_AND_TIME_OVERLAP);
            }
        }
        return null;
    }

    private boolean overlaps(Instant observedAt, ApmSpanSummary span) {
        Instant from = span.startTime().minus(timingWindow);
        Instant to = span.endTime().plus(timingWindow);
        return !observedAt.isBefore(from) && !observedAt.isAfter(to);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
