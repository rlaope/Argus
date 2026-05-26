package io.argus.core.alert;

import io.argus.core.net.HostAllowlist;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Sends a JSON alert payload to a configured webhook URL via HTTP POST.
 *
 * <p>Payload format:
 * <pre>
 * {
 *   "alert":     "ArgusGCOverhead",
 *   "severity":  "warning",
 *   "value":     12.3,
 *   "threshold": 10.0,
 *   "instance":  "localhost:9202",
 *   "timestamp": "2024-01-01T14:24:01Z"
 * }
 * </pre>
 */
public final class WebhookSender {

    private final HttpClient client;

    public WebhookSender() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Posts an alert notification to the webhook URL of the given rule.
     * Failures are logged to stderr but do not throw.
     *
     * <p>SECURITY: validates scheme is {@code http|https} and host is not
     * loopback / link-local / IMDS via {@link HostAllowlist}. A malformed or
     * forbidden URL is logged and dropped.
     */
    public void send(AlertRule rule, double value, String instance) {
        if (rule.webhookUrl() == null || rule.webhookUrl().isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = new URI(rule.webhookUrl());
        } catch (URISyntaxException e) {
            System.err.println("[alert] reject webhook: invalid URI for rule '" + rule.name() + "'");
            return;
        }
        String reason = HostAllowlist.rejectionReasonForUri(uri);
        if (reason != null) {
            System.err.println("[alert] reject webhook: " + reason + " for rule '" + rule.name() + "'");
            return;
        }
        String alertName = "Argus" + toTitleCase(rule.name());
        String timestamp = Instant.now().toString();
        String json = "{"
                + "\"alert\":\"" + escapeJson(alertName) + "\","
                + "\"severity\":\"" + escapeJson(rule.severity()) + "\","
                + "\"value\":" + value + ","
                + "\"threshold\":" + rule.threshold() + ","
                + "\"instance\":\"" + escapeJson(instance) + "\","
                + "\"timestamp\":\"" + timestamp + "\""
                + "}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("[alert] Webhook returned HTTP " + response.statusCode()
                        + " for rule '" + rule.name() + "'");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[alert] Webhook delivery failed for rule '" + rule.name()
                    + "': " + e.getMessage());
        }
    }

    private static String toTitleCase(String s) {
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : s.toCharArray()) {
            if (c == '-' || c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
