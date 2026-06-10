package io.argus.apm.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ApmEntityIdentity(
        String stableId,
        ApmMetadataSource winningSource,
        Map<String, String> observedAttributes,
        List<ApmMetadataConflict> conflicts
) {
    public ApmEntityIdentity {
        stableId = ApmValidation.requireText(stableId, "stableId");
        Objects.requireNonNull(winningSource, "winningSource");
        observedAttributes = ApmValidation.copyMap(observedAttributes, "observedAttributes");
        conflicts = ApmValidation.copyList(conflicts, "conflicts");
    }
}
