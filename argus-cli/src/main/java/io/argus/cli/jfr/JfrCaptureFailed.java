package io.argus.cli.jfr;

/**
 * Thrown by {@link JfrCaptureSession#capture} when a JFR recording cannot
 * be started, dumped, or written. The message describes which stage failed;
 * callers decide how to surface it (stderr message, exit code).
 */
public final class JfrCaptureFailed extends Exception {

    public JfrCaptureFailed(String message) {
        super(message);
    }
}
