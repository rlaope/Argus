package io.argus.cli.model;

/**
 * Information about a running JVM process.
 */
public final class ProcessInfo {
    private final long pid;
    private final String mainClass;
    private final String arguments;
    private final String javaVersion;
    private final long uptimeMs;

    public ProcessInfo(long pid, String mainClass, String arguments) {
        this(pid, mainClass, arguments, "", 0);
    }

    public ProcessInfo(long pid, String mainClass, String arguments, String javaVersion, long uptimeMs) {
        this.pid = pid;
        this.mainClass = mainClass;
        this.arguments = arguments;
        this.javaVersion = javaVersion;
        this.uptimeMs = uptimeMs;
    }

    public long pid() { return pid; }
    public String mainClass() { return mainClass; }
    public String arguments() { return arguments; }
    public String javaVersion() { return javaVersion; }
    public long uptimeMs() { return uptimeMs; }
}
