package io.argus.cli.provider.agent;

import io.argus.cli.model.GcResult;
import io.argus.cli.provider.GcProvider;

import java.util.List;

/**
 * GcProvider that fetches GC data from the Argus agent's {@code /gc-analysis} endpoint.
 */
public final class AgentGcProvider implements GcProvider {

    private final AgentClient client;

    public AgentGcProvider(AgentClient client) {
        this.client = client;
    }

    @Override
    public boolean isAvailable(long pid) {
        return client.isReachable();
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public String source() {
        return "agent";
    }

    @Override
    public GcResult getGcInfo(long pid) {
        String json = client.fetch("/gc-analysis");
        if (json == null || json.isEmpty()) {
            return new GcResult(0L, 0.0, 0.0, "", 0L, 0L, List.of());
        }

        long totalEvents = AgentClient.jsonLong(json, "totalGCEvents");
        double totalPauseMs = AgentClient.jsonDouble(json, "totalPauseTimeMs");
        double overheadPercent = AgentClient.jsonDouble(json, "gcOverheadPercent");
        String lastCause = AgentClient.jsonString(json, "lastGCCause");
        long heapUsed = AgentClient.jsonLong(json, "currentHeapUsed");
        long heapCommitted = AgentClient.jsonLong(json, "currentHeapCommitted");

        return new GcResult(
                totalEvents,
                totalPauseMs,
                overheadPercent,
                lastCause != null ? lastCause : "",
                heapUsed,
                heapCommitted,
                List.of()   // per-collector breakdown not available from agent endpoint
        );
    }
}
