package io.argus.apm.model;

import java.net.URI;
import java.util.Objects;

public record ApmBackendLink(
        ApmBackendSignal signal,
        String label,
        URI uri,
        boolean bestEffort,
        ApmScope scope
) {
    public ApmBackendLink {
        Objects.requireNonNull(signal, "signal");
        label = ApmValidation.requireText(label, "label");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(scope, "scope");
    }
}
