package io.argus.cli.provider.jdk;

import io.argus.cli.model.EnvResult;
import io.argus.cli.provider.EnvProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * EnvProvider that uses {@code jcmd VM.command_line} and {@code VM.system_properties}
 * to obtain JVM launch environment.
 */
public final class JdkEnvProvider implements EnvProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public EnvResult getEnv(long pid) {
        String cmdLineOutput;
        String propsOutput;
        try {
            cmdLineOutput = JcmdExecutor.execute(pid, "VM.command_line");
        } catch (RuntimeException e) {
            cmdLineOutput = "";
        }
        try {
            propsOutput = JcmdExecutor.execute(pid, "VM.system_properties");
        } catch (RuntimeException e) {
            propsOutput = "";
        }
        return parseOutput(cmdLineOutput, propsOutput);
    }

    static EnvResult parseOutput(String cmdLineOutput, String propsOutput) {
        String commandLine = "";
        List<String> vmArgs = new ArrayList<>();

        // Parse VM.command_line output
        for (String line : cmdLineOutput.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("jvm_args:")) {
                String argsStr = trimmed.substring(9).trim();
                if (!argsStr.isEmpty()) {
                    for (String arg : argsStr.split("\\s+")) {
                        if (!arg.isEmpty()) vmArgs.add(arg);
                    }
                }
            } else if (trimmed.startsWith("java_command:")) {
                commandLine = trimmed.substring(13).trim();
            }
        }

        // Parse system properties for env info
        String javaHome = "";
        String classPath = "";
        String workingDir = "";

        for (String line : propsOutput.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("java.home=") || trimmed.startsWith("java.home =")) {
                javaHome = extractPropertyValue(trimmed);
            } else if (trimmed.startsWith("java.class.path=") || trimmed.startsWith("java.class.path =")) {
                classPath = extractPropertyValue(trimmed);
            } else if (trimmed.startsWith("user.dir=") || trimmed.startsWith("user.dir =")) {
                workingDir = extractPropertyValue(trimmed);
            }
        }

        return new EnvResult(commandLine, javaHome, classPath, workingDir, List.copyOf(vmArgs));
    }

    private static String extractPropertyValue(String line) {
        int eqIdx = line.indexOf('=');
        if (eqIdx < 0) return "";
        return line.substring(eqIdx + 1).trim();
    }
}
