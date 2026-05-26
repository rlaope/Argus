package io.argus.operator;

import io.argus.operator.client.AggregatorClient;
import io.argus.operator.crd.ArgusFleet;
import io.argus.operator.reconcile.FleetReconciler;
import io.argus.operator.reconcile.PodTargetMapper;
import io.argus.operator.reconcile.TargetReconciler;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * argus-operator entrypoint.
 *
 * Configuration via env vars:
 *   - ARGUS_AGGREGATOR_URL    (default: http://argus-aggregator.argus-system.svc.cluster.local:9300)
 *   - ARGUS_WATCH_NAMESPACE   (default: "" — cluster-wide)
 *   - ARGUS_RESYNC_PERIOD_MS  (default: 60000)
 */
public final class ArgusOperator {

    private static final Logger LOG = LoggerFactory.getLogger(ArgusOperator.class);

    private static final String DEFAULT_AGGREGATOR_URL =
            "http://argus-aggregator.argus-system.svc.cluster.local:9300";

    private ArgusOperator() {
    }

    public static void main(String[] args) throws Exception {
        String aggregatorUrl = env("ARGUS_AGGREGATOR_URL", DEFAULT_AGGREGATOR_URL);
        String watchNamespace = env("ARGUS_WATCH_NAMESPACE", "");
        long resyncPeriodMs = Long.parseLong(env("ARGUS_RESYNC_PERIOD_MS", "60000"));

        LOG.info("argus-operator starting: aggregator={} watchNamespace={} resync={}ms",
                aggregatorUrl, watchNamespace.isEmpty() ? "<cluster-wide>" : watchNamespace, resyncPeriodMs);

        AggregatorClient aggregator = new AggregatorClient(aggregatorUrl);
        try (KubernetesClient client = new KubernetesClientBuilder().build();
             TargetReconciler targetReconciler = new TargetReconciler(
                     client, new FleetReconciler(client), aggregator)) {
            FleetReconciler fleetReconciler = targetReconciler.fleetReconciler();

            SharedIndexInformer<ArgusFleet> fleetInformer = watchNamespace.isEmpty()
                    ? client.resources(ArgusFleet.class).inAnyNamespace().inform(fleetReconciler, resyncPeriodMs)
                    : client.resources(ArgusFleet.class).inNamespace(watchNamespace).inform(fleetReconciler, resyncPeriodMs);

            SharedIndexInformer<Pod> podInformer = watchNamespace.isEmpty()
                    ? client.pods().inAnyNamespace()
                            .withLabel(PodTargetMapper.SCRAPE_LABEL, PodTargetMapper.SCRAPE_LABEL_VALUE)
                            .inform(targetReconciler, resyncPeriodMs)
                    : client.pods().inNamespace(watchNamespace)
                            .withLabel(PodTargetMapper.SCRAPE_LABEL, PodTargetMapper.SCRAPE_LABEL_VALUE)
                            .inform(targetReconciler, resyncPeriodMs);

            LOG.info("Informers started. fleet={} pod={}",
                    fleetInformer.getClass().getSimpleName(),
                    podInformer.getClass().getSimpleName());

            CountDownLatch shutdown = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("argus-operator shutting down");
                shutdown.countDown();
            }, "argus-operator-shutdown"));
            shutdown.await();
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? fallback : v;
    }
}
