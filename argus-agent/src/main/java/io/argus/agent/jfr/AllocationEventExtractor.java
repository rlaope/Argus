package io.argus.agent.jfr;

import io.argus.core.event.AllocationEvent;
import jdk.jfr.consumer.RecordedEvent;

import java.time.Instant;

/**
 * Extracts allocation event data from JFR RecordedEvent objects.
 *
 * <p>This class handles extraction of object allocation information
 * from the {@code jdk.ObjectAllocationInNewTLAB} JFR event.
 */
public final class AllocationEventExtractor {

    /**
     * Extracts an AllocationEvent from a jdk.ObjectAllocationInNewTLAB JFR event.
     *
     * @param event the JFR event
     * @return the extracted AllocationEvent
     */
    public AllocationEvent extractAllocation(RecordedEvent event) {
        Instant timestamp = event.getStartTime();
        String className = extractClassName(event);
        long allocationSize = extractAllocationSize(event);
        long tlabSize = extractTlabSize(event);

        return AllocationEvent.of(timestamp, className, allocationSize, tlabSize);
    }

    private String extractClassName(RecordedEvent event) {
        // Try objectClass field (contains RecordedClass)
        try {
            var objectClass = event.getClass("objectClass");
            if (objectClass != null) {
                return objectClass.getName();
            }
        } catch (Exception ignored) {
        }

        // Try class field
        try {
            return event.getString("class");
        } catch (Exception ignored) {
        }

        // Try className field
        try {
            return event.getString("className");
        } catch (Exception ignored) {
        }

        return "Unknown";
    }

    private long extractAllocationSize(RecordedEvent event) {
        // Try allocationSize field
        try {
            return event.getLong("allocationSize");
        } catch (Exception ignored) {
        }

        // Try objectSize field
        try {
            return event.getLong("objectSize");
        } catch (Exception ignored) {
        }

        return 0;
    }

    private long extractTlabSize(RecordedEvent event) {
        // Try tlabSize field
        try {
            return event.getLong("tlabSize");
        } catch (Exception ignored) {
        }

        // Try tlab field
        try {
            return event.getLong("tlab");
        } catch (Exception ignored) {
        }

        return 0;
    }

    /**
     * Debug method to print all available fields in an allocation JFR event.
     *
     * @param event the JFR event
     */
    public void debugPrintFields(RecordedEvent event) {
        System.out.println("[Argus Debug] Allocation Event: " + event.getEventType().getName());
        event.getFields().forEach(field -> {
            try {
                Object value = event.getValue(field.getName());
                System.out.printf("  %s (%s) = %s%n",
                        field.getName(), field.getTypeName(), value);
            } catch (Exception e) {
                System.out.printf("  %s (%s) = ERROR: %s%n",
                        field.getName(), field.getTypeName(), e.getMessage());
            }
        });
    }
}
