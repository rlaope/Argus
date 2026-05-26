package io.argus.operator.reconcile;

import io.argus.operator.crd.ArgusTarget;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PodTargetMapperTest {

    private static Pod pod(String ns, String name, Map<String, String> labels,
                           Map<String, String> annotations, String ip) {
        Pod p = new Pod();
        ObjectMeta meta = new ObjectMeta();
        meta.setNamespace(ns);
        meta.setName(name);
        meta.setLabels(labels);
        meta.setAnnotations(annotations);
        p.setMetadata(meta);
        PodStatus s = new PodStatus();
        s.setPodIP(ip);
        p.setStatus(s);
        return p;
    }

    @Test
    void shouldScrape_requiresLabel() {
        Pod yes = pod("ns", "p", Map.of(PodTargetMapper.SCRAPE_LABEL, "true"), null, "10.0.0.1");
        Pod no = pod("ns", "p", Map.of(PodTargetMapper.SCRAPE_LABEL, "false"), null, "10.0.0.1");
        Pod none = pod("ns", "p", null, null, "10.0.0.1");
        assertTrue(PodTargetMapper.shouldScrape(yes));
        assertFalse(PodTargetMapper.shouldScrape(no));
        assertFalse(PodTargetMapper.shouldScrape(none));
    }

    @Test
    void podId_concatenatesNamespaceAndName() {
        Pod p = pod("prod", "payment-5c7d9f-xkz2q", Map.of(), Map.of(), "10.1.2.3");
        assertEquals("prod/payment-5c7d9f-xkz2q", PodTargetMapper.podId(p));
    }

    @Test
    void podPort_defaultWhenAnnotationMissing() {
        Pod p = pod("ns", "p", Map.of(), null, "10.0.0.1");
        assertEquals(PodTargetMapper.DEFAULT_PORT, PodTargetMapper.podPort(p));
    }

    @Test
    void podPort_overrideViaAnnotation() {
        Pod p = pod("ns", "p", Map.of(), Map.of(PodTargetMapper.PORT_ANNOTATION, "8080"), "10.0.0.1");
        assertEquals(8080, PodTargetMapper.podPort(p));
    }

    @Test
    void podPort_invalidAnnotationFallsBack() {
        Pod p = pod("ns", "p", Map.of(), Map.of(PodTargetMapper.PORT_ANNOTATION, "abc"), "10.0.0.1");
        assertEquals(PodTargetMapper.DEFAULT_PORT, PodTargetMapper.podPort(p));
    }

    @Test
    void deploymentName_prefersAppLabel() {
        Pod p = pod("ns", "p-abc",
                Map.of(PodTargetMapper.DEPLOYMENT_LABEL, "payment"),
                null, "10.0.0.1");
        assertEquals("payment", PodTargetMapper.deploymentName(p));
    }

    @Test
    void deploymentName_fallsBackToReplicaSetOwner() {
        Pod p = pod("ns", "p-abc", Map.of(), null, "10.0.0.1");
        OwnerReference ref = new OwnerReferenceBuilder()
                .withKind("ReplicaSet").withName("payment-5c7d9f")
                .withApiVersion("apps/v1").withUid("uid-rs").build();
        p.getMetadata().setOwnerReferences(List.of(ref));
        assertEquals("payment-5c7d9f", PodTargetMapper.deploymentName(p));
    }

    @Test
    void toTarget_populatesSpecAndOwnerReference() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PodTargetMapper.SCRAPE_LABEL, "true");
        labels.put(PodTargetMapper.DEPLOYMENT_LABEL, "payment");
        Pod p = pod("prod", "payment-5c7d9f-xkz2q", labels,
                Map.of(PodTargetMapper.PORT_ANNOTATION, "7070"), "10.1.2.3");

        ArgusTarget t = PodTargetMapper.toTarget(p, "default-fleet", "uid-fleet-1", "default-fleet");

        assertNotNull(t);
        assertEquals("prod", t.getMetadata().getNamespace());
        assertEquals("tgt-payment-5c7d9f-xkz2q", t.getMetadata().getName());
        assertEquals("argus-operator", t.getMetadata().getLabels().get("argus.io/managed-by"));
        assertEquals("default-fleet", t.getMetadata().getLabels().get("argus.io/fleet"));
        assertEquals("payment-5c7d9f-xkz2q", t.getSpec().getPodName());
        assertEquals("prod", t.getSpec().getPodNamespace());
        assertEquals("payment", t.getSpec().getDeployment());
        assertEquals("10.1.2.3", t.getSpec().getHost());
        assertEquals(7070, t.getSpec().getPort());
        assertEquals("default-fleet", t.getSpec().getFleetRef());
        assertNotNull(t.getMetadata().getOwnerReferences());
        assertEquals(1, t.getMetadata().getOwnerReferences().size());
        assertEquals("ArgusFleet", t.getMetadata().getOwnerReferences().get(0).getKind());
        assertEquals("uid-fleet-1", t.getMetadata().getOwnerReferences().get(0).getUid());
    }

    @Test
    void toTarget_omitsOwnerReferenceWhenFleetMissing() {
        Pod p = pod("ns", "p", Map.of(PodTargetMapper.SCRAPE_LABEL, "true"), null, "10.0.0.1");
        ArgusTarget t = PodTargetMapper.toTarget(p, null, null, null);
        assertNotNull(t);
        // owner refs may be null or empty when no fleet
        assertTrue(t.getMetadata().getOwnerReferences() == null
                || t.getMetadata().getOwnerReferences().isEmpty());
    }
}
