package io.argus.aggregator.model;

import java.time.Instant;

/**
 * One registered scrape target — a pod running argus-agent.
 *
 * <p>Matches the JSON shape in docs/aggregator-api.md.
 *
 * @param podId        unique ID: {@code <namespace>/<podName>}
 * @param namespace    K8s namespace
 * @param podName      K8s pod name
 * @param deployment   K8s deployment/statefulset name (empty string if unknown)
 * @param host         pod IP or hostname
 * @param port         argus-agent HTTP port
 * @param registeredAt timestamp of first registration
 * @param lastScrapeAt timestamp of most recent successful scrape (null if never scraped)
 * @param scrapeOk     true if last scrape succeeded
 */
public record PodTarget(
        String podId,
        String namespace,
        String podName,
        String deployment,
        String host,
        int port,
        Instant registeredAt,
        Instant lastScrapeAt,
        boolean scrapeOk
) {
    /** URL used for scrape requests against this target. */
    public String scrapeUrl() {
        return "http://" + host + ":" + port;
    }

    /** Returns a copy with updated scrape state. */
    public PodTarget withScrape(Instant ts, boolean ok) {
        return new PodTarget(podId, namespace, podName, deployment, host, port, registeredAt, ts, ok);
    }

    /** Returns a copy with updated host/port (idempotent re-registration). */
    public PodTarget withAddress(String newHost, int newPort) {
        return new PodTarget(podId, namespace, podName, deployment, newHost, newPort, registeredAt, lastScrapeAt, scrapeOk);
    }
}
