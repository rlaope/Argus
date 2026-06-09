package io.argus.apm.metrics;

import java.util.concurrent.atomic.AtomicLong;

public final class ApmSelfMetrics {
    private final AtomicLong facadeRequests = new AtomicLong();
    private final AtomicLong facadeErrors = new AtomicLong();
    private final AtomicLong authorizationDenied = new AtomicLong();
    private final AtomicLong routeRejected = new AtomicLong();
    private final AtomicLong backendLinksGenerated = new AtomicLong();
    private final AtomicLong totalLatencyMillis = new AtomicLong();

    public void recordFacadeRequest(long latencyMillis, boolean error) {
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must be non-negative");
        }
        facadeRequests.incrementAndGet();
        totalLatencyMillis.addAndGet(latencyMillis);
        if (error) {
            facadeErrors.incrementAndGet();
        }
    }

    public void recordAuthorizationDenied() {
        authorizationDenied.incrementAndGet();
    }

    public void recordRouteRejected() {
        routeRejected.incrementAndGet();
    }

    public void recordBackendLinksGenerated(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        backendLinksGenerated.addAndGet(count);
    }

    public Snapshot snapshot() {
        long requests = facadeRequests.get();
        return new Snapshot(
                requests,
                facadeErrors.get(),
                authorizationDenied.get(),
                routeRejected.get(),
                backendLinksGenerated.get(),
                requests == 0 ? 0.0 : (double) totalLatencyMillis.get() / requests
        );
    }

    public String toPrometheusText() {
        Snapshot s = snapshot();
        return "# TYPE argus_apm_facade_requests_total counter\n"
                + "argus_apm_facade_requests_total " + s.facadeRequests() + "\n"
                + "# TYPE argus_apm_facade_errors_total counter\n"
                + "argus_apm_facade_errors_total " + s.facadeErrors() + "\n"
                + "# TYPE argus_apm_authorization_denied_total counter\n"
                + "argus_apm_authorization_denied_total " + s.authorizationDenied() + "\n"
                + "# TYPE argus_apm_route_rejected_total counter\n"
                + "argus_apm_route_rejected_total " + s.routeRejected() + "\n"
                + "# TYPE argus_apm_backend_links_generated_total counter\n"
                + "argus_apm_backend_links_generated_total " + s.backendLinksGenerated() + "\n"
                + "# TYPE argus_apm_facade_latency_avg_ms gauge\n"
                + "argus_apm_facade_latency_avg_ms " + s.averageLatencyMillis() + "\n";
    }

    public record Snapshot(
            long facadeRequests,
            long facadeErrors,
            long authorizationDenied,
            long routeRejected,
            long backendLinksGenerated,
            double averageLatencyMillis
    ) {}
}
