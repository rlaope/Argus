package io.argus.operator.reconcile;

import io.argus.operator.crd.ArgusFleet;
import io.argus.operator.crd.ArgusFleetSpec;
import io.argus.operator.crd.ArgusFleetStatus;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /**
     * Returns the fleet whose {@link ArgusFleetSpec#getNamespaceSelector()} matches
     * the given pod namespace, OR a fleet in the same namespace if no selector is
     * configured. Never returns a cross-namespace fallback — K8s rejects OwnerReferences
     * across namespaces, and silently picking an arbitrary fleet for an unmatched namespace
     * caused subtle drift in pre-alpha builds.
     */
    public ArgusFleet fleetFor(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return null;
        }

        ArgusFleet sameNamespace = null;
        for (ArgusFleet f : fleetsByKey.values()) {
            ArgusFleetSpec spec = f.getSpec();
            if (spec != null && spec.getNamespaceSelector() != null
                    && !isEmpty(spec.getNamespaceSelector())) {
                if (namespaceMatches(namespace, spec.getNamespaceSelector())) {
                    return f;
                }
                continue;
            }
            if (namespace.equals(f.getMetadata().getNamespace())) {
                sameNamespace = f;
            }
        }
        return sameNamespace;
    }

    public int knownFleetCount() {
        return fleetsByKey.size();
    }

    private static String key(ArgusFleet fleet) {
        return fleet.getMetadata().getNamespace() + "/" + fleet.getMetadata().getName();
    }

    private boolean namespaceMatches(String namespace, LabelSelector selector) {
        Namespace ns;
        try {
            ns = client.namespaces().withName(namespace).get();
        } catch (Exception e) {
            LOG.debug("Could not fetch namespace {} for selector match: {}", namespace, e.getMessage());
            return false;
        }
        if (ns == null || ns.getMetadata() == null) {
            return false;
        }
        return matches(ns.getMetadata().getLabels(), selector);
    }

    static boolean isEmpty(LabelSelector selector) {
        if (selector == null) {
            return true;
        }
        boolean noMatchLabels = selector.getMatchLabels() == null || selector.getMatchLabels().isEmpty();
        boolean noMatchExpr = selector.getMatchExpressions() == null || selector.getMatchExpressions().isEmpty();
        return noMatchLabels && noMatchExpr;
    }

    static boolean matches(Map<String, String> labels, LabelSelector selector) {
        if (selector == null) {
            return true;
        }
        Map<String, String> resolved = labels == null ? Map.of() : labels;
        Map<String, String> matchLabels = selector.getMatchLabels();
        if (matchLabels != null) {
            for (Map.Entry<String, String> e : matchLabels.entrySet()) {
                if (!e.getValue().equals(resolved.get(e.getKey()))) {
                    return false;
                }
            }
        }
        var expressions = selector.getMatchExpressions();
        if (expressions != null) {
            for (var expr : expressions) {
                String key = expr.getKey();
                String op = expr.getOperator();
                List<String> values = expr.getValues();
                boolean present = resolved.containsKey(key);
                String actual = resolved.get(key);
                switch (op == null ? "" : op) {
                    case "In":
                        if (!present || values == null || !values.contains(actual)) return false;
                        break;
                    case "NotIn":
                        if (present && values != null && values.contains(actual)) return false;
                        break;
                    case "Exists":
                        if (!present) return false;
                        break;
                    case "DoesNotExist":
                        if (present) return false;
                        break;
                    default:
                        return false;
                }
            }
        }
        return true;
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
