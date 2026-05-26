package io.argus.server.command;

/**
 * Thrown by {@link ServerCommandExecutor#execute(String)} when a caller invokes a command
 * whose {@link io.argus.core.command.DiagnosticCommand#supportsWebConsole()} returns false.
 *
 * <p>Distinct from generic execution errors so the HTTP handler can return HTTP 403
 * and the frontend can render the message in its error channel (not success styling).
 */
public final class WebConsoleRejectedException extends RuntimeException {
    public WebConsoleRejectedException(String commandId) {
        super("Command '" + commandId + "' is not available via the web console.");
    }
}
