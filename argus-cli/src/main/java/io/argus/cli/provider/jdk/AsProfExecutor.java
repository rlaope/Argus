package io.argus.cli.provider.jdk;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Process executor for long-running async-profiler invocations.
 * Unlike JcmdExecutor (10s timeout, blocking stdout), this uses dedicated I/O threads
 * and configurable timeouts for profiles lasting 30-300 seconds.
 */
public final class AsProfExecutor {

    private AsProfExecutor() {}

    /**
     * Result of an async-profiler invocation.
     */
    public static final class Result {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        public Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int exitCode() {
            return exitCode;
        }

        public String stdout() {
            return stdout;
        }

        public String stderr() {
            return stderr;
        }
    }

    /**
     * Callback invoked approximately every second during profiling.
     */
    public interface ProgressCallback {
        void onProgress(int elapsedSeconds, int totalSeconds);
    }

    /**
     * Executes a command with dedicated I/O threads and configurable timeout.
     * The calling thread runs a progress polling loop while I/O threads read stdout/stderr.
     *
     * @param command        the command and arguments to execute
     * @param timeoutSeconds maximum wall-clock seconds before forcible termination
     * @param callback       receives elapsed/total progress updates every ~1s (may be null)
     * @return Result containing exit code, stdout, and stderr
     */
    public static Result execute(String[] command, int timeoutSeconds, ProgressCallback callback) {
        final List<String> stdoutLines = new ArrayList<>();
        final List<String> stderrLines = new ArrayList<>();

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            process = pb.start();
        } catch (Exception e) {
            return new Result(-1, "", "Failed to start process: " + e.getMessage());
        }

        // Daemon thread draining stdout
        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stdoutLines) {
                        stdoutLines.add(line);
                    }
                }
            } catch (Exception ignored) {
                // process closed stream on exit — normal
            }
        });
        stdoutThread.setDaemon(true);
        stdoutThread.setName("asprof-stdout");
        stdoutThread.start();

        // Daemon thread draining stderr
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrLines) {
                        stderrLines.add(line);
                    }
                }
            } catch (Exception ignored) {
                // process closed stream on exit — normal
            }
        });
        stderrThread.setDaemon(true);
        stderrThread.setName("asprof-stderr");
        stderrThread.start();

        // Progress loop: runs on calling thread until process exits or timeout
        long startMs = System.currentTimeMillis();
        while (process.isAlive()) {
            int elapsedSeconds = (int) ((System.currentTimeMillis() - startMs) / 1000L);
            if (elapsedSeconds >= timeoutSeconds) {
                break;
            }
            if (callback != null) {
                try {
                    callback.onProgress(elapsedSeconds, timeoutSeconds);
                } catch (Exception ignored) {
                    // never let a callback kill the executor
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Wait for process to finish within remaining timeout
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        if (!finished) {
            process.destroyForcibly();
        }

        // Join I/O threads so their buffers are fully populated
        try {
            stdoutThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            stderrThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException e) {
            exitCode = -1;
        }

        String stdout = joinLines(stdoutLines);
        String stderr = joinLines(stderrLines);

        return new Result(exitCode, stdout, stderr);
    }

    private static String joinLines(List<String> lines) {
        synchronized (lines) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(lines.get(i));
            }
            return sb.toString();
        }
    }
}
