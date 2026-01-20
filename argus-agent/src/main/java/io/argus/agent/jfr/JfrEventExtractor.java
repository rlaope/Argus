package io.argus.agent.jfr;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

/**
 * Extracts field values from JFR RecordedEvent objects.
 *
 * <p>This class handles the complexity of extracting thread information
 * from JFR events, including fallback logic for different JDK versions
 * and event types.
 */
public final class JfrEventExtractor {

    /**
     * Extracts the thread ID from a JFR event.
     *
     * <p>Tries multiple field names for compatibility across JDK versions:
     * <ol>
     *   <li>javaThreadId field</li>
     *   <li>eventThread field</li>
     *   <li>thread field</li>
     * </ol>
     *
     * @param event the JFR event
     * @return thread ID, or -1 if not found
     */
    public long extractThreadId(RecordedEvent event) {
        // Try javaThreadId field first
        try {
            return event.getLong("javaThreadId");
        } catch (Exception ignored) {
        }

        // Try eventThread field
        try {
            RecordedThread thread = event.getValue("eventThread");
            if (thread != null) {
                return thread.getJavaThreadId();
            }
        } catch (Exception ignored) {
        }

        // Try thread field as fallback
        try {
            RecordedThread thread = event.getValue("thread");
            if (thread != null) {
                return thread.getJavaThreadId();
            }
        } catch (Exception ignored) {
        }

        return -1;
    }

    /**
     * Extracts the thread name from a JFR event.
     *
     * <p>Tries multiple field names for compatibility:
     * <ol>
     *   <li>eventThread field</li>
     *   <li>thread field</li>
     * </ol>
     *
     * @param event the JFR event
     * @return thread name, or null if not found
     */
    public String extractThreadName(RecordedEvent event) {
        // Try eventThread field
        try {
            RecordedThread thread = event.getValue("eventThread");
            if (thread != null) {
                return thread.getJavaName();
            }
        } catch (Exception ignored) {
        }

        // Try thread field as fallback
        try {
            RecordedThread thread = event.getValue("thread");
            if (thread != null) {
                return thread.getJavaName();
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * Extracts the carrier thread ID from a pinned event.
     *
     * @param event the JFR event
     * @return carrier thread ID, or -1 if not found
     */
    public long extractCarrierThreadId(RecordedEvent event) {
        try {
            RecordedThread carrier = event.getValue("carrierThread");
            if (carrier != null) {
                return carrier.getJavaThreadId();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * Formats a stack trace for display.
     *
     * @param stackTrace the recorded stack trace
     * @return formatted string, or null if stack trace is null
     */
    public String formatStackTrace(RecordedStackTrace stackTrace) {
        if (stackTrace == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        stackTrace.getFrames().forEach(frame -> {
            sb.append(frame.getMethod().getType().getName())
                    .append(".")
                    .append(frame.getMethod().getName())
                    .append("(")
                    .append(frame.getLineNumber())
                    .append(")\n");
        });
        return sb.toString();
    }
}
