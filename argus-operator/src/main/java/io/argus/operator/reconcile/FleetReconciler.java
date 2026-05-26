package io.argus.operator.reconcile;

import io.argus.operator.crd.ArgusFleet;
import io.argus.operator.crd.ArgusFleetStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Watches {@link ArgusFleet} CRD instances and keeps an in-memory cache that
 * {@link TargetReconciler} reads when materializing per-pod targets.
 */
public class FleetReconciler implements ResourceEventHandler<ArgusFleet> {

    private static final Logger LOG = LoggerFactory.getLogger(FleetReconciler.class);

    private final KubernetesClient client;
    private final ConcurrentMap<String, ArgusFleet> fleetsByKey = new ConcurrentHashMap<>();

    public FleetReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public void onAdd(ArgusFleet fleet) {
        String key = key(fleet);
        fleetsByKey.put(key, fleet);
        LOG.info("ArgusFleet added: {} aggregator={} interval={}s",
                key,
                fleet.getSpec() == null ? "?" : fleet.getSpec().getAggregatorEndpoint(),
                fleet.getSpec() == null ? -1 : fleet.getSpec().getScrapeIntervalSeconds());
        updateStatus(fleet, "Ready", null);
    }

    @Override
    public void onUpdate(ArgusFleet oldFleet, ArgusFleet newFleet) {
        String key = key(newFleet);
        fleetsByKey.put(key, newFleet);
        LOG.info("ArgusFleet updated: {}", key);
        updateStatus(newFleet, "Ready", null);
    }

    @Override
    public void onDelete(ArgusFleet fleet, boolean deletedFinalStateUnknown) {
        String key = key(fleet);
        fleetsByKey.remove(key);
        LOG.info("ArgusFleet deleted: {} (finalStateUnknown={})", key, deletedFinalStateUnknown);
    }

    public ArgusFleet fleetFor(String namespace) {
        for (ArgusFleet f : fleetsByKey.values()) {
            if (namespace.equals(f.getMetadata().getNamespace())) {
                return f;
            }
        }
        return fleetsByKey.values().stream().findFirst().orElse(null);
    }

    public int knownFleetCount() {
        return fleetsByKey.size();
    }

    private static String key(ArgusFleet fleet) {
        return fleet.getMetadata().getNamespace() + "/" + fleet.getMetadata().getName();
    }

    private void updateStatus(ArgusFleet fleet, String phase, String message) {
        try {
            ArgusFleetStatus status = fleet.getStatus() == null ? new ArgusFleetStatus() : fleet.getStatus();
            status.setPhase(phase);
            status.setMessage(message);
            status.setLastReconcileAt(Instant.now().toString());
            fleet.setStatus(status);
            client.resources(ArgusFleet.class)
                    .inNamespace(fleet.getMetadata().getNamespace())
                    .resource(fleet)
                    .replaceStatus();
        } catch (Exception e) {
            LOG.debug("Failed to update ArgusFleet status (will retry on next reconcile): {}", e.getMessage());
        }
    }
}
