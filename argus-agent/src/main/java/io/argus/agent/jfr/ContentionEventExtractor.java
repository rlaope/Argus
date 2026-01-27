package io.argus.agent.jfr;

import io.argus.core.event.ContentionEvent;
import jdk.jfr.consumer.RecordedEvent;

import java.time.Instant;

/**
 * Extracts contention event data from JFR RecordedEvent objects.
 *
 * <p>This class handles extraction of thread contention information
 * from the following JFR events:
 * <ul>
 *   <li>{@code jdk.JavaMonitorEnter} - Monitor enter events</li>
 *   <li>{@code jdk.JavaMonitorWait} - Monitor wait events</li>
 * </ul>
 */
public final class ContentionEventExtractor {

    /**
     * Extracts a ContentionEvent from a jdk.JavaMonitorEnter JFR event.
     *
     * @param event the JFR event
     * @return the extracted ContentionEvent
     */
    public ContentionEvent extractMonitorEnter(RecordedEvent event) {
        Instant timestamp = event.getStartTime();
        long threadId = extractThreadId(event);
        String threadName = extractThreadName(event);
        String monitorClass = extractMonitorClass(event);
        long durationNanos = event.getDuration().toNanos();

        return ContentionEvent.enter(timestamp, threadId, threadName, monitorClass, durationNanos);
    }

    /**
     * Extracts a ContentionEvent from a jdk.JavaMonitorWait JFR event.
     *
     * @param event the JFR event
     * @return the extracted ContentionEvent
     */
    public ContentionEvent extractMonitorWait(RecordedEvent event) {
        Instant timestamp = event.getStartTime();
        long threadId = extractThreadId(event);
        String threadName = extractThreadName(event);
        String monitorClass = extractMonitorClass(event);
        long durationNanos = event.getDuration().toNanos();

        return ContentionEvent.wait(timestamp, threadId, threadName, monitorClass, durationNanos);
    }

    private long extractThreadId(RecordedEvent event) {
        // Try eventThread.javaThreadId
        try {
            return event.getLong("eventThread.javaThreadId");
        } catch (Exception ignored) {
        }

        // Try thread.javaThreadId
        try {
            return event.getLong("thread.javaThreadId");
        } catch (Exception ignored) {
        }

        return 0;
    }

    private String extractThreadName(RecordedEvent event) {
        // Try eventThread.javaName
        try {
            return event.getString("eventThread.javaName");
        } catch (Exception ignored) {
        }

        // Try thread.name
        try {
            return event.getString("thread.name");
        } catch (Exception ignored) {
        }

        return "Unknown";
    }

    private String extractMonitorClass(RecordedEvent event) {
        // Try monitorClass field (contains RecordedClass)
        try {
            var monitorClass = event.getClass("monitorClass");
            if (monitorClass != null) {
                return monitorClass.getName();
            }
        } catch (Exception ignored) {
        }

        // Try monitor.class
        try {
            return event.getString("monitor.class");
        } catch (Exception ignored) {
        }

        // Try class field
        try {
            return event.getString("class");
        } catch (Exception ignored) {
        }

        return "Unknown";
    }

    /**
     * Debug method to print all available fields in a contention JFR event.
     *
     * @param event the JFR event
     */
    public void debugPrintFields(RecordedEvent event) {
        System.out.println("[Argus Debug] Contention Event: " + event.getEventType().getName());
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
