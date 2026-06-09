package io.argus.apm.model;

import java.net.URI;
import java.util.Objects;

public record RunbookLink(String title, URI uri) {
    public RunbookLink {
        title = ApmValidation.requireText(title, "title");
        Objects.requireNonNull(uri, "uri");
    }
}
