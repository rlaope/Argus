package io.argus.apm.security;

import io.argus.apm.dto.ApmBackendLinksRequest;
import io.argus.apm.model.ApmPrincipal;
import io.argus.apm.model.ApmScope;
import io.argus.apm.model.ApmServiceId;

public final class ApmAuthorizer {
    private ApmAuthorizer() {
    }

    public static ApmAuthorizationDecision authorizeRead(ApmPrincipal principal, ApmScope scope) {
        if (principal == null) {
            return ApmAuthorizationDecision.deny("missing principal");
        }
        if (scope == null) {
            return ApmAuthorizationDecision.deny("missing scope");
        }
        if (!principal.canRead(scope)) {
            return ApmAuthorizationDecision.deny("principal is outside tenant/project/environment/service scope");
        }
        return ApmAuthorizationDecision.allow();
    }

    public static ApmAuthorizationDecision authorizeBackendLinks(
            ApmPrincipal principal,
            ApmBackendLinksRequest request
    ) {
        if (request == null) {
            return ApmAuthorizationDecision.deny("missing backend links request");
        }
        ApmScope scope = request.scope();
        ApmServiceId requestedService = request.service();
        if (requestedService != null) {
            if (scope.service() != null && !scope.service().equals(requestedService)) {
                return ApmAuthorizationDecision.deny("backend link service must match scope service");
            }
            scope = scope.withService(requestedService);
        }
        return authorizeRead(principal, scope);
    }
}
