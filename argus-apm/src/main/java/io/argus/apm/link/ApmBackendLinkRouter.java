package io.argus.apm.link;

import io.argus.apm.model.ApmBackendLink;
import io.argus.apm.model.ApmBackendSignal;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ApmBackendLinkRouter {
    private final List<ApmBackendLinkTemplate> templates;

    public ApmBackendLinkRouter(List<ApmBackendLinkTemplate> templates) {
        this.templates = List.copyOf(Objects.requireNonNull(templates, "templates"));
    }

    public static ApmBackendLinkRouter mvp(URI grafanaBase, URI tempoOrJaegerBase,
                                           URI lokiBase, URI pyroscopeBase) {
        return new ApmBackendLinkRouter(List.of(
                ApmBackendLinkTemplate.of(ApmBackendSignal.METRICS, "prometheus", grafanaBase, "/d/argus-apm"),
                ApmBackendLinkTemplate.of(ApmBackendSignal.TRACES, "tempo-or-jaeger", tempoOrJaegerBase, "/trace"),
                ApmBackendLinkTemplate.of(ApmBackendSignal.LOGS, "loki", lokiBase, "/explore"),
                ApmBackendLinkTemplate.of(ApmBackendSignal.PROFILES, "pyroscope", pyroscopeBase, "/profiles")
        ));
    }

    public List<ApmBackendLink> linksFor(ApmBackendLinkContext context) {
        Objects.requireNonNull(context, "context");
        List<ApmBackendLink> links = new ArrayList<>();
        for (ApmBackendLinkTemplate template : templates) {
            links.add(template.render(context));
        }
        return links;
    }

    public List<ApmBackendLink> linksFor(ApmBackendSignal signal, ApmBackendLinkContext context) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(context, "context");
        List<ApmBackendLink> links = new ArrayList<>();
        for (ApmBackendLinkTemplate template : templates) {
            if (template.signal() == signal) {
                links.add(template.render(context));
            }
        }
        return links;
    }
}
