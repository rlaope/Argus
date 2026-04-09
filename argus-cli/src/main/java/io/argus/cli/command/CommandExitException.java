package io.argus.cli.command;

/**
 * Thrown by commands that need to signal a non-zero exit code
 * without calling System.exit() directly (which would kill the TUI).
 */
public final class CommandExitException extends RuntimeException {

    private final int exitCode;

    public CommandExitException(int exitCode) {
        super("Command exited with code " + exitCode);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
