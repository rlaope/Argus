package io.argus.apm.security;

public record ApmAuthorizationDecision(boolean allowed, String reason) {
    public ApmAuthorizationDecision {
        reason = reason == null ? "" : reason;
    }

    public static ApmAuthorizationDecision allow() {
        return new ApmAuthorizationDecision(true, "allowed");
    }

    public static ApmAuthorizationDecision deny(String reason) {
        return new ApmAuthorizationDecision(false, reason);
    }
}
