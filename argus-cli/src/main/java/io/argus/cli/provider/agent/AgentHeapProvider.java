package io.argus.cli.provider.agent;

import io.argus.cli.model.HeapResult;
import io.argus.cli.provider.HeapProvider;

import java.util.Map;

/**
 * HeapProvider that fetches heap data from the Argus agent's {@code /gc-analysis} endpoint.
 *
 * <p>The agent endpoint exposes current heap used and committed, but does not provide
 * per-space breakdown. Use {@code JdkHeapProvider} when per-space detail is needed.
 */
public final class AgentHeapProvider implements HeapProvider {

    private final AgentClient client;

    public AgentHeapProvider(AgentClient client) {
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
    public HeapResult getHeapInfo(long pid) {
        String json = client.fetch("/gc-analysis");
        if (json == null || json.isEmpty()) {
            return new HeapResult(0L, 0L, 0L, Map.of());
        }

        long used = AgentClient.jsonLong(json, "currentHeapUsed");
        long committed = AgentClient.jsonLong(json, "currentHeapCommitted");

        // Agent does not expose max heap; use committed as the best available approximation
        return new HeapResult(used, committed, committed, Map.of());
    }
}
