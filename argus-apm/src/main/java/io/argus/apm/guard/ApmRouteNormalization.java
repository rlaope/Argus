package io.argus.apm.guard;

import java.util.List;
import java.util.Objects;

public record ApmRouteNormalization(
        String method,
        String route,
        boolean normalized,
        List<String> reasons
) {
    public ApmRouteNormalization {
        method = requireText(method, "method").toUpperCase(java.util.Locale.ROOT);
        route = requireText(route, "route");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
