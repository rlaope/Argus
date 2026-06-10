package io.argus.apm;

import io.argus.apm.correlation.ApmCorrelationReason;
import io.argus.apm.correlation.ApmTraceCorrelationResult;
import io.argus.apm.correlation.ApmTraceFindingCorrelator;
import io.argus.apm.model.ApmFindingKind;
import io.argus.apm.model.ApmHealth;
import io.argus.apm.model.ApmServiceId;
import io.argus.apm.model.ApmSeverity;
import io.argus.apm.model.ApmSpanSummary;
import io.argus.apm.model.ApmTraceContext;
import io.argus.apm.model.JvmFinding;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApmTraceFindingCorrelatorTest {
    @Test
    void prefersSpanIdThenTraceIdThenTimingOverlap() {
        Instant now = Instant.parse("2026-06-09T00:00:00Z");
        ApmServiceId checkout = new ApmServiceId("shop", "checkout");
        ApmTraceContext trace = new ApmTraceContext(
                "trace-1",
                checkout,
                "span-1",
                now,
                200,
                ApmHealth.DEGRADED,
                List.of(new ApmSpanSummary("span-1", "", checkout, "GET /checkout/{id}", "/checkout/{id}", now, 200)),
                List.of(),
                List.of(),
                List.of()
        );

        JvmFinding spanMatch = finding("finding-1", checkout, "trace-1", "span-1", now.plusMillis(10));
        JvmFinding traceMatch = finding("finding-2", checkout, "trace-1", "", now.plusMillis(20));
        JvmFinding timingMatch = finding("finding-3", checkout, "", "", now.plusMillis(100));
        JvmFinding noMatch = finding("finding-4", new ApmServiceId("shop", "catalog"), "trace-2", "", now);

        ApmTraceCorrelationResult result = new ApmTraceFindingCorrelator()
                .correlate(trace, List.of(spanMatch, traceMatch, timingMatch, noMatch));

        assertEquals(3, result.matches().size());
        assertEquals(ApmCorrelationReason.TRACE_AND_SPAN_ID, result.matches().get(0).reason());
        assertEquals(ApmCorrelationReason.TRACE_ID, result.matches().get(1).reason());
        assertEquals(ApmCorrelationReason.SERVICE_AND_TIME_OVERLAP, result.matches().get(2).reason());
        assertEquals(List.of(noMatch), result.unmatchedFindings());
    }

    private static JvmFinding finding(String id, ApmServiceId service, String traceId, String spanId, Instant observedAt) {
        return new JvmFinding(
                id,
                ApmFindingKind.GC_PAUSE,
                ApmSeverity.WARNING,
                "GC pause",
                "",
                service,
                "pod-1",
                traceId,
                spanId,
                observedAt,
                List.of()
        );
    }
}
