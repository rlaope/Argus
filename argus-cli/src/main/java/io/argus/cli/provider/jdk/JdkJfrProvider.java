package io.argus.cli.provider.jdk;

import io.argus.cli.model.JfrResult;
import io.argus.cli.provider.JfrProvider;

/**
 * Provides JFR Flight Recorder control via {@code jcmd JFR.*} commands.
 */
public final class JdkJfrProvider implements JfrProvider {

    private static final String DEFAULT_FILENAME = "argus-recording.jfr";
    private static final int DEFAULT_DURATION_SEC = 60;

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String source() {
        return "jdk";
    }

    @Override
    public JfrResult startRecording(long pid, int durationSec, String filename) {
        int duration = durationSec > 0 ? durationSec : DEFAULT_DURATION_SEC;
        String file = (filename != null && !filename.isBlank()) ? filename : DEFAULT_FILENAME;
        String command = "JFR.start duration=" + duration + "s filename=" + file;
        return runCommand(pid, command);
    }

    @Override
    public JfrResult stopRecording(long pid) {
        return runCommand(pid, "JFR.stop");
    }

    @Override
    public JfrResult checkRecording(long pid) {
        return runCommand(pid, "JFR.check");
    }

    @Override
    public JfrResult dumpRecording(long pid, String filename) {
        String file = (filename != null && !filename.isBlank()) ? filename : DEFAULT_FILENAME;
        String command = "JFR.dump filename=" + file;
        return runCommand(pid, command);
    }

    private static JfrResult runCommand(long pid, String command) {
        try {
            String output = JcmdExecutor.execute(pid, command);
            String trimmed = output.trim();
            if (trimmed.isEmpty()) {
                return new JfrResult("ok", "Command completed (no output)", null);
            }
            // First non-empty line is treated as the primary message
            String[] lines = trimmed.split("\n");
            String firstLine = lines[0].trim();
            String recordingInfo = lines.length > 1 ? trimmed : null;
            return new JfrResult("ok", firstLine, recordingInfo);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getSimpleName();
            return new JfrResult("error", msg, null);
        }
    }
}
