package io.argus.agent.jfr;

import io.argus.core.event.MetaspaceEvent;
import jdk.jfr.consumer.RecordedEvent;

import java.time.Instant;

/**
 * Extracts metaspace event data from JFR RecordedEvent objects.
 *
 * <p>This class handles extraction of metaspace information
 * from the {@code jdk.MetaspaceSummary} JFR event.
 */
public final class MetaspaceEventExtractor {

    /**
     * Extracts a MetaspaceEvent from a jdk.MetaspaceSummary JFR event.
     *
     * @param event the JFR event
     * @return the extracted MetaspaceEvent
     */
    public MetaspaceEvent extractMetaspace(RecordedEvent event) {
        Instant timestamp = event.getStartTime();
        long used = extractMetaspaceUsed(event);
        long committed = extractMetaspaceCommitted(event);
        long reserved = extractMetaspaceReserved(event);
        long classCount = extractClassCount(event);

        return MetaspaceEvent.of(timestamp, used, committed, reserved, classCount);
    }

    private long extractMetaspaceUsed(RecordedEvent event) {
        // Try metaspace.used field path
        try {
            return event.getLong("metaspace.used");
        } catch (Exception ignored) {
        }

        // Try dataSpace.used + classSpace.used
        try {
            long dataUsed = event.getLong("dataSpace.used");
            long classUsed = event.getLong("classSpace.used");
            return dataUsed + classUsed;
        } catch (Exception ignored) {
        }

        // Try used field
        try {
            return event.getLong("used");
        } catch (Exception ignored) {
        }

        return 0;
    }

    private long extractMetaspaceCommitted(RecordedEvent event) {
        // Try metaspace.committed field path
        try {
            return event.getLong("metaspace.committed");
        } catch (Exception ignored) {
        }

        // Try dataSpace.committed + classSpace.committed
        try {
            long dataCommitted = event.getLong("dataSpace.committed");
            long classCommitted = event.getLong("classSpace.committed");
            return dataCommitted + classCommitted;
        } catch (Exception ignored) {
        }

        // Try committed field
        try {
            return event.getLong("committed");
        } catch (Exception ignored) {
        }

        return 0;
    }

    private long extractMetaspaceReserved(RecordedEvent event) {
        // Try metaspace.reserved field path
        try {
            return event.getLong("metaspace.reserved");
        } catch (Exception ignored) {
        }

        // Try dataSpace.reserved + classSpace.reserved
        try {
            long dataReserved = event.getLong("dataSpace.reserved");
            long classReserved = event.getLong("classSpace.reserved");
            return dataReserved + classReserved;
        } catch (Exception ignored) {
        }

        // Try reserved field
        try {
            return event.getLong("reserved");
        } catch (Exception ignored) {
        }

        return 0;
    }

    private long extractClassCount(RecordedEvent event) {
        // Try classCount field
        try {
            return event.getLong("classCount");
        } catch (Exception ignored) {
        }

        // Try classLoader.classCount
        try {
            return event.getLong("classLoader.classCount");
        } catch (Exception ignored) {
        }

        return 0;
    }

    /**
     * Debug method to print all available fields in a metaspace JFR event.
     *
     * @param event the JFR event
     */
    public void debugPrintFields(RecordedEvent event) {
        System.out.println("[Argus Debug] Metaspace Event: " + event.getEventType().getName());
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
