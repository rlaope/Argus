package io.argus.aggregator.store;

import io.argus.aggregator.model.AlertEvent;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.TileMetrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store mapping {@code podId} → {@link PodTarget} and
 * keeping a per-target {@link PodRingBuffer} of recent metric samples.
 *
 * <p>Also tracks currently-firing alert events keyed by {@code <podId>/<ruleName>}
 * so the alert overlay endpoint can be answered cheaply.
 */
public final class FleetRegistry {

    private final long retentionSeconds;
    private final Map<String, PodTarget> targets = new ConcurrentHashMap<>();
    private final Map<String, PodRingBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<String, TileMetrics> latestMetrics = new ConcurrentHashMap<>();
    private final Map<String, AlertEvent> activeAlerts = new ConcurrentHashMap<>();

    public FleetRegistry(long retentionSeconds) {
        this.retentionSeconds = retentionSeconds;
    }

    /**
     * Registers (or idempotently updates) a target. Returns true when this was
     * a new registration; false when it updated an existing record.
     */
    public RegistrationResult register(String podId, String namespace, String podName,
                                       String deployment, String host, int port) {
        Instant now = Instant.now();
        PodTarget existing = targets.get(podId);
        if (existing != null) {
            PodTarget updated = existing.withAddress(host, port);
            // Refresh other identity fields too if changed
            updated = new PodTarget(podId, namespace, podName,
                    deployment == null ? "" : deployment,
                    host, port,
                    existing.registeredAt(), existing.lastScrapeAt(), existing.scrapeOk());
            targets.put(podId, updated);
            return new RegistrationResult(updated, true);
        }
        PodTarget fresh = new PodTarget(podId, namespace, podName,
                deployment == null ? "" : deployment,
                host, port, now, null, false);
        targets.put(podId, fresh);
        buffers.put(podId, new PodRingBuffer(retentionSeconds));
        return new RegistrationResult(fresh, false);
    }

    /** Removes a target and drops all associated state. */
    public void deregister(String podId) {
        targets.remove(podId);
        buffers.remove(podId);
        latestMetrics.remove(podId);
        clearAlertsForPod(podId);
    }

    public PodTarget get(String podId) {
        return targets.get(podId);
    }

    /** Returns a snapshot of all known targets, ordered by podId. */
    public List<PodTarget> listTargets() {
        List<PodTarget> all = new ArrayList<>(targets.values());
        all.sort((a, b) -> a.podId().compareTo(b.podId()));
        return all;
    }

    public PodRingBuffer buffer(String podId) {
        return buffers.get(podId);
    }

    /** Stores the most recent scrape result for a target. */
    public void recordScrape(String podId, TileMetrics metrics, boolean ok) {
        PodTarget existing = targets.get(podId);
        if (existing == null) return;
        PodTarget updated = existing.withScrape(Instant.now(), ok);
        targets.put(podId, updated);
        if (metrics != null) {
            latestMetrics.put(podId, metrics);
        }
    }

    public TileMetrics latestMetrics(String podId) {
        return latestMetrics.get(podId);
    }

    public int size() {
        return targets.size();
    }

    // ── Alert tracking ──────────────────────────────────────────────────────

    /** Records (or refreshes) a firing alert. */
    public void recordAlert(AlertEvent event) {
        activeAlerts.put(event.alertId(), event);
    }

    /** Clears a specific alert (rule no longer breached). */
    public void clearAlert(String alertId) {
        activeAlerts.remove(alertId);
    }

    /** Returns all active (firing) alerts, ordered by alertId. */
    public List<AlertEvent> activeAlerts() {
        List<AlertEvent> all = new ArrayList<>(activeAlerts.values());
        all.sort((a, b) -> a.alertId().compareTo(b.alertId()));
        return all;
    }

    public List<AlertEvent> activeAlertsForPod(String podId) {
        List<AlertEvent> out = new ArrayList<>();
        for (AlertEvent e : activeAlerts.values()) {
            if (e.podId().equals(podId)) {
                out.add(e);
            }
        }
        out.sort((a, b) -> a.alertId().compareTo(b.alertId()));
        return out;
    }

    public int alertCountForPod(String podId) {
        int n = 0;
        for (AlertEvent e : activeAlerts.values()) {
            if (e.podId().equals(podId)) n++;
        }
        return n;
    }

    private void clearAlertsForPod(String podId) {
        activeAlerts.entrySet().removeIf(e -> e.getValue().podId().equals(podId));
    }

    /** Returns a snapshot map keyed by podId, useful for summary aggregation. */
    public Map<String, TileMetrics> latestMetricsSnapshot() {
        return new HashMap<>(latestMetrics);
    }

    public Collection<PodRingBuffer> allBuffers() {
        return buffers.values();
    }

    public record RegistrationResult(PodTarget target, boolean updated) {}
}
