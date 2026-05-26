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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store mapping {@code podId} → {@link PodTarget} and
 * keeping a per-target {@link PodRingBuffer} of recent metric samples.
 *
 * <p>Mutations on the targets map use {@link ConcurrentHashMap#compute} so
 * read-modify-write is atomic against concurrent scrapes / registrations on
 * the same {@code podId}.
 *
 * <p>Also tracks currently-firing alert events keyed by
 * {@code <podId>/<ruleName>} so the alert overlay endpoint can be answered
 * cheaply.
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
     * Registers (or idempotently updates) a target atomically.
     *
     * <p>When updating an existing target, {@code registeredAt}, the latest
     * {@code lastScrapeAt}, and {@code scrapeOk} are preserved — only the
     * address / identity fields are refreshed. Atomic via {@code compute}.
     */
    public RegistrationResult register(String podId, String namespace, String podName,
                                       String deployment, String host, int port) {
        Instant now = Instant.now();
        String dep = deployment == null ? "" : deployment;
        final boolean[] wasUpdate = {false};
        PodTarget result = targets.compute(podId, (k, prev) -> {
            if (prev == null) {
                buffers.computeIfAbsent(k, x -> new PodRingBuffer(retentionSeconds));
                return new PodTarget(podId, namespace, podName, dep, host, port, now, null, false);
            }
            wasUpdate[0] = true;
            return new PodTarget(
                    podId, namespace, podName, dep, host, port,
                    prev.registeredAt(), prev.lastScrapeAt(), prev.scrapeOk());
        });
        return new RegistrationResult(result, wasUpdate[0]);
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

    /**
     * Atomically stores the most recent scrape result for a target.
     *
     * <p>The {@code targets} update is performed via {@code compute} so it
     * does not race against concurrent registrations. Returns silently if
     * the target was deregistered between scrape kickoff and result arrival.
     */
    public void recordScrape(String podId, TileMetrics metrics, boolean ok) {
        Instant now = Instant.now();
        targets.computeIfPresent(podId, (k, prev) -> prev.withScrape(now, ok));
        if (metrics != null && targets.containsKey(podId)) {
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

    /** Direct lookup by stable alertId (cheap; used by FleetAlertEvaluator). */
    public Optional<AlertEvent> getAlert(String alertId) {
        return Optional.ofNullable(activeAlerts.get(alertId));
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

    /**
     * Computes a {@code podId -> activeAlertCount} map in one pass.
     * Used by {@link io.argus.aggregator.http.SummaryComputer} and
     * {@link io.argus.aggregator.http.TileBuilder#buildWith} to avoid
     * O(targets × alerts) scans when rendering large fleets.
     */
    public Map<String, Integer> alertCountsByPod() {
        Map<String, Integer> out = new HashMap<>();
        for (AlertEvent e : activeAlerts.values()) {
            out.merge(e.podId(), 1, Integer::sum);
        }
        return out;
    }

    /** Computes a {@code podId -> severity-set} map in one pass. */
    public Map<String, java.util.Set<String>> alertSeveritiesByPod() {
        Map<String, java.util.Set<String>> out = new HashMap<>();
        for (AlertEvent e : activeAlerts.values()) {
            out.computeIfAbsent(e.podId(), k -> new java.util.HashSet<>())
               .add(e.severity() == null ? "" : e.severity().toLowerCase());
        }
        return out;
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
