package io.argus.apm;

import java.util.Set;

/**
 * Contract route names for the public APM facade.
 */
public final class ApmFacadeRoutes {
    public static final String SERVICES = "/apm/services";
    public static final String SERVICE = "/apm/services/{service}";
    public static final String SERVICE_ENDPOINTS = "/apm/services/{service}/endpoints";
    public static final String TRACE = "/apm/traces/{traceId}";
    public static final String INCIDENTS = "/apm/incidents";
    public static final String BACKEND_LINKS = "/apm/backend-links";

    private static final Set<String> PUBLIC_ROUTES = Set.of(
            SERVICES,
            SERVICE,
            SERVICE_ENDPOINTS,
            TRACE,
            INCIDENTS,
            BACKEND_LINKS
    );

    private static final Set<String> FORBIDDEN_AGGREGATOR_PREFIXES = Set.of(
            "/fleet",
            "/api/pods",
            "/profile"
    );

    private ApmFacadeRoutes() {
    }

    public static Set<String> publicRoutes() {
        return PUBLIC_ROUTES;
    }

    public static boolean isPublicApmRoute(String path) {
        return path != null && path.startsWith("/apm/");
    }

    public static boolean isForbiddenAggregatorRoute(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : FORBIDDEN_AGGREGATOR_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
