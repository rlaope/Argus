package io.argus.operator.reconcile;

import io.argus.operator.client.AggregatorClient;
import io.argus.operator.crd.ArgusFleet;
import io.argus.operator.crd.ArgusTarget;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Watches pods labeled {@code argus.io/scrape=true} and reconciles
 * {@link ArgusTarget} CRD instances + aggregator-side scrape registration.
 *
 * Behaviour:
 *   - Pod ADD/UPDATE (running, has IP): upsert ArgusTarget + POST /fleet/targets
 *   - Pod DELETE / loses label / loses IP: delete ArgusTarget + DELETE /fleet/targets/{podId}
 */
public class TargetReconciler implements ResourceEventHandler<Pod> {

    private static final Logger LOG = LoggerFactory.getLogger(TargetReconciler.class);

    private final KubernetesClient client;
    private final FleetReconciler fleetReconciler;
    private final AggregatorClient aggregator;
    private final ConcurrentMap<String, RegisteredPod> registered = new ConcurrentHashMap<>();

    public TargetReconciler(KubernetesClient client,
                            FleetReconciler fleetReconciler,
                            AggregatorClient aggregator) {
        this.client = client;
        this.fleetReconciler = fleetReconciler;
        this.aggregator = aggregator;
    }

    @Override
    public void onAdd(Pod pod) {
        handleUpsert(pod);
    }

    @Override
    public void onUpdate(Pod oldPod, Pod newPod) {
        if (!PodTargetMapper.shouldScrape(newPod) || PodTargetMapper.podHost(newPod) == null) {
            // If the pod previously had a target but no longer qualifies, tear it down.
            if (registered.containsKey(PodTargetMapper.podId(newPod))) {
                handleDelete(newPod);
            }
            return;
        }
        handleUpsert(newPod);
    }

    @Override
    public void onDelete(Pod pod, boolean deletedFinalStateUnknown) {
        handleDelete(pod);
    }

    public int registeredCount() {
        return registered.size();
    }

    private void handleUpsert(Pod pod) {
        if (!PodTargetMapper.shouldScrape(pod)) {
            return;
        }
        String host = PodTargetMapper.podHost(pod);
        if (host == null || host.isEmpty()) {
            // Pod not yet scheduled with an IP; will retry on next update.
            return;
        }
        String podId = PodTargetMapper.podId(pod);
        int port = PodTargetMapper.podPort(pod);
        String deployment = PodTargetMapper.deploymentName(pod);

        ArgusFleet fleet = fleetReconciler.fleetFor(pod.getMetadata().getNamespace());
        String fleetRef = fleet == null ? null : fleet.getMetadata().getName();
        String fleetUid = fleet == null ? null : fleet.getMetadata().getUid();

        // 1) Upsert the ArgusTarget CRD instance.
        try {
            ArgusTarget desired = PodTargetMapper.toTarget(pod, fleetRef, fleetUid, fleetRef);
            MixedOperation<ArgusTarget, ?, Resource<ArgusTarget>> api = client.resources(ArgusTarget.class);
            api.inNamespace(desired.getMetadata().getNamespace())
                    .resource(desired)
                    .createOr(r -> r.update());
        } catch (Exception e) {
            LOG.warn("Failed to upsert ArgusTarget for pod {}: {}", podId, e.getMessage());
        }

        // 2) Register with aggregator. Idempotent — safe to retry.
        try {
            aggregator.registerTarget(podId,
                    pod.getMetadata().getNamespace(),
                    pod.getMetadata().getName(),
                    deployment,
                    host,
                    port);
            registered.put(podId, new RegisteredPod(host, port));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Failed to register target {} with aggregator: {}", podId, e.getMessage());
        }
    }

    private void handleDelete(Pod pod) {
        String podId = PodTargetMapper.podId(pod);

        // Delete the ArgusTarget CRD instance (best-effort).
        try {
            client.resources(ArgusTarget.class)
                    .inNamespace(pod.getMetadata().getNamespace())
                    .withName(PodTargetMapper.targetResourceName(pod))
                    .delete();
        } catch (Exception e) {
            LOG.debug("Failed to delete ArgusTarget for pod {}: {}", podId, e.getMessage());
        }

        // Deregister from aggregator (idempotent).
        try {
            aggregator.deleteTarget(podId);
            registered.remove(podId);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Failed to deregister target {} from aggregator: {}", podId, e.getMessage());
        }
    }

    private record RegisteredPod(String host, int port) {
    }
}
