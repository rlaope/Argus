package io.argus.agent.jfr;

import io.argus.core.event.GCEvent;
import jdk.jfr.consumer.RecordedEvent;

import java.time.Instant;

/**
 * Extracts GC event data from JFR RecordedEvent objects.
 *
 * <p>This class handles extraction of garbage collection information
 * from JFR events including:
 * <ul>
 *   <li>{@code jdk.GarbageCollection} - GC pause duration and cause</li>
 *   <li>{@code jdk.GCHeapSummary} - Heap usage before and after GC</li>
 * </ul>
 */
public final class GCEventExtractor {

    /**
     * Extracts a GCEvent from a jdk.GarbageCollection JFR event.
     *
     * @param event the JFR event
     * @return the extracted GCEvent
     */
    public GCEvent extractGarbageCollection(RecordedEvent event) {
        Instant timestamp = event.getStartTime();
        long duration = event.getDuration().toNanos();
        String gcName = extractGcName(event);
        String gcCause = extractGcCause(event);

        return GCEvent.pause(timestamp, duration, gcName, gcCause);
    }

    /**
     * Extracts a GCEvent from a jdk.GCHeapSummary JFR event.
     *
     * @param event the JFR event
     * @return the extracted GCEvent
     */
    public GCEvent extractHeapSummary(RecordedEvent event) {
        Instant timestamp = event.getStartTime();
        long heapUsed = extractHeapUsed(event);
        long heapCommitted = extractHeapCommitted(event);

        // GCHeapSummary doesn't distinguish before/after - it's a snapshot
        return GCEvent.heapSummary(timestamp, heapUsed, heapUsed, heapCommitted);
    }

    /**
     * Creates a combined GCEvent with both pause and heap information.
     *
     * @param gcEvent   the garbage collection event
     * @param heapEvent the heap summary event
     * @return combined GCEvent
     */
    public GCEvent combineEvents(RecordedEvent gcEvent, RecordedEvent heapEvent) {
        Instant timestamp = gcEvent.getStartTime();
        long duration = gcEvent.getDuration().toNanos();
        String gcName = extractGcName(gcEvent);
        String gcCause = extractGcCause(gcEvent);
        long heapUsed = extractHeapUsed(heapEvent);
        long heapCommitted = extractHeapCommitted(heapEvent);

        return GCEvent.combined(timestamp, duration, gcName, gcCause,
                heapUsed, heapUsed, heapCommitted);
    }

    private String extractGcName(RecordedEvent event) {
        // Try to get GC name from event
        try {
            return event.getString("name");
        } catch (Exception ignored) {
        }

        // Try gcName field
        try {
            return event.getString("gcName");
        } catch (Exception ignored) {
        }

        return "Unknown";
    }

    private String extractGcCause(RecordedEvent event) {
        // Try cause field
        try {
            return event.getString("cause");
        } catch (Exception ignored) {
        }

        // Try gcCause field
        try {
            return event.getString("gcCause");
        } catch (Exception ignored) {
        }

        return "Unknown";
    }

    private long extractHeapUsed(RecordedEvent event) {
        // Try heapUsed field
        try {
            return event.getLong("heapUsed");
        } catch (Exception ignored) {
        }

        // Try to get from heapSpace object
        try {
            Object heapSpace = event.getValue("heapSpace");
            if (heapSpace != null) {
                // heapSpace is a RecordedObject with committed/reserved/used fields
                return event.getLong("heapSpace.used");
            }
        } catch (Exception ignored) {
        }

        return 0;
    }

    private long extractHeapCommitted(RecordedEvent event) {
        // Try heapCommitted field
        try {
            return event.getLong("heapCommitted");
        } catch (Exception ignored) {
        }

        // Try to get from heapSpace object
        try {
            Object heapSpace = event.getValue("heapSpace");
            if (heapSpace != null) {
                return event.getLong("heapSpace.committedSize");
            }
        } catch (Exception ignored) {
        }

        return 0;
    }

    /**
     * Debug method to print all available fields in a GC JFR event.
     */
    public void debugPrintFields(RecordedEvent event) {
        System.out.println("[Argus Debug] GC Event: " + event.getEventType().getName());
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
