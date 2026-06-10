package io.argus.apm.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ApmValidation {
    private ApmValidation() {
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static <T> List<T> copyList(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name));
    }

    static <T> Set<T> copySet(Set<T> values, String name) {
        return Set.copyOf(Objects.requireNonNull(values, name));
    }

    static <K, V> Map<K, V> copyMap(Map<K, V> values, String name) {
        return Map.copyOf(Objects.requireNonNull(values, name));
    }
}
