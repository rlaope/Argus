package io.argus.operator.crd;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ArgusFleetStatus {

    @JsonProperty("registeredTargets")
    private int registeredTargets;

    @JsonProperty("lastReconcileAt")
    private String lastReconcileAt;

    @JsonProperty("phase")
    private String phase = "Pending";

    @JsonProperty("message")
    private String message;

    public int getRegisteredTargets() {
        return registeredTargets;
    }

    public void setRegisteredTargets(int registeredTargets) {
        this.registeredTargets = registeredTargets;
    }

    public String getLastReconcileAt() {
        return lastReconcileAt;
    }

    public void setLastReconcileAt(String lastReconcileAt) {
        this.lastReconcileAt = lastReconcileAt;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
