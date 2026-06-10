package io.argus.apm.guard;

public record ApmGuardrailDecision(boolean allowed, String code, String detail) {
    public ApmGuardrailDecision {
        code = code == null ? "" : code;
        detail = detail == null ? "" : detail;
    }

    public static ApmGuardrailDecision allow() {
        return new ApmGuardrailDecision(true, "allowed", "allowed");
    }

    public static ApmGuardrailDecision deny(String code, String detail) {
        return new ApmGuardrailDecision(false, code, detail);
    }
}
