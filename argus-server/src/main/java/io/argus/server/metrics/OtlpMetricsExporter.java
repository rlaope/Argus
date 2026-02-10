package io.argus.server.metrics;

import io.argus.core.config.AgentConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes metrics to an OpenTelemetry collector in OTLP JSON format.
 *
 * <p>Uses {@link java.net.http.HttpClient} (JDK built-in) to POST metrics
 * at a configurable interval. No external SDK dependency required.
 */
public final class OtlpMetricsExporter {

    private static final System.Logger LOG = System.getLogger(OtlpMetricsExporter.class.getName());

    private final AgentConfig config;
    private final OtlpJsonBuilder jsonBuilder;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    public OtlpMetricsExporter(AgentConfig config, OtlpJsonBuilder jsonBuilder) {
        this.config = config;
        this.jsonBuilder = jsonBuilder;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("argus-otlp-exporter").daemon(true).unstarted(r)
        );
    }

    /**
     * Starts the periodic OTLP metrics push.
     */
    public void start() {
        long intervalMs = config.getOtlpIntervalMs();
        scheduler.scheduleAtFixedRate(this::pushMetrics, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        LOG.log(System.Logger.Level.INFO, "OTLP exporter started (endpoint: {0}, interval: {1}ms)",
                config.getOtlpEndpoint(), intervalMs);
    }

    /**
     * Stops the OTLP metrics exporter.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.log(System.Logger.Level.INFO, "OTLP exporter stopped");
    }

    private void pushMetrics() {
        try {
            String json = jsonBuilder.build();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(config.getOtlpEndpoint()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            // Add custom headers (e.g., auth tokens)
            String headers = config.getOtlpHeaders();
            if (headers != null && !headers.isBlank()) {
                for (String header : headers.split(",")) {
                    int eq = header.indexOf('=');
                    if (eq > 0) {
                        String key = header.substring(0, eq).trim();
                        String value = header.substring(eq + 1).trim();
                        requestBuilder.header(key, value);
                    }
                }
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                LOG.log(System.Logger.Level.WARNING,
                        "OTLP push failed: HTTP {0} - {1}", response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "OTLP push error: {0}", e.getMessage());
        }
    }
}
