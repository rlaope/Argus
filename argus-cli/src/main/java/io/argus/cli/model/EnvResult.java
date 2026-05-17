package io.argus.cli.model;

import io.argus.diagnostics.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * JVM launch environment result.
 */
public final class EnvResult implements JsonWritable {
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

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"commandLine\":\"").append(RichRenderer.escapeJson(commandLine)).append('"')
           .append(",\"javaHome\":\"").append(RichRenderer.escapeJson(javaHome)).append('"')
           .append(",\"classPath\":\"").append(RichRenderer.escapeJson(classPath)).append('"')
           .append(",\"workingDir\":\"").append(RichRenderer.escapeJson(workingDir)).append('"')
           .append(",\"vmArgs\":[");
        for (int i = 0; i < vmArgs.size(); i++) {
            if (i > 0) out.append(',');
            out.append('"').append(RichRenderer.escapeJson(vmArgs.get(i))).append('"');
        }
        out.append("]}");
    }
}
