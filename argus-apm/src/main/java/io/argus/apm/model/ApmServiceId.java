package io.argus.apm.model;

public record ApmServiceId(String namespace, String name) {
    public ApmServiceId {
        namespace = namespace == null ? "" : namespace;
        name = ApmValidation.requireText(name, "name");
    }

    public String displayName() {
        return namespace.isBlank() ? name : namespace + "/" + name;
    }
}
