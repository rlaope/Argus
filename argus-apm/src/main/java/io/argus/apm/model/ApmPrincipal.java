package io.argus.apm.model;

import java.util.Objects;
import java.util.Set;

public record ApmPrincipal(
        String subject,
        String tenant,
        String project,
        Set<String> environments,
        Set<ApmRole> roles,
        Set<ApmServiceId> serviceAllowlist
) {
    public ApmPrincipal {
        subject = ApmValidation.requireText(subject, "subject");
        tenant = ApmValidation.requireText(tenant, "tenant");
        project = ApmValidation.requireText(project, "project");
        environments = ApmValidation.copySet(environments, "environments");
        roles = ApmValidation.copySet(roles, "roles");
        serviceAllowlist = ApmValidation.copySet(serviceAllowlist, "serviceAllowlist");
        if (environments.isEmpty()) {
            throw new IllegalArgumentException("environments must not be empty");
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
    }

    public boolean canRead(ApmScope scope) {
        Objects.requireNonNull(scope, "scope");
        boolean roleAllowed = roles.contains(ApmRole.VIEWER)
                || roles.contains(ApmRole.OPERATOR)
                || roles.contains(ApmRole.ADMIN);
        if (!roleAllowed) {
            return false;
        }
        if (!tenant.equals(scope.tenant()) || !project.equals(scope.project())) {
            return false;
        }
        if (!environments.contains(scope.environment())) {
            return false;
        }
        return scope.service() == null
                || serviceAllowlist.isEmpty()
                || serviceAllowlist.contains(scope.service());
    }
}
