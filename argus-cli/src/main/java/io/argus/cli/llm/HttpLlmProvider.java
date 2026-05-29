package io.argus.cli.llm;

import io.argus.cli.render.RichRenderer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Default {@link LlmProvider} implementation built on the JDK {@link HttpClient}.
 *
 * <p>Targets a provider-neutral OpenAI-compatible chat-completions endpoint (the
 * same wire shape is accepted by OpenAI, Azure OpenAI, and many self-hosted
 * gateways). No third-party SDK is required.
 *
 * <p>This class is only constructed by {@link LlmRootCause} after the
 * {@link LlmConfig} gate confirms the feature is enabled and a key is present,
 * so instantiating it implies the user has explicitly opted in.
 */
public final class HttpLlmProvider implements LlmProvider {

    private final LlmConfig config;
    private final HttpClient client;

    public HttpLlmProvider(LlmConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public String complete(String systemInstruction, String userPrompt) throws LlmException {
        String body = "{"
                + "\"model\":\"" + RichRenderer.escapeJson(config.model()) + "\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + RichRenderer.escapeJson(systemInstruction) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + RichRenderer.escapeJson(userPrompt) + "\"}"
                + "],\"temperature\":0}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LlmException("LLM request failed: " + e.getMessage(), e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new LlmException("LLM endpoint returned HTTP " + response.statusCode());
        }

        String content = extractContent(response.body());
        if (content == null || content.isBlank()) {
            throw new LlmException("LLM response did not contain any content");
        }
        return content;
    }

    /**
     * Extracts {@code choices[0].message.content} from an OpenAI-compatible
     * response using a minimal scan, avoiding a JSON dependency.
     */
    static String extractContent(String json) {
        if (json == null) return null;
        int contentKey = json.indexOf("\"content\"");
        if (contentKey < 0) return null;
        int colon = json.indexOf(':', contentKey);
        if (colon < 0) return null;
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = quote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(next); break;
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
