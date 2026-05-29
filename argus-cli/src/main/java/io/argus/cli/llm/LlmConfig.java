package io.argus.cli.llm;

/**
 * Configuration gate for the opt-in LLM root-cause surface.
 *
 * <p><b>Default OFF.</b> The feature is disabled unless BOTH of these hold:
 * <ul>
 *   <li>{@code argus.llm.enabled=true} is set (system property or
 *       {@code ARGUS_LLM_ENABLED=true} env var), and</li>
 *   <li>an API key is present in {@code ARGUS_LLM_API_KEY}.</li>
 * </ul>
 *
 * <p>When disabled, {@link LlmRootCause} never constructs or invokes a provider,
 * so there is ZERO network activity. Bring-your-own-key: the key is read only
 * from the environment and is never persisted by Argus.
 */
public final class LlmConfig {

    /** Provider-neutral chat-completions endpoint (OpenAI-compatible by default). */
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final boolean enabled;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    public LlmConfig(boolean enabled, String apiKey, String endpoint, String model) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.model = model;
    }

    public boolean enabled() { return enabled; }
    public String apiKey() { return apiKey; }
    public String endpoint() { return endpoint; }
    public String model() { return model; }

    /**
     * True only when the feature flag is on AND a non-blank key is present.
     * This is the single condition that may trigger a network call.
     */
    public boolean isActivatable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** Reads the gate from system properties and environment variables. */
    public static LlmConfig fromEnvironment() {
        boolean enabled = readFlag();
        String key = trimToNull(System.getenv("ARGUS_LLM_API_KEY"));
        String endpoint = orDefault(System.getenv("ARGUS_LLM_ENDPOINT"), DEFAULT_ENDPOINT);
        String model = orDefault(System.getenv("ARGUS_LLM_MODEL"), DEFAULT_MODEL);
        return new LlmConfig(enabled, key, endpoint, model);
    }

    private static boolean readFlag() {
        String prop = System.getProperty("argus.llm.enabled");
        if (prop != null) {
            return Boolean.parseBoolean(prop.trim());
        }
        return Boolean.parseBoolean(orDefault(System.getenv("ARGUS_LLM_ENABLED"), "false").trim());
    }

    private static String orDefault(String value, String def) {
        String v = trimToNull(value);
        return v != null ? v : def;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
