package io.argus.apm.otel;

/**
 * OTel semantic-convention keys used by the APM facade model.
 */
public final class OtelSemanticAttributes {
    public static final String SERVICE_NAME = "service.name";
    public static final String SERVICE_NAMESPACE = "service.namespace";
    public static final String SERVICE_VERSION = "service.version";
    public static final String DEPLOYMENT_ENVIRONMENT_NAME = "deployment.environment.name";
    public static final String DEPLOYMENT_ENVIRONMENT_LEGACY = "deployment.environment";
    public static final String HTTP_ROUTE = "http.route";
    public static final String HTTP_REQUEST_METHOD = "http.request.method";
    public static final String URL_PATH = "url.path";
    public static final String TRACE_ID = "trace_id";
    public static final String SPAN_ID = "span_id";

    private OtelSemanticAttributes() {
    }
}
