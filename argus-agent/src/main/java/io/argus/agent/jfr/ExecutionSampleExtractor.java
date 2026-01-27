package io.argus.agent.jfr;

import io.argus.core.event.ExecutionSampleEvent;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

import java.time.Instant;

/**
 * Extracts execution sample event data from JFR RecordedEvent objects.
 *
 * <p>This class handles extraction of execution sample information
 * from the {@code jdk.ExecutionSample} JFR event for CPU profiling.
 */
public final class ExecutionSampleExtractor {

    /**
     * Extracts an ExecutionSampleEvent from a jdk.ExecutionSample JFR event.
     *
     * @param event the JFR event
     * @return the extracted ExecutionSampleEvent, or null if stack trace is empty
     */
    public ExecutionSampleEvent extractExecutionSample(RecordedEvent event) {
        RecordedStackTrace stackTrace = event.getStackTrace();
        if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
            return null;
        }

        Instant timestamp = event.getStartTime();
        long threadId = extractThreadId(event);
        String threadName = extractThreadName(event);

        // Get the top frame
        RecordedFrame topFrame = stackTrace.getFrames().getFirst();
        String methodName = extractMethodName(topFrame);
        String className = extractClassName(topFrame);
        int lineNumber = topFrame.getLineNumber();

        String fullStackTrace = formatStackTrace(stackTrace);

        return ExecutionSampleEvent.of(timestamp, threadId, threadName,
                methodName, className, lineNumber, fullStackTrace);
    }

    private long extractThreadId(RecordedEvent event) {
        // Try sampledThread.javaThreadId
        try {
            return event.getLong("sampledThread.javaThreadId");
        } catch (Exception ignored) {
        }

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
        // Try sampledThread.javaName
        try {
            var thread = event.getValue("sampledThread");
            if (thread != null) {
                return event.getString("sampledThread.javaName");
            }
        } catch (Exception ignored) {
        }

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

    private String extractMethodName(RecordedFrame frame) {
        try {
            var method = frame.getMethod();
            if (method != null) {
                return method.getName();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private String extractClassName(RecordedFrame frame) {
        try {
            var method = frame.getMethod();
            if (method != null && method.getType() != null) {
                return method.getType().getName();
            }
        } catch (Exception ignored) {
        }
        return "Unknown";
    }

    private String formatStackTrace(RecordedStackTrace stackTrace) {
        if (stackTrace == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (RecordedFrame frame : stackTrace.getFrames()) {
            try {
                var method = frame.getMethod();
                if (method != null) {
                    String className = method.getType() != null ? method.getType().getName() : "Unknown";
                    String methodName = method.getName();
                    int lineNumber = frame.getLineNumber();

                    sb.append("    at ").append(className).append(".")
                            .append(methodName).append("(");
                    if (lineNumber >= 0) {
                        sb.append("line:").append(lineNumber);
                    } else {
                        sb.append("Unknown Source");
                    }
                    sb.append(")\n");
                }
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    /**
     * Debug method to print all available fields in an execution sample JFR event.
     *
     * @param event the JFR event
     */
    public void debugPrintFields(RecordedEvent event) {
        System.out.println("[Argus Debug] Execution Sample Event: " + event.getEventType().getName());
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
