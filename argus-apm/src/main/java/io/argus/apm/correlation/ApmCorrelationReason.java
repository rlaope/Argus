package io.argus.apm.correlation;

public enum ApmCorrelationReason {
    TRACE_AND_SPAN_ID,
    TRACE_ID,
    SERVICE_AND_TIME_OVERLAP
}
