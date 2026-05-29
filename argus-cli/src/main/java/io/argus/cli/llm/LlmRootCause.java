package io.argus.cli.llm;

import io.argus.diagnostics.doctor.Finding;

import java.util.List;

/**
 * Orchestrates the opt-in LLM root-cause surface.
 *
 * <p>This is the single enforcement point for the gate. {@link #analyze} returns
 * a {@link Result} that ALWAYS carries the deterministic findings; the advisory
 * LLM narrative is attached only when the feature is activatable and the call
 * succeeds. When disabled, no provider is constructed and no call is made.
 */
public final class LlmRootCause {

    /** Outcome of a root-cause attempt. Findings are always present. */
    public static final class Result {
        private final List<Finding> findings;
        private final String advisory;     // null when no LLM narrative
        private final String providerName; // null when disabled/failed
        private final String skippedReason; // null when an advisory was produced

        private Result(List<Finding> findings, String advisory, String providerName, String skippedReason) {
            this.findings = findings;
            this.advisory = advisory;
            this.providerName = providerName;
            this.skippedReason = skippedReason;
        }

        public List<Finding> findings() { return findings; }
        public boolean hasAdvisory() { return advisory != null; }
        public String advisory() { return advisory; }
        public String providerName() { return providerName; }
        public String skippedReason() { return skippedReason; }
    }

    private final LlmConfig config;
    private final ProviderFactory factory;

    /** Allows tests to inject a stub provider instead of the HTTP one. */
    @FunctionalInterface
    public interface ProviderFactory {
        LlmProvider create(LlmConfig config);
    }

    public LlmRootCause(LlmConfig config) {
        this(config, HttpLlmProvider::new);
    }

    public LlmRootCause(LlmConfig config, ProviderFactory factory) {
        this.config = config;
        this.factory = factory;
    }

    /**
     * Produces a root-cause result. The provider is constructed and invoked ONLY
     * when {@link LlmConfig#isActivatable()} is true. Any provider failure is
     * caught and reported as a skip reason — the findings still flow through.
     */
    public Result analyze(List<Finding> findings) {
        if (!config.isActivatable()) {
            return new Result(findings, null, null,
                    "LLM disabled (set argus.llm.enabled=true and ARGUS_LLM_API_KEY to enable)");
        }
        LlmProvider provider = factory.create(config);
        try {
            String advisory = provider.complete(
                    FindingsPrompt.SYSTEM_INSTRUCTION,
                    FindingsPrompt.buildPrompt(findings));
            return new Result(findings, advisory, provider.name(), null);
        } catch (LlmException e) {
            return new Result(findings, null, provider.name(),
                    "LLM call failed: " + e.getMessage());
        }
    }
}
