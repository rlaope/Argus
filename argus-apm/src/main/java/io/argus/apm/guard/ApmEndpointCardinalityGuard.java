package io.argus.apm.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ApmEndpointCardinalityGuard {
    private static final int MAX_ROUTE_LENGTH = 160;
    private static final int MAX_SEGMENTS = 12;
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}$");
    private static final Pattern LONG_NUMBER = Pattern.compile("^[0-9]{4,}$");
    private static final Pattern LONG_HEX = Pattern.compile("^[0-9a-fA-F]{10,}$");

    private ApmEndpointCardinalityGuard() {
    }

    public static ApmRouteNormalization normalize(String method, String rawPath) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (rawPath == null || rawPath.isBlank() || !rawPath.startsWith("/")) {
            throw new IllegalArgumentException("rawPath must start with /");
        }
        String path = rawPath.split("[?#]", 2)[0];
        if (path.length() > MAX_ROUTE_LENGTH) {
            throw new IllegalArgumentException("route exceeds max length " + MAX_ROUTE_LENGTH);
        }
        String[] segments = path.substring(1).split("/");
        if (segments.length > MAX_SEGMENTS) {
            throw new IllegalArgumentException("route exceeds max segment count " + MAX_SEGMENTS);
        }
        List<String> reasons = new ArrayList<>();
        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            if (isHighCardinality(segment)) {
                normalized.add("{id}");
                reasons.add("high-cardinality segment");
            } else {
                normalized.add(segment.toLowerCase(Locale.ROOT));
            }
        }
        String route = "/" + String.join("/", normalized);
        return new ApmRouteNormalization(method, route, !reasons.isEmpty() || !route.equals(path), reasons);
    }

    private static boolean isHighCardinality(String segment) {
        return UUID.matcher(segment).matches()
                || LONG_NUMBER.matcher(segment).matches()
                || LONG_HEX.matcher(segment).matches()
                || segment.contains("@");
    }
}
