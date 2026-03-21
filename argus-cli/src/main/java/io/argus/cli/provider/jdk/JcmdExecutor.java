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
        try {
            Process process = new ProcessBuilder("jcmd", "-l")
                    .redirectErrorStream(false)
                    .start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String runProcess(String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Read stdout and stderr concurrently to avoid blocking
            String stdout;
            String stderr;
            try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

                // Capture both streams; stderr is captured for error messages
                stdout = outReader.lines().collect(Collectors.joining("\n"));
                stderr = errReader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("jcmd timed out after " + TIMEOUT_SECONDS + "s: " + String.join(" ", args));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String msg = stderr.isEmpty() ? stdout : stderr;
                throw new RuntimeException("jcmd exited with code " + exitCode + ": " + msg);
            }

            return stdout;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute jcmd: " + String.join(" ", args), e);
        }
    }
}
