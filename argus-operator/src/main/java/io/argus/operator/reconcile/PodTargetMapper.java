package io.argus.operator.reconcile;

import io.argus.operator.crd.ArgusTarget;
import io.argus.operator.crd.ArgusTargetSpec;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure mapping between a K8s {@link Pod} and an {@link ArgusTarget} CRD instance.
 * Kept side-effect-free so it can be unit-tested without a cluster.
 */
public final class PodTargetMapper {

    public static final String SCRAPE_LABEL = "argus.io/scrape";
    public static final String SCRAPE_LABEL_VALUE = "true";
    public static final String PORT_ANNOTATION = "argus.io/port";
    public static final String DEPLOYMENT_LABEL = "app.kubernetes.io/name";
    public static final int DEFAULT_PORT = 7070;

    private PodTargetMapper() {
    }

    public static boolean shouldScrape(Pod pod) {
        if (pod == null || pod.getMetadata() == null) {
            return false;
        }
        Map<String, String> labels = pod.getMetadata().getLabels();
        if (labels == null) {
            return false;
        }
        return SCRAPE_LABEL_VALUE.equals(labels.get(SCRAPE_LABEL));
    }

    public static String podId(Pod pod) {
        ObjectMeta meta = pod.getMetadata();
        return meta.getNamespace() + "/" + meta.getName();
    }

    public static String podHost(Pod pod) {
        if (pod.getStatus() == null) {
            return null;
        }
        return pod.getStatus().getPodIP();
    }

    public static int podPort(Pod pod) {
        Map<String, String> ann = pod.getMetadata().getAnnotations();
        if (ann != null) {
            String raw = ann.get(PORT_ANNOTATION);
            if (raw != null && !raw.isBlank()) {
                try {
                    int v = Integer.parseInt(raw.trim());
                    if (v > 0 && v <= 65535) {
                        return v;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return DEFAULT_PORT;
    }

    public static String deploymentName(Pod pod) {
        Map<String, String> labels = pod.getMetadata().getLabels();
        if (labels != null) {
            String name = labels.get(DEPLOYMENT_LABEL);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        if (pod.getMetadata().getOwnerReferences() != null) {
            for (OwnerReference ref : pod.getMetadata().getOwnerReferences()) {
                if ("ReplicaSet".equals(ref.getKind()) || "StatefulSet".equals(ref.getKind())) {
                    return ref.getName();
                }
            }
        }
        return "";
    }

    public static String targetResourceName(Pod pod) {
        return "tgt-" + pod.getMetadata().getName();
    }

    public static ArgusTarget toTarget(Pod pod, String fleetRef, String fleetUid, String fleetName) {
        ArgusTarget target = new ArgusTarget();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(targetResourceName(pod));
        meta.setNamespace(pod.getMetadata().getNamespace());

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("argus.io/managed-by", "argus-operator");
        labels.put("argus.io/pod", pod.getMetadata().getName());
        if (fleetRef != null) {
            labels.put("argus.io/fleet", fleetRef);
        }
        meta.setLabels(labels);

        if (fleetUid != null && fleetName != null) {
            OwnerReference owner = new OwnerReferenceBuilder()
                    .withApiVersion("argus.io/v1alpha1")
                    .withKind("ArgusFleet")
                    .withName(fleetName)
                    .withUid(fleetUid)
                    .withController(true)
                    .withBlockOwnerDeletion(true)
                    .build();
            meta.setOwnerReferences(List.of(owner));
        }

        target.setMetadata(meta);

        ArgusTargetSpec spec = new ArgusTargetSpec();
        spec.setPodName(pod.getMetadata().getName());
        spec.setPodNamespace(pod.getMetadata().getNamespace());
        spec.setDeployment(deploymentName(pod));
        spec.setHost(podHost(pod));
        spec.setPort(podPort(pod));
        spec.setLabels(pod.getMetadata().getLabels() != null
                ? new LinkedHashMap<>(pod.getMetadata().getLabels())
                : new LinkedHashMap<>());
        spec.setFleetRef(fleetRef);
        target.setSpec(spec);

        return target;
    }
}
