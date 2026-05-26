package io.argus.operator.crd;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Required;

import java.util.HashMap;
import java.util.Map;

public class ArgusTargetSpec {

    @Required
    @JsonProperty("podName")
    private String podName;

    @Required
    @JsonProperty("podNamespace")
    private String podNamespace;

    @JsonProperty("deployment")
    private String deployment = "";

    @Required
    @JsonProperty("host")
    private String host;

    @Required
    @Min(1)
    @JsonProperty("port")
    private int port = 7070;

    @JsonProperty("labels")
    private Map<String, String> labels = new HashMap<>();

    @JsonProperty("fleetRef")
    private String fleetRef;

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public String getPodNamespace() {
        return podNamespace;
    }

    public void setPodNamespace(String podNamespace) {
        this.podNamespace = podNamespace;
    }

    public String getDeployment() {
        return deployment;
    }

    public void setDeployment(String deployment) {
        this.deployment = deployment;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public String getFleetRef() {
        return fleetRef;
    }

    public void setFleetRef(String fleetRef) {
        this.fleetRef = fleetRef;
    }
}
