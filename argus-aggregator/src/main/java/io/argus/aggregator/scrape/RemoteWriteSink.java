package io.argus.aggregator.scrape;

import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.TileMetrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional Prometheus {@code remote_write}-style sink. When disabled (the
 * default, by passing {@code null} or an empty URL to the constructor) it is a
 * pure no-op and introduces zero background threads or HTTP traffic.
 *
 * <p>This is intentionally a simple JSON line-protocol push (one POST per
 * scrape result), not the full Prometheus protobuf remote_write spec. The
 * receiver is expected to be an argus-side or HTTP-compatible relay; full
 * protobuf remote_write can be added later without changing the public API.
 */
public final class RemoteWriteSink {

    private static final System.Logger LOG = System.getLogger(RemoteWriteSink.class.getName());

    private final String url;
    private final boolean enabled;
    private final HttpClient client;
    private final LinkedBlockingQueue<String> queue;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong dropped = new AtomicLong(0);
    private final AtomicLong sent = new AtomicLong(0);

    public RemoteWriteSink(String url) {
        this.url = url;
        this.enabled = url != null && !url.isBlank();
        if (enabled) {
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            this.queue = new LinkedBlockingQueue<>(4096);
            this.worker = new Thread(this::drainLoop, "argus-aggregator-remote-write");
            this.worker.setDaemon(true);
        } else {
            this.client = null;
            this.queue = null;
            this.worker = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long droppedCount() { return dropped.get(); }
    public long sentCount() { return sent.get(); }

    public void start() {
        if (!enabled) return;
        if (running.compareAndSet(false, true)) {
            worker.start();
        }
    }

    public void stop() {
        if (!enabled) return;
        if (running.compareAndSet(true, false)) {
            worker.interrupt();
        }
    }

    /** Enqueues a sample for remote write. Drops silently when full. */
    public void offer(PodTarget target, TileMetrics metrics) {
        if (!enabled || metrics == null || target == null) return;
        String payload = buildPayload(target, metrics);
        if (!queue.offer(payload)) {
            dropped.incrementAndGet();
        }
    }

    private void drainLoop() {
        while (running.get()) {
            try {
                String payload = queue.take();
                send(payload);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "remote_write failed", t);
            }
        }
    }

    private void send(String payload) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                LOG.log(System.Logger.Level.DEBUG, () -> "remote_write got " + resp.statusCode());
            } else {
                sent.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "remote_write error: " + e.getMessage());
        }
    }

    private static String buildPayload(PodTarget target, TileMetrics metrics) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
          .append("\"podId\":\"").append(target.podId()).append("\",")
          .append("\"namespace\":\"").append(target.namespace()).append("\",")
          .append("\"deployment\":\"").append(target.deployment()).append("\",")
          .append("\"heapPercent\":").append(orNull(metrics.heapPercent())).append(',')
          .append("\"gcOverheadPercent\":").append(orNull(metrics.gcOverheadPercent())).append(',')
          .append("\"cpuPercent\":").append(orNull(metrics.cpuPercent())).append(',')
          .append("\"activeVThreads\":").append(metrics.activeVThreads()).append(',')
          .append("\"leakSuspected\":").append(metrics.leakSuspected())
          .append('}');
        return sb.toString();
    }

    private static String orNull(Double v) {
        return v == null ? "null" : v.toString();
    }
}
