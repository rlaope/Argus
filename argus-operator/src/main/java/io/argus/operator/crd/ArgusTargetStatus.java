package io.argus.operator.crd;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ArgusTargetStatus {

    @JsonProperty("registered")
    private boolean registered;

    @JsonProperty("lastRegisteredAt")
    private String lastRegisteredAt;

    @JsonProperty("phase")
    private String phase = "Pending";

    @JsonProperty("message")
    private String message;

    public boolean isRegistered() {
        return registered;
    }

    public void setRegistered(boolean registered) {
        this.registered = registered;
    }

    public String getLastRegisteredAt() {
        return lastRegisteredAt;
    }

    public void setLastRegisteredAt(String lastRegisteredAt) {
        this.lastRegisteredAt = lastRegisteredAt;
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
