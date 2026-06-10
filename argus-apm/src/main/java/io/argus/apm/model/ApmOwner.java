package io.argus.apm.model;

public record ApmOwner(String team, String contact, String escalationPolicy) {
    public ApmOwner {
        team = ApmValidation.requireText(team, "team");
        contact = contact == null ? "" : contact;
        escalationPolicy = escalationPolicy == null ? "" : escalationPolicy;
    }

    public static ApmOwner unassigned() {
        return new ApmOwner("unassigned", "", "");
    }
}
