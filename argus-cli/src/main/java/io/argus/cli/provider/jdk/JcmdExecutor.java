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
        return stripPidEcho(runProcess("jcmd", String.valueOf(pid), command), pid);
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
     * Runs {@code jcmd <pid> <subCommand> [extraArgs...]} with a 30-second timeout
     * and returns stdout, or {@code null} on any failure. This is a fire-and-forget
     * helper used by commands that start, dump, and stop JFR recordings.
     *
     * @param pid        the target JVM process ID
     * @param subCommand the jcmd sub-command (e.g. "JFR.start")
     * @param extraArgs  additional key=value arguments passed to the sub-command
     * @return stdout of the command, or {@code null} if execution fails
     */
    public static String runJcmd(long pid, String subCommand, String... extraArgs) {
        try {
            String[] fullCmd = new String[3 + extraArgs.length];
            fullCmd[0] = "jcmd";
            fullCmd[1] = String.valueOf(pid);
            fullCmd[2] = subCommand;
            System.arraycopy(extraArgs, 0, fullCmd, 3, extraArgs.length);
            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            return stripPidEcho(out, pid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * jcmd echoes "{@code <pid>:}" as the first line of every response. Strip it
     * here so providers don't all need to repeat the same defensive check
     * (which historically led to "VM Name = <pid>:" leaking into argus info).
     */
    private static String stripPidEcho(String output, long pid) {
        if (output == null || output.isEmpty()) return output;
        String prefix = pid + ":";
        int newline = output.indexOf('\n');
        String firstLine = newline >= 0 ? output.substring(0, newline) : output;
        if (firstLine.trim().equals(prefix)) {
            return newline >= 0 ? output.substring(newline + 1) : "";
        }
        return output;
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

            // Read stdout in a separate thread so waitFor timeout actually works
            StringBuilder outputBuf = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (outputBuf.length() > 0) outputBuf.append('\n');
                        outputBuf.append(line);
                    }
                } catch (Exception ignored) {}
            }, "jcmd-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                throw new RuntimeException("jcmd timed out after " + TIMEOUT_SECONDS + "s: " + String.join(" ", args));
            }

            reader.join(2000);
            String output = outputBuf.toString();

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
