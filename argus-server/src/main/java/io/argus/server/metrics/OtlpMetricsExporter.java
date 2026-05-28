package io.argus.server.metrics;

import io.argus.core.config.AgentConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes metrics to an OpenTelemetry collector in OTLP JSON format.
 *
 * <p>Uses {@link java.net.http.HttpClient} (JDK built-in) to POST metrics
 * at a configurable interval. No external SDK dependency required.
 *
 * <p>Beyond metrics, the exporter also flushes a queue of significant
 * JVM-internal event spans (GC pauses) to the OTLP/HTTP {@code /v1/traces}
 * endpoint on the same cadence. Spans are enqueued via {@link #enqueueGcPauseSpan}
 * and encoded with {@link OtlpSpanBuilder}; when no spans are queued, no traces
 * request is made. Both metric and span export are gated by the OTLP enable flag
 * at construction time in {@code ArgusServer}.
 */
public final class OtlpMetricsExporter {

    private static final System.Logger LOG = System.getLogger(OtlpMetricsExporter.class.getName());

    /** Bound the span queue so a slow/absent collector can't grow it without limit. */
    private static final int MAX_PENDING_SPANS = 512;

    private final AgentConfig config;
    private final OtlpJsonBuilder jsonBuilder;
    private final OtlpSpanBuilder spanBuilder;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentLinkedQueue<PendingSpan> pendingSpans = new ConcurrentLinkedQueue<>();
    /** O(1) size tracking — ConcurrentLinkedQueue.size() is O(n). */
    private final java.util.concurrent.atomic.AtomicInteger pendingSpanCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public OtlpMetricsExporter(AgentConfig config, OtlpJsonBuilder jsonBuilder) {
        this.config = config;
        this.jsonBuilder = jsonBuilder;
        this.spanBuilder = new OtlpSpanBuilder(config.getOtlpServiceName(),
                io.argus.server.metrics.OtlpMetricsExporter.class.getPackage().getImplementationVersion());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "argus-otlp-exporter"); t.setDaemon(true); return t; }
        );
    }

    /**
     * Queues a GC-pause span for the next traces flush. The active W3C trace id
     * (or {@code null}) is captured by the caller at pause time so the span can
     * be linked to in-flight work. No-op beyond the queue cap.
     */
    public void enqueueGcPauseSpan(long endTimeMillis, String gcName, String gcCause,
                                   double pauseMs, long reclaimedBytes, String traceId) {
        pendingSpans.add(new PendingSpan(endTimeMillis, gcName, gcCause, pauseMs, reclaimedBytes, traceId));
        int count = pendingSpanCount.incrementAndGet();
        // Drop OLDEST on overflow (not newest): during an incident the most recent
        // pauses are the most relevant, so evict from the head instead of rejecting.
        while (count > MAX_PENDING_SPANS && pendingSpans.poll() != null) {
            count = pendingSpanCount.decrementAndGet();
        }
    }

    /**
     * Starts the periodic OTLP metrics push.
     */
    public void start() {
        long intervalMs = config.getOtlpIntervalMs();
        scheduler.scheduleAtFixedRate(this::pushMetrics, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::pushSpans, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
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
        post(config.getOtlpEndpoint(), jsonBuilder.build(), "metrics");
    }

    /**
     * Drains the pending GC-pause spans, encodes them into one OTLP/JSON traces
     * payload, and POSTs to the {@code /v1/traces} endpoint. No-op when the
     * queue is empty so an idle JVM never emits empty traces requests.
     */
    private void pushSpans() {
        List<PendingSpan> batch = new ArrayList<>();
        PendingSpan s;
        while ((s = pendingSpans.poll()) != null) {
            pendingSpanCount.decrementAndGet();
            batch.add(s);
            if (batch.size() >= MAX_PENDING_SPANS) {
                break;
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        post(tracesEndpoint(), encodeSpanBatch(batch), "traces");
    }

    /** Encodes a batch of GC-pause spans into a single OTLP/JSON resourceSpans payload. */
    String encodeSpanBatch(List<PendingSpan> batch) {
        if (batch.size() == 1) {
            PendingSpan p = batch.get(0);
            return spanBuilder.buildGcPauseSpan(p.endTimeMillis(), p.gcName(), p.gcCause(),
                    p.pauseMs(), p.reclaimedBytes(), p.traceId());
        }
        // Multiple spans share one resourceSpans/scopeSpans wrapper.
        StringBuilder sb = new StringBuilder(256 + batch.size() * 256);
        sb.append("{\"resourceSpans\":[{");
        // Reuse the builder's resource by delegating a single-span build, then splice.
        // Simpler: build the wrapper inline mirroring OtlpSpanBuilder's resource.
        sb.append("\"resource\":{\"attributes\":[");
        sb.append("{\"key\":\"service.name\",\"value\":{\"stringValue\":\"")
                .append(jsonEscape(config.getOtlpServiceName())).append("\"}},");
        sb.append("{\"key\":\"telemetry.sdk.name\",\"value\":{\"stringValue\":\"argus\"}},");
        sb.append("{\"key\":\"telemetry.sdk.language\",\"value\":{\"stringValue\":\"java\"}}");
        sb.append("]},\"scopeSpans\":[{");
        sb.append("\"scope\":{\"name\":\"io.argus.tracing\"},\"spans\":[");
        boolean first = true;
        for (PendingSpan p : batch) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            long endNano = p.endTimeMillis() * 1_000_000L;
            long startNano = endNano - (long) (p.pauseMs() * 1_000_000.0);
            spanBuilder.appendGcPauseSpanObject(sb, p.traceId(), null, startNano, endNano,
                    p.gcName(), p.gcCause(), p.pauseMs(), p.reclaimedBytes());
        }
        sb.append("]}]}]}");
        return sb.toString();
    }

    /**
     * Derives the OTLP/HTTP traces endpoint from the configured metrics endpoint
     * by swapping the trailing {@code /v1/metrics} signal path for
     * {@code /v1/traces}, per the OTLP/HTTP convention. Falls back to appending
     * {@code /v1/traces} when no recognizable signal suffix is present.
     */
    String tracesEndpoint() {
        String endpoint = config.getOtlpEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return endpoint;
        }
        if (endpoint.endsWith("/v1/metrics")) {
            return endpoint.substring(0, endpoint.length() - "/v1/metrics".length()) + "/v1/traces";
        }
        if (endpoint.contains("/v1/traces")) {
            return endpoint;
        }
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return base + "/v1/traces";
    }

    private void post(String endpoint, String json, String signal) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
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
                        "OTLP {0} push failed: HTTP {1} - {2}", signal, response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "OTLP {0} push error: {1}", signal, e.getMessage());
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** A GC-pause span awaiting the next traces flush. */
    record PendingSpan(long endTimeMillis, String gcName, String gcCause,
                       double pauseMs, long reclaimedBytes, String traceId) {
    }
}
