package io.argus.apm.model;

public enum ApmMetadataSource {
    OPEN_TELEMETRY_RESOURCE,
    KUBERNETES_METADATA,
    ARGUS_FLEET_LABEL,
    USER_OVERRIDE,
    SERVICE_CATALOG,
    ROUTE_NORMALIZER
}
