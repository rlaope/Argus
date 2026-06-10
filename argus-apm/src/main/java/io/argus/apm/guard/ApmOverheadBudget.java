package io.argus.apm.guard;

public record ApmOverheadBudget(
        int maxServices,
        int maxEndpointsPerService,
        int maxFindingsPerTrace,
        int maxBackendLinksPerRequest
) {
    public ApmOverheadBudget {
        requirePositive(maxServices, "maxServices");
        requirePositive(maxEndpointsPerService, "maxEndpointsPerService");
        requirePositive(maxFindingsPerTrace, "maxFindingsPerTrace");
        requirePositive(maxBackendLinksPerRequest, "maxBackendLinksPerRequest");
    }

    public static ApmOverheadBudget defaults() {
        return new ApmOverheadBudget(500, 200, 50, 12);
    }

    public ApmGuardrailDecision validate(int services, int endpointsPerService,
                                         int findingsPerTrace, int backendLinksPerRequest) {
        if (services > maxServices) {
            return ApmGuardrailDecision.deny("services_limit", "service count exceeds APM facade budget");
        }
        if (endpointsPerService > maxEndpointsPerService) {
            return ApmGuardrailDecision.deny("endpoints_limit", "endpoint cardinality exceeds service budget");
        }
        if (findingsPerTrace > maxFindingsPerTrace) {
            return ApmGuardrailDecision.deny("findings_limit", "trace finding fanout exceeds budget");
        }
        if (backendLinksPerRequest > maxBackendLinksPerRequest) {
            return ApmGuardrailDecision.deny("backend_links_limit", "backend link fanout exceeds budget");
        }
        return ApmGuardrailDecision.allow();
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
