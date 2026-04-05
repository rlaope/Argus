package io.argus.core.event;

import java.time.Instant;

/**
 * Represents an execution sample event captured by the Argus agent.
 *
 * <p>This event is generated from {@code jdk.ExecutionSample} JFR events
 * which are periodic CPU samples for profiling hot methods.
 *
 * @param timestamp   the event timestamp
 * @param threadId    the thread ID being sampled
 * @param threadName  the thread name
 * @param methodName  the method name at the top of the stack
 * @param className   the class name containing the method
 * @param lineNumber  the line number in the source file
 * @param stackTrace  the full stack trace
 */
public final class ExecutionSampleEvent {
    private final Instant timestamp;
    private final long threadId;
    private final String threadName;
    private final String methodName;
    private final String className;
    private final int lineNumber;
    private final String stackTrace;

    public ExecutionSampleEvent(Instant timestamp, long threadId, String threadName,
                                String methodName, String className, int lineNumber,
                                String stackTrace) {
        this.timestamp = timestamp;
        this.threadId = threadId;
        this.threadName = threadName;
        this.methodName = methodName;
        this.className = className;
        this.lineNumber = lineNumber;
        this.stackTrace = stackTrace;
    }

    public Instant timestamp() { return timestamp; }
    public long threadId() { return threadId; }
    public String threadName() { return threadName; }
    public String methodName() { return methodName; }
    public String className() { return className; }
    public int lineNumber() { return lineNumber; }
    public String stackTrace() { return stackTrace; }

    /**
     * Creates an execution sample event.
     *
     * @param timestamp   the event timestamp
     * @param threadId    the thread ID
     * @param threadName  the thread name
     * @param methodName  the method name
     * @param className   the class name
     * @param lineNumber  the line number
     * @param stackTrace  the full stack trace
     * @return the execution sample event
     */
    public static ExecutionSampleEvent of(Instant timestamp, long threadId, String threadName,
                                          String methodName, String className, int lineNumber,
                                          String stackTrace) {
        return new ExecutionSampleEvent(timestamp, threadId, threadName, methodName,
                className, lineNumber, stackTrace);
    }

    /**
     * Returns the fully qualified method name (class.method).
     *
     * @return fully qualified method name
     */
    public String fullyQualifiedMethod() {
        return className + "." + methodName;
    }

    /**
     * Returns the package name from the class name.
     *
     * @return package name or empty string if no package
     */
    public String packageName() {
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(0, lastDot) : "";
    }
}
