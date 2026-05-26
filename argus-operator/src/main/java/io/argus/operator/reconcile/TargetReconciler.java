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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches pods labeled {@code argus.io/scrape=true} and reconciles
 * {@link ArgusTarget} CRD instances + aggregator-side scrape registration.
 *
 * Behaviour:
 *   - Pod ADD/UPDATE (running, has IP): upsert ArgusTarget + POST /fleet/targets
 *   - Pod DELETE / loses label / loses IP: delete ArgusTarget + DELETE /fleet/targets/{podId}
 *
 * Aggregator I/O is dispatched to a bounded worker pool so a slow aggregator
 * never blocks the fabric8 informer dispatch thread. The aggregator REST contract
 * is idempotent (POST = upsert, DELETE = idempotent), so retries and dropped
 * tasks under saturation are safe.
 */
public class TargetReconciler implements ResourceEventHandler<Pod>, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TargetReconciler.class);

    private static final int DEFAULT_WORKER_THREADS = 8;
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;

    private final KubernetesClient client;
    private final FleetReconciler fleetReconciler;
    private final AggregatorClient aggregator;
    private final ConcurrentMap<String, RegisteredPod> registered = new ConcurrentHashMap<>();
    private final ExecutorService aggregatorWorkers;

    public TargetReconciler(KubernetesClient client,
                            FleetReconciler fleetReconciler,
                            AggregatorClient aggregator) {
        this(client, fleetReconciler, aggregator,
                buildBoundedExecutor(DEFAULT_WORKER_THREADS, DEFAULT_QUEUE_CAPACITY));
    }

    TargetReconciler(KubernetesClient client,
                     FleetReconciler fleetReconciler,
                     AggregatorClient aggregator,
                     ExecutorService aggregatorWorkers) {
        this.client = client;
        this.fleetReconciler = fleetReconciler;
        this.aggregator = aggregator;
        this.aggregatorWorkers = aggregatorWorkers;
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

    public FleetReconciler fleetReconciler() {
        return fleetReconciler;
    }

    @Override
    public void close() {
        aggregatorWorkers.shutdown();
        try {
            if (!aggregatorWorkers.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warn("TargetReconciler workers did not drain within 10s; forcing shutdown.");
                aggregatorWorkers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            aggregatorWorkers.shutdownNow();
        }
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

        // Honor the fleet's podSelector (in addition to argus.io/scrape=true) — when
        // a fleet sets one, only pods whose labels match are governed.
        if (fleet != null && fleet.getSpec() != null) {
            var podSelector = fleet.getSpec().getPodSelector();
            if (!FleetReconciler.isEmpty(podSelector)
                    && !FleetReconciler.matches(pod.getMetadata().getLabels(), podSelector)) {
                LOG.debug("Pod {} does not match fleet {}/podSelector; skipping.",
                        podId, fleet.getMetadata().getName());
                // If we previously registered this pod, tear it down to honour the
                // selector flip.
                if (registered.containsKey(podId)) {
                    handleDelete(pod);
                }
                return;
            }
        }

        String fleetRef = fleet == null ? null : fleet.getMetadata().getName();
        String fleetUid = fleet == null ? null : fleet.getMetadata().getUid();

        // 1) Upsert the ArgusTarget CRD instance (cheap K8s API call — stays on the
        //    informer thread to preserve event ordering).
        try {
            ArgusTarget desired = PodTargetMapper.toTarget(pod, fleetRef, fleetUid, fleetRef);
            MixedOperation<ArgusTarget, ?, Resource<ArgusTarget>> api = client.resources(ArgusTarget.class);
            api.inNamespace(desired.getMetadata().getNamespace())
                    .resource(desired)
                    .createOr(r -> r.update());
        } catch (Exception e) {
            LOG.warn("Failed to upsert ArgusTarget for pod {}: {}", podId, e.getMessage());
        }

        // 2) Register with aggregator off-thread. Idempotent — safe to retry,
        //    safe to drop on saturation.
        submit(podId, () -> {
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
        });
    }

    private void handleDelete(Pod pod) {
        String podId = PodTargetMapper.podId(pod);

        // Delete the ArgusTarget CRD instance (best-effort, cheap K8s API call).
        try {
            client.resources(ArgusTarget.class)
                    .inNamespace(pod.getMetadata().getNamespace())
                    .withName(PodTargetMapper.targetResourceName(pod))
                    .delete();
        } catch (Exception e) {
            LOG.debug("Failed to delete ArgusTarget for pod {}: {}", podId, e.getMessage());
        }

        // Deregister from aggregator off-thread. Idempotent on both sides.
        submit(podId, () -> {
            try {
                aggregator.deleteTarget(podId);
                registered.remove(podId);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.warn("Failed to deregister target {} from aggregator: {}", podId, e.getMessage());
            }
        });
    }

    private void submit(String podId, Runnable task) {
        try {
            aggregatorWorkers.submit(task);
        } catch (RejectedExecutionException rex) {
            // Bounded queue saturated — drop and rely on the next informer resync
            // to retry. Loss is bounded because the aggregator contract is idempotent.
            LOG.warn("Aggregator worker queue saturated; dropping task for pod {} (will retry on next resync)", podId);
        }
    }

    static ExecutorService buildBoundedExecutor(int threads, int queueCapacity) {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "argus-operator-aggregator-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                tf,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private record RegisteredPod(String host, int port) {
    }
}
