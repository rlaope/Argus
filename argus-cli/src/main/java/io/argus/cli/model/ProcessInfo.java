package io.argus.cli.model;

/**
 * Information about a running JVM process.
 */
public final class ProcessInfo {
    private final long pid;
    private final String mainClass;
    private final String arguments;

    public ProcessInfo(long pid, String mainClass, String arguments) {
        this.pid = pid;
        this.mainClass = mainClass;
        this.arguments = arguments;
    }

    public long pid() { return pid; }
    public String mainClass() { return mainClass; }
    public String arguments() { return arguments; }
}
