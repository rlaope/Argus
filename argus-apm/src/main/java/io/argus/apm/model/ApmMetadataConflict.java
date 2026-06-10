package io.argus.apm.model;

import java.util.Objects;

public record ApmMetadataConflict(
        String entityType,
        String field,
        ApmMetadataSource winningSource,
        String winningValue,
        ApmMetadataSource losingSource,
        String losingValue
) {
    public ApmMetadataConflict {
        entityType = ApmValidation.requireText(entityType, "entityType");
        field = ApmValidation.requireText(field, "field");
        Objects.requireNonNull(winningSource, "winningSource");
        winningValue = ApmValidation.requireText(winningValue, "winningValue");
        Objects.requireNonNull(losingSource, "losingSource");
        losingValue = ApmValidation.requireText(losingValue, "losingValue");
    }
}
