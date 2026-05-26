package io.argus.aggregator.scrape;

import io.argus.aggregator.model.MetricSample;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.store.FleetRegistry;
import io.argus.aggregator.store.PodRingBuffer;
import io.argus.core.cluster.PrometheusTextParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodically pulls metrics from every registered {@link PodTarget} and feeds
 * the result into {@link FleetRegistry} and the alert evaluation pipeline.
 *
 * <p>Per scrape cycle the loop hits each target's {@code /prometheus} endpoint
 * over HTTP. The optional {@code /gc-analysis} and {@code /doctor} endpoints
 * are surfaced via drill-down on first request, so they are not pulled here on
 * every cycle (saves bandwidth at fleet scale). They are fetched lazily by
 * higher-level handlers when a drill-down is requested.
 */
public final class ScrapeLoop {

    private static final System.Logger LOG = System.getLogger(ScrapeLoop.class.getName());

    private final FleetRegistry registry;
    private final long intervalSeconds;
    private final HttpClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private final AtomicLong scrapeCount = new AtomicLong(0);
    private final AtomicLong scrapeErrors = new AtomicLong(0);
    private final java.util.function.Consumer<ScrapeResult> onScrape;

    public ScrapeLoop(FleetRegistry registry, long intervalSeconds,
                      java.util.function.Consumer<ScrapeResult> onScrape) {
        this.registry = registry;
        this.intervalSeconds = intervalSeconds;
        this.onScrape = onScrape;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "argus-aggregator-scrape");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::scrapeOnce, 1, intervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public long scrapeCount() { return scrapeCount.get(); }
    public long scrapeErrors() { return scrapeErrors.get(); }
    public long intervalSeconds() { return intervalSeconds; }

    private void scrapeOnce() {
        try {
            List<PodTarget> targets = registry.listTargets();
            for (PodTarget t : targets) {
                scrapeTarget(t);
            }
        } catch (Throwable th) {
            LOG.log(System.Logger.Level.WARNING, "scrape cycle failed", th);
        }
    }

    private void scrapeTarget(PodTarget target) {
        Instant start = Instant.now();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(target.scrapeUrl() + "/prometheus"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                onFailure(target, "HTTP " + resp.statusCode());
                return;
            }
            Map<String, Double> parsed = PrometheusTextParser.parse(resp.body());
            TileMetrics metrics = MetricsMapper.map(parsed);
            registry.recordScrape(target.podId(), metrics, true);
            PodRingBuffer buf = registry.buffer(target.podId());
            if (buf != null) {
                buf.append(new MetricSample(start, metrics.heapPercent(), metrics.gcOverheadPercent(),
                        metrics.cpuPercent(), metrics.activeVThreads()));
            }
            scrapeCount.incrementAndGet();
            if (onScrape != null) {
                onScrape.accept(new ScrapeResult(target.podId(), metrics, parsed, true, null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            onFailure(target, e.getMessage());
        }
    }

    private void onFailure(PodTarget target, String reason) {
        scrapeErrors.incrementAndGet();
        registry.recordScrape(target.podId(), null, false);
        if (onScrape != null) {
            onScrape.accept(new ScrapeResult(target.podId(), null, null, false, reason));
        }
        LOG.log(System.Logger.Level.DEBUG, () -> "scrape failed for " + target.podId() + ": " + reason);
    }

    public record ScrapeResult(String podId, TileMetrics metrics, Map<String, Double> rawMetrics,
                               boolean ok, String error) {}
}
