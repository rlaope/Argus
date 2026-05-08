package io.argus.cli.command;

import io.argus.cli.config.Messages;

/**
 * Shared helpers for argus CLI commands.
 *
 * <p>Centralizes the &quot;parse a positional &lt;pid&gt; or fail with a useful
 * error&quot; pattern that almost every command needs. Before this helper
 * existed, each command had its own copy of the parse-and-validate boilerplate,
 * and most of them swallowed bad-PID failures silently (returning early
 * without an exit code), so CI scripts could not distinguish &quot;PID not
 * found&quot; from &quot;everything fine.&quot;
 */
public final class CommandUtils {

    private CommandUtils() {}

    /**
     * Resolves the first positional argument as a JVM PID, validating that
     * the process actually exists. Throws {@link CommandExitException} on any
     * failure so the CLI exit code reflects the problem.
     *
     * <ul>
     *   <li>No args: {@code error.pid.required} → exit 2</li>
     *   <li>Non-numeric: {@code error.pid.invalid} → exit 2</li>
     *   <li>Numeric but no such process: {@code error.pid.notfound} → exit 1</li>
     * </ul>
     */
    public static long parsePidOrExit(String[] args, Messages messages) {
        if (args.length == 0) {
            System.err.println(messages.get("error.pid.required"));
            throw new CommandExitException(2);
        }
        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[0]));
            throw new CommandExitException(2);
        }
        if (!ProcessHandle.of(pid).isPresent()) {
            System.err.println(messages.get("error.pid.notfound", pid));
            throw new CommandExitException(1);
        }
        return pid;
    }
}
