package io.argus.apm;

import io.argus.apm.otel.OtelSemanticAttributes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApmOtelSemanticAttributesTest {
    @Test
    void keepsOtelResourceAndRouteAttributeNamesStable() {
        assertEquals("service.name", OtelSemanticAttributes.SERVICE_NAME);
        assertEquals("service.namespace", OtelSemanticAttributes.SERVICE_NAMESPACE);
        assertEquals("service.version", OtelSemanticAttributes.SERVICE_VERSION);
        assertEquals("deployment.environment.name", OtelSemanticAttributes.DEPLOYMENT_ENVIRONMENT_NAME);
        assertEquals("http.route", OtelSemanticAttributes.HTTP_ROUTE);
        assertEquals("http.request.method", OtelSemanticAttributes.HTTP_REQUEST_METHOD);
        assertEquals("trace_id", OtelSemanticAttributes.TRACE_ID);
        assertEquals("span_id", OtelSemanticAttributes.SPAN_ID);
    }
}
