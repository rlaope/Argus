package io.argus.cli.model;

import java.util.List;

/**
 * JVM launch environment result.
 */
public final class EnvResult {
    private final String commandLine;
    private final String javaHome;
    private final String classPath;
    private final String workingDir;
    private final List<String> vmArgs;

    public EnvResult(String commandLine, String javaHome, String classPath,
                     String workingDir, List<String> vmArgs) {
        this.commandLine = commandLine;
        this.javaHome = javaHome;
        this.classPath = classPath;
        this.workingDir = workingDir;
        this.vmArgs = vmArgs;
    }

    public String commandLine() { return commandLine; }
    public String javaHome() { return javaHome; }
    public String classPath() { return classPath; }
    public String workingDir() { return workingDir; }
    public List<String> vmArgs() { return vmArgs; }
}
