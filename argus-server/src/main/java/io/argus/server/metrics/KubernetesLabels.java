package io.argus.server.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detects Kubernetes environment via Downward API environment variables
 * and provides labels for Prometheus metrics.
 *
 * Expected env vars (set via K8s Downward API):
 * - ARGUS_POD_NAME or HOSTNAME
 * - ARGUS_NAMESPACE or POD_NAMESPACE
 * - ARGUS_NODE_NAME
 * - ARGUS_DEPLOYMENT (optional)
 */
public final class KubernetesLabels {

    private static final Map<String, String> LABELS = detectLabels();

    private KubernetesLabels() {}

    public static boolean isKubernetes() {
        return !LABELS.isEmpty();
    }

    public static Map<String, String> getLabels() {
        return LABELS;
    }

    /**
     * Returns Prometheus label suffix string like {pod="abc",namespace="default"}
     * or empty string if not in K8s.
     */
    public static String prometheusLabelSuffix() {
        if (LABELS.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : LABELS.entrySet()) {
            if (!first) sb.append(',');
            sb.append(entry.getKey()).append("=\"").append(entry.getValue()).append('"');
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Merges K8s labels into an existing Prometheus label string.
     * If existingLabels is empty, returns {@link #prometheusLabelSuffix()}.
     * Otherwise inserts K8s labels before the closing brace.
     *
     * @param existingLabels existing label string, e.g. {@code {monitor="java.util.concurrent.locks.ReentrantLock"}}
     * @return merged label string, or existingLabels unchanged if not in K8s
     */
    public static String mergeLabels(String existingLabels) {
        if (LABELS.isEmpty()) return existingLabels;
        if (existingLabels == null || existingLabels.isEmpty()) return prometheusLabelSuffix();

        // Build K8s label pairs without braces
        StringBuilder k8sPairs = new StringBuilder();
        for (var entry : LABELS.entrySet()) {
            k8sPairs.append(',')
                    .append(entry.getKey()).append("=\"").append(entry.getValue()).append('"');
        }

        // Insert before closing brace
        int closingBrace = existingLabels.lastIndexOf('}');
        if (closingBrace < 0) return existingLabels;
        return existingLabels.substring(0, closingBrace) + k8sPairs + "}";
    }

    private static Map<String, String> detectLabels() {
        Map<String, String> labels = new LinkedHashMap<>();

        String pod = env("ARGUS_POD_NAME", env("HOSTNAME", null));
        String namespace = env("ARGUS_NAMESPACE", env("POD_NAMESPACE", null));
        String node = env("ARGUS_NODE_NAME", null);

        // Only add labels if we detect at least namespace (strong K8s signal)
        if (namespace != null) {
            if (pod != null) labels.put("pod", pod);
            labels.put("namespace", namespace);
            if (node != null) labels.put("node", node);
        }

        return Map.copyOf(labels);
    }

    private static String env(String name, String fallback) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : fallback;
    }
}
