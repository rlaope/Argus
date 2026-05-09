package io.argus.cli.jfr;

import io.argus.cli.provider.jdk.JcmdExecutor;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the {@code JFR.start → sleep → JFR.dump → JFR.stop} sequence on a
 * target JVM and writes the recording to a temp file owned by the returned
 * {@link Capture}.
 *
 * <p>Closing the {@link Capture} (e.g. via try-with-resources) deletes the
 * temp file. Failures at any stage surface as {@link JfrCaptureFailed} with a
 * description of which step broke; callers translate that into their own
 * exit-code or message convention.
 *
 * <p>The session deliberately does <em>not</em> pass {@code duration=} to
 * {@code JFR.start}: with that flag the JVM auto-stops and finalises the
 * recording at the deadline, racing the explicit {@code JFR.dump} and
 * occasionally producing an empty file. Timing is controlled by sleeping
 * inside this helper instead.
 */
public final class JfrCaptureSession {

    private JfrCaptureSession() {}

    /**
     * Captures a JFR recording.
     *
     * @param pid           target JVM PID
     * @param recordingName JFR recording name (e.g. {@code "argus-zgc"})
     * @param settings      JFR settings template ({@code "default"} or {@code "profile"})
     * @param durationSec   capture window in seconds
     * @param tmpPrefix     temp-file name prefix (e.g. {@code "argus-zgc-"})
     * @return a non-empty {@link Capture} wrapping the dumped recording
     * @throws JfrCaptureFailed if start or dump fails, the wait is interrupted,
     *                          or the recording file ends up missing/empty
     * @throws IOException if temp-file creation fails
     */
    public static Capture capture(long pid, String recordingName, String settings,
                                  int durationSec, String tmpPrefix)
            throws JfrCaptureFailed, IOException {
        Path tmpFile = Files.createTempFile(tmpPrefix + pid + "-", ".jfr");
        boolean ok = false;
        try {
            // Stop any leftover recording with the same name from a prior run.
            JcmdExecutor.runJcmd(pid, "JFR.stop", "name=" + recordingName);

            String startOut = JcmdExecutor.runJcmd(pid, "JFR.start",
                    "name=" + recordingName,
                    "settings=" + settings);
            if (failed(startOut)) {
                throw new JfrCaptureFailed("JFR.start failed: " + describe(startOut));
            }

            try {
                Thread.sleep((long) durationSec * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JfrCaptureFailed("Interrupted while waiting for JFR recording.");
            }

            String dumpOut = JcmdExecutor.runJcmd(pid, "JFR.dump",
                    "name=" + recordingName,
                    "filename=" + tmpFile.toAbsolutePath());
            if (failed(dumpOut)) {
                throw new JfrCaptureFailed("JFR.dump failed: " + describe(dumpOut));
            }
            JcmdExecutor.runJcmd(pid, "JFR.stop", "name=" + recordingName);

            if (!Files.exists(tmpFile) || Files.size(tmpFile) == 0) {
                throw new JfrCaptureFailed(
                        "JFR file is empty or was not created. The JVM may not support JFR recording.");
            }

            ok = true;
            return new Capture(tmpFile);
        } finally {
            if (!ok) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    private static boolean failed(String out) {
        return out == null
                || out.contains("Could not")
                || out.toLowerCase().contains("error");
    }

    private static String describe(String out) {
        return out == null ? "no output" : out.trim();
    }

    /** A captured recording on disk; closing deletes the temp file. */
    public static final class Capture implements Closeable {
        private final Path file;

        Capture(Path file) { this.file = file; }

        public Path file() { return file; }

        @Override
        public void close() {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
    }
}
