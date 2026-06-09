package io.argus.apm.demo;

import io.argus.apm.model.ApmIncident;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceDetail;
import io.argus.apm.model.ApmTraceContext;

import java.util.List;
import java.util.Objects;

public record ApmDemoTopology(
        ApmScope scope,
        List<ApmServiceDetail> services,
        List<ApmDemoScenario> scenarios
) {
    public ApmDemoTopology {
        Objects.requireNonNull(scope, "scope");
        services = List.copyOf(Objects.requireNonNull(services, "services"));
        scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
    }

    public List<ApmIncident> incidents() {
        return scenarios.stream().map(ApmDemoScenario::incident).toList();
    }

    public List<ApmTraceContext> traces() {
        return scenarios.stream().map(ApmDemoScenario::trace).toList();
    }
}
