package io.argus.instrument;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * Renders a captured argument or return value to a short, safe string.
 *
 * <p>Rendering a live object means calling its {@code toString()}, which is
 * user code running on the application's own threads. Three protections apply:
 * <ol>
 *   <li>Every {@code toString()} is wrapped so a thrown exception degrades to
 *       a {@code <toString threw ...>} marker instead of corrupting the call.</li>
 *   <li>Output is truncated to {@link CaptureCaps#maxValueLen()} characters.</li>
 *   <li>Arrays, collections and maps render as {@code type[size]} / {@code type(size)}
 *       summaries rather than deep-stringifying potentially huge graphs.</li>
 * </ol>
 *
 * <p>This is deliberately read-only: it never mutates the value and never
 * invokes anything beyond {@code toString()} / {@code getClass()}.
 */
public final class ValueRenderer {

    private ValueRenderer() {
    }

    /** Renders {@code value} bounded by {@code caps.maxValueLen()}. Null-safe. */
    public static String render(Object value, CaptureCaps caps) {
        int max = caps == null ? 256 : caps.maxValueLen();
        return truncate(renderRaw(value), max);
    }

    private static String renderRaw(Object value) {
        if (value == null) {
            return "null";
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return arraySummary(value, type);
        }
        if (value instanceof CharSequence) {
            // Route through safeToString: a lazily-evaluated CharSequence whose
            // toString() throws must degrade to a marker, not propagate out onto
            // the application thread.
            return "\"" + safeToString(value) + "\"";
        }
        if (value instanceof Collection) {
            return type.getSimpleName() + "(size=" + safeSize(() -> ((Collection<?>) value).size()) + ")";
        }
        if (value instanceof Map) {
            return type.getSimpleName() + "(size=" + safeSize(() -> ((Map<?, ?>) value).size()) + ")";
        }
        return safeToString(value);
    }

    private static String arraySummary(Object array, Class<?> type) {
        String component = type.getComponentType().getSimpleName();
        int len;
        try {
            len = java.lang.reflect.Array.getLength(array);
        } catch (Throwable t) {
            return component + "[?]";
        }
        // Primitive arrays are cheap and informative to fully render (still bounded by truncate()).
        if (type.getComponentType().isPrimitive() && len <= 32) {
            return primitiveArrayToString(array, type.getComponentType());
        }
        return component + "[" + len + "]";
    }

    private static String primitiveArrayToString(Object array, Class<?> component) {
        try {
            if (component == int.class) {
                return Arrays.toString((int[]) array);
            }
            if (component == long.class) {
                return Arrays.toString((long[]) array);
            }
            if (component == double.class) {
                return Arrays.toString((double[]) array);
            }
            if (component == float.class) {
                return Arrays.toString((float[]) array);
            }
            if (component == boolean.class) {
                return Arrays.toString((boolean[]) array);
            }
            if (component == byte.class) {
                return Arrays.toString((byte[]) array);
            }
            if (component == short.class) {
                return Arrays.toString((short[]) array);
            }
            if (component == char.class) {
                return Arrays.toString((char[]) array);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return component + "[]";
    }

    private static String safeToString(Object value) {
        try {
            String s = value.toString();
            return s == null ? "null" : s;
        } catch (Throwable t) {
            return "<" + value.getClass().getName() + " toString threw "
                    + t.getClass().getSimpleName() + ">";
        }
    }

    private interface IntSupplier {
        int get();
    }

    private static String safeSize(IntSupplier supplier) {
        try {
            return Integer.toString(supplier.get());
        } catch (Throwable t) {
            return "?";
        }
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        if (max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…"; // ellipsis marker
    }
}
