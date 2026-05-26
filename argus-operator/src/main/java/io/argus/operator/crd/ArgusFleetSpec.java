package io.argus.operator.crd;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Required;
import io.fabric8.kubernetes.api.model.LabelSelector;

public class ArgusFleetSpec {

    @Required
    @JsonProperty("aggregatorEndpoint")
    private String aggregatorEndpoint;

    @Min(1)
    @JsonProperty("scrapeIntervalSeconds")
    private int scrapeIntervalSeconds = 15;

    @Min(60)
    @JsonProperty("retentionSeconds")
    private int retentionSeconds = 3600;

    @JsonProperty("alertWebhook")
    private String alertWebhook;

    @JsonProperty("namespaceSelector")
    private LabelSelector namespaceSelector;

    @JsonProperty("podSelector")
    private LabelSelector podSelector;

    public String getAggregatorEndpoint() {
        return aggregatorEndpoint;
    }

    public void setAggregatorEndpoint(String aggregatorEndpoint) {
        this.aggregatorEndpoint = aggregatorEndpoint;
    }

    public int getScrapeIntervalSeconds() {
        return scrapeIntervalSeconds;
    }

    public void setScrapeIntervalSeconds(int scrapeIntervalSeconds) {
        this.scrapeIntervalSeconds = scrapeIntervalSeconds;
    }

    public int getRetentionSeconds() {
        return retentionSeconds;
    }

    public void setRetentionSeconds(int retentionSeconds) {
        this.retentionSeconds = retentionSeconds;
    }

    public String getAlertWebhook() {
        return alertWebhook;
    }

    public void setAlertWebhook(String alertWebhook) {
        this.alertWebhook = alertWebhook;
    }

    public LabelSelector getNamespaceSelector() {
        return namespaceSelector;
    }

    public void setNamespaceSelector(LabelSelector namespaceSelector) {
        this.namespaceSelector = namespaceSelector;
    }

    public LabelSelector getPodSelector() {
        return podSelector;
    }

    public void setPodSelector(LabelSelector podSelector) {
        this.podSelector = podSelector;
    }
}
