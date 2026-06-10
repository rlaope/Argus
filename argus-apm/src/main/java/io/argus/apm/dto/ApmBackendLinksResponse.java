package io.argus.apm.dto;

import io.argus.apm.model.ApmBackendLink;
import io.argus.apm.model.ApmScope;

import java.util.List;
import java.util.Objects;

public record ApmBackendLinksResponse(ApmScope scope, List<ApmBackendLink> links) {
    public ApmBackendLinksResponse {
        Objects.requireNonNull(scope, "scope");
        links = List.copyOf(Objects.requireNonNull(links, "links"));
    }
}
