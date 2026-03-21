package io.argus.cli.provider.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Simple HTTP client for communicating with the Argus agent server.
 *
 * <p>Uses only {@code java.net.http.HttpClient} — no external libraries.
 * JSON parsing is done via manual string extraction helpers.
 */
public final class AgentClient {

    private static final int DEFAULT_PORT = 9202;
    private static final String DEFAULT_HOST = "localhost";

    private final String baseUrl;
    private final HttpClient httpClient;
    private Boolean reachableCache;

    public AgentClient() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public AgentClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Performs a GET request to the given path and returns the response body.
     *
     * @param path the URL path (e.g. "/health")
     * @return the response body string, or {@code null} on failure
     */
    public String fetch(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks whether the Argus agent is reachable by probing {@code /health}.
     *
     * @return true if the agent responds with HTTP 200
     */
    public boolean isReachable() {
        if (reachableCache != null) return reachableCache;
        boolean result;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            result = response.statusCode() == 200;
        } catch (Exception e) {
            result = false;
        }
        reachableCache = result;
        return result;
    }

    // -------------------------------------------------------------------------
    // Manual JSON value parsers — no external library
    // -------------------------------------------------------------------------

    public static long jsonLong(String json, String key) {
        String val = extractJsonValue(json, key);
        if (val == null || val.isEmpty()) return 0L;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static double jsonDouble(String json, String key) {
        String val = extractJsonValue(json, key);
        if (val == null || val.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static int jsonInt(String json, String key) {
        return (int) jsonLong(json, key);
    }

    public static String jsonString(String json, String key) {
        if (json == null || json.isEmpty()) return null;
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }

    private static String extractJsonValue(String json, String key) {
        if (json == null || json.isEmpty()) return null;
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        if (start >= json.length()) return null;

        char first = json.charAt(start);
        if (first == '"') {
            int end = json.indexOf('"', start + 1);
            return end > start ? json.substring(start + 1, end) : null;
        }

        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || c == ']') break;
            end++;
        }
        return json.substring(start, end).trim();
    }
}
