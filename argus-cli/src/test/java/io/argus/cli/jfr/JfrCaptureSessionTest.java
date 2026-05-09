package io.argus.cli.jfr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JfrCaptureSessionTest {

    /**
     * Capturing from a non-existent PID must surface as {@link JfrCaptureFailed}
     * (the JVM's jcmd will not be able to attach), not as a generic IOException
     * or a partial recording. The temp file must be cleaned up.
     */
    @Test
    void unreachablePidThrowsJfrCaptureFailed() throws IOException {
        long bogusPid = 99_999_999L;
        Path tempDirBefore = listTempJfrFiles();

        JfrCaptureFailed ex = assertThrows(JfrCaptureFailed.class, () ->
                JfrCaptureSession.capture(bogusPid, "argus-jfr-test", "default", 1, "argus-jfrtest-"));

        assertNotNull(ex.getMessage(), "failed message should not be null");

        Path tempDirAfter = listTempJfrFiles();
        assertEquals(tempDirBefore, tempDirAfter,
                "no leftover argus-jfrtest- temp files after a failed capture");
    }

    /**
     * The {@link JfrCaptureSession.Capture} returned on success must implement
     * AutoCloseable in a way that deletes its temp file.
     */
    @Test
    void captureCloseDeletesTempFile() throws IOException {
        Path tmp = Files.createTempFile("argus-jfrtest-close-", ".jfr");
        Files.writeString(tmp, "fake recording");

        try (JfrCaptureSession.Capture capture = new JfrCaptureSession.Capture(tmp)) {
            assertTrue(Files.exists(capture.file()), "file present while capture is open");
        }

        assertFalse(Files.exists(tmp), "file deleted after close()");
    }

    /** Returns the count of leftover argus-jfrtest- temp files for leak checks. */
    private static Path listTempJfrFiles() throws IOException {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        long count;
        try (var stream = Files.list(tmpDir)) {
            count = stream.filter(p -> p.getFileName().toString().startsWith("argus-jfrtest-"))
                    .count();
        }
        // Encode count as a sentinel path so assertEquals on Path values compares the count.
        return Path.of(String.valueOf(count));
    }
}
