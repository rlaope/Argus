package io.argus.cli.llm;

/**
 * Pluggable provider for the opt-in LLM root-cause surface.
 *
 * <p>The default implementation ({@link HttpLlmProvider}) talks to a
 * provider-neutral chat endpoint over the JDK {@code HttpClient}. Tests inject a
 * stub so no real network call is ever made.
 *
 * <p>Implementations MUST NOT be invoked unless the feature is explicitly
 * enabled and a key is present — that gate lives in {@link LlmRootCause}.
 */
public interface LlmProvider {

    /**
     * Sends a system instruction plus a user prompt and returns the model's
     * advisory text.
     *
     * @param systemInstruction grounding instruction (findings-only constraint)
     * @param userPrompt        the serialized findings payload
     * @return the model's advisory response text
     * @throws LlmException if the call fails
     */
    String complete(String systemInstruction, String userPrompt) throws LlmException;

    /** A short human-readable name for diagnostics/labelling (e.g. "anthropic"). */
    String name();
}
