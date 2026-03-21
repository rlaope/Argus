package io.argus.cli.provider.jdk;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shared utility for executing jcmd diagnostic commands.
 */
public final class JcmdExecutor {

    private static final int TIMEOUT_SECONDS = 10;
    private static volatile Boolean jcmdAvailable;

    private JcmdExecutor() {}

    /**
     * Executes a jcmd command against a specific PID.
     *
     * @param pid     the target JVM process ID
     * @param command the jcmd sub-command (e.g. "GC.class_histogram")
     * @return stdout output of the command
     * @throws RuntimeException if the process fails or times out
     */
    public static String execute(long pid, String command) {
        return runProcess("jcmd", String.valueOf(pid), command);
    }

    /**
     * Executes a jcmd command without a PID (e.g. "jcmd -l").
     *
     * @param command the jcmd argument (e.g. "-l")
     * @return stdout output of the command
     * @throws RuntimeException if the process fails or times out
     */
    public static String execute(String command) {
        return runProcess("jcmd", command);
    }

    /**
     * Checks whether jcmd is available on this system.
     *
     * @return true if jcmd exits successfully
     */
    public static boolean isJcmdAvailable() {
        Boolean cached = jcmdAvailable;
        if (cached != null) return cached;
        boolean result;
        try {
            Process process = new ProcessBuilder("jcmd", "-l")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result = false;
            } else {
                result = process.exitValue() == 0;
            }
        } catch (Exception e) {
            result = false;
        }
        jcmdAvailable = result;
        return result;
    }

    private static String runProcess(String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("jcmd timed out after " + TIMEOUT_SECONDS + "s: " + String.join(" ", args));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("jcmd exited with code " + exitCode + ": " + output);
            }

            return output;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute jcmd: " + String.join(" ", args), e);
        }
    }
}
