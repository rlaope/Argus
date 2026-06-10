package io.argus.apm.demo;

import io.argus.apm.link.ApmBackendLinkContext;
import io.argus.apm.link.ApmBackendLinkRouter;
import io.argus.apm.model.ApmBackendLink;
import io.argus.apm.model.ApmDeployment;
import io.argus.apm.model.ApmEndpointSummary;
import io.argus.apm.model.ApmEntityIdentity;
import io.argus.apm.model.ApmFindingKind;
import io.argus.apm.model.ApmHealth;
import io.argus.apm.model.ApmIncident;
import io.argus.apm.model.ApmIncidentStatus;
import io.argus.apm.model.ApmInstance;
import io.argus.apm.model.ApmInstanceStatus;
import io.argus.apm.model.ApmMetadataSource;
import io.argus.apm.model.ApmOwner;
import io.argus.apm.model.ApmProfileReference;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceDetail;
import io.argus.apm.model.ApmServiceId;
import io.argus.apm.model.ApmServiceSummary;
import io.argus.apm.model.ApmSeverity;
import io.argus.apm.model.ApmSignalStats;
import io.argus.apm.model.ApmSpanSummary;
import io.argus.apm.model.ApmTimeRange;
import io.argus.apm.model.ApmTraceContext;
import io.argus.apm.model.JvmFinding;
import io.argus.apm.model.RunbookLink;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApmDemoCatalog {
    private static final Instant BASE_TIME = Instant.parse("2026-06-09T00:00:00Z");
    private static final ApmBackendLinkRouter LINK_ROUTER = ApmBackendLinkRouter.mvp(
            URI.create("https://grafana.local"),
            URI.create("https://tempo.local"),
            URI.create("https://loki.local"),
            URI.create("https://pyroscope.local")
    );

    private ApmDemoCatalog() {
    }

    public static ApmDemoTopology defaultTopology() {
        ApmScope scope = ApmScope.environment("tenant-a", "payments", "prod")
                .withTimeRange(new ApmTimeRange(BASE_TIME.minusSeconds(3600), BASE_TIME));
        ApmServiceId checkout = new ApmServiceId("shop", "checkout");
        ApmServiceId session = new ApmServiceId("identity", "session");

        List<ApmDemoScenario> scenarios = List.of(
                scenario(scope, ApmDemoScenarioType.GC_LATENCY, checkout, "checkout-7f4c", "pod-checkout-a",
                        "POST", "/checkout", "GC latency checkout regression", ApmFindingKind.GC_PAUSE,
                        ApmSeverity.WARNING, ApmHealth.DEGRADED, "G1 evacuation pause overlaps checkout spans.",
                        "trace-gc-latency", "span-gc-latency", 180, BASE_TIME.minusSeconds(210)),
                scenario(scope, ApmDemoScenarioType.LOCK_CONTENTION, checkout, "checkout-7f4c", "pod-checkout-b",
                        "GET", "/checkout/{id}", "Checkout lock contention hotspot", ApmFindingKind.LOCK_CONTENTION,
                        ApmSeverity.CRITICAL, ApmHealth.UNHEALTHY, "PaymentClient cache lock blocks carrier threads.",
                        "trace-lock-contention", "span-lock-contention", 240, BASE_TIME.minusSeconds(170)),
                scenario(scope, ApmDemoScenarioType.VIRTUAL_THREAD_PINNING, session, "session-65dd", "pod-session-a",
                        "POST", "/session", "Session virtual-thread pinning", ApmFindingKind.VIRTUAL_THREAD_PINNING,
                        ApmSeverity.WARNING, ApmHealth.DEGRADED, "Synchronized token refresh pins request carriers.",
                        "trace-vthread-pinning", "span-vthread-pinning", 220, BASE_TIME.minusSeconds(130)),
                scenario(scope, ApmDemoScenarioType.BAD_RELEASE_REGRESSION, session, "session-65dd", "pod-session-b",
                        "POST", "/session", "Session bad release regression", ApmFindingKind.DEPLOYMENT_REGRESSION,
                        ApmSeverity.CRITICAL, ApmHealth.UNHEALTHY, "Deployment 2026.06.09 shifted p95 latency and error rate.",
                        "trace-bad-release", "span-bad-release", 420, BASE_TIME.minusSeconds(90))
        );

        return new ApmDemoTopology(scope, List.of(
                service(scope, checkout, "checkout-7f4c", "payments-platform", ApmHealth.UNHEALTHY,
                        scenarios.stream().filter(s -> s.service().equals(checkout)).toList()),
                service(scope, session, "session-65dd", "identity", ApmHealth.UNHEALTHY,
                        scenarios.stream().filter(s -> s.service().equals(session)).toList())
        ), scenarios);
    }

    private static ApmDemoScenario scenario(ApmScope scope, ApmDemoScenarioType type, ApmServiceId service,
                                            String deploymentId, String instanceId, String method,
                                            String endpointRoute, String title, ApmFindingKind kind,
                                            ApmSeverity severity, ApmHealth health, String detail,
                                            String traceId, String spanId, long latencyP95,
                                            Instant observedAt) {
        ApmScope scenarioScope = scope.withService(service)
                .withDeployment(deploymentId)
                .withInstance(instanceId)
                .withEndpointRoute(endpointRoute);
        List<ApmBackendLink> links = LINK_ROUTER.linksFor(new ApmBackendLinkContext(
                scenarioScope, service, deploymentId, instanceId, endpointRoute, traceId, spanId));
        JvmFinding finding = new JvmFinding(
                type.name().toLowerCase(java.util.Locale.ROOT),
                kind,
                severity,
                title,
                detail,
                service,
                instanceId,
                traceId,
                spanId,
                observedAt,
                links
        );
        ApmTraceContext trace = new ApmTraceContext(
                traceId,
                service,
                spanId,
                observedAt.minusMillis(latencyP95),
                latencyP95,
                health,
                List.of(new ApmSpanSummary(spanId, "", service, method + " " + endpointRoute,
                        endpointRoute, observedAt.minusMillis(latencyP95), latencyP95)),
                List.of(finding),
                List.of(),
                links
        );
        ApmIncident incident = new ApmIncident(
                "incident-" + type.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                ApmIncidentStatus.OPEN,
                severity,
                title,
                service,
                observedAt.minusSeconds(60),
                observedAt,
                List.of(finding),
                links
        );
        String localPath = "/?service=" + encode(service.displayName()) + "&scenario=" + type.name().toLowerCase(java.util.Locale.ROOT);
        return new ApmDemoScenario(type, title, service, method, endpointRoute, trace, incident,
                List.of(finding), links, localPath, links.get(0).uri().toString());
    }

    private static ApmServiceDetail service(ApmScope scope, ApmServiceId service, String deploymentId,
                                            String owner, ApmHealth health,
                                            List<ApmDemoScenario> scenarios) {
        ApmEntityIdentity identity = new ApmEntityIdentity(
                "otel:" + service.displayName(),
                ApmMetadataSource.OPEN_TELEMETRY_RESOURCE,
                Map.of("service.name", service.name(), "service.namespace", service.namespace()),
                List.of()
        );
        List<JvmFinding> findings = scenarios.stream().flatMap(s -> s.findings().stream()).toList();
        List<ApmBackendLink> links = scenarios.isEmpty() ? List.of() : scenarios.get(0).backendLinks();
        ApmServiceSummary summary = new ApmServiceSummary(
                service,
                service.displayName(),
                health,
                new ApmOwner(owner, owner + "@example.com", "pagerduty/" + owner),
                new RunbookLink("APM demo runbook", URI.create("https://runbooks.example/argus-apm-demo")),
                new ApmSignalStats(120, 2.0, 40, maxLatency(scenarios), maxLatency(scenarios) + 120, maxGc(scenarios), 0.72, 0.61),
                identity,
                findings,
                links
        );
        ApmDeployment deployment = new ApmDeployment(
                deploymentId,
                service,
                "2026.06.09",
                scope.environment(),
                BASE_TIME.minusSeconds(7200),
                BASE_TIME,
                health,
                identity
        );
        ApmInstance instance = new ApmInstance(
                deploymentId + "-demo",
                service,
                deploymentId,
                service.namespace(),
                deploymentId + "-demo",
                "node-demo",
                health == ApmHealth.UNHEALTHY ? ApmInstanceStatus.DEGRADED : ApmInstanceStatus.UP,
                BASE_TIME,
                identity
        );
        List<ApmEndpointSummary> endpoints = scenarios.stream()
                .map(s -> new ApmEndpointSummary(service, s.method(), s.endpointRoute(), identity,
                        ApmSignalStats.empty(), s.findings(), s.backendLinks()))
                .toList();
        List<ApmProfileReference> profiles = scenarios.stream()
                .map(s -> new ApmProfileReference("profile-" + s.type().name().toLowerCase(java.util.Locale.ROOT),
                        service, deploymentId + "-demo", "cpu", BASE_TIME.minusSeconds(120), BASE_TIME,
                        s.backendLinks().get(s.backendLinks().size() - 1)))
                .toList();
        return new ApmServiceDetail(summary, List.of(deployment), List.of(instance), endpoints, profiles);
    }

    private static double maxLatency(List<ApmDemoScenario> scenarios) {
        return scenarios.stream().mapToLong(s -> s.trace().durationMillis()).max().orElse(0);
    }

    private static double maxGc(List<ApmDemoScenario> scenarios) {
        return scenarios.stream()
                .filter(s -> s.type() == ApmDemoScenarioType.GC_LATENCY)
                .mapToDouble(s -> 96.0)
                .findFirst()
                .orElse(32.0);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
