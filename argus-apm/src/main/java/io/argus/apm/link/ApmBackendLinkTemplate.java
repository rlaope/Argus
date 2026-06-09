package io.argus.apm.link;

import io.argus.apm.model.ApmBackendLink;
import io.argus.apm.model.ApmBackendSignal;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ApmBackendLinkTemplate(
        ApmBackendSignal signal,
        String label,
        URI baseUri,
        String path
) {
    public ApmBackendLinkTemplate {
        Objects.requireNonNull(signal, "signal");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        Objects.requireNonNull(baseUri, "baseUri");
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
    }

    public static ApmBackendLinkTemplate of(ApmBackendSignal signal, String label, URI baseUri, String path) {
        return new ApmBackendLinkTemplate(signal, label, baseUri, path);
    }

    public ApmBackendLink render(ApmBackendLinkContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> params = new LinkedHashMap<>();
        put(params, "tenant", context.scope().tenant());
        put(params, "project", context.scope().project());
        put(params, "environment", context.scope().environment());
        if (context.service() != null) {
            put(params, "service", context.service().displayName());
        }
        put(params, "deployment", context.deploymentId());
        put(params, "instance", context.instanceId());
        put(params, "endpoint", context.endpointRoute());
        put(params, "traceId", context.traceId());
        put(params, "spanId", context.spanId());
        if (context.scope().timeRange() != null) {
            put(params, "from", instant(context.scope().timeRange().start()));
            put(params, "to", instant(context.scope().timeRange().end()));
        }
        return new ApmBackendLink(signal, label, URI.create(renderUri(params)), false, context.scope());
    }

    private String renderUri(Map<String, String> params) {
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringBuilder sb = new StringBuilder(base).append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    private static void put(Map<String, String> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    private static String instant(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
