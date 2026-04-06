package io.argus.server.command;

import io.argus.core.command.CommandContext;

/**
 * In-process command context for the Argus server.
 * Commands executed here run inside the monitored JVM with direct MXBean access.
 */
public final class ServerContext implements CommandContext.InProcess {

    private static final long PID = ProcessHandle.current().pid();

    @Override
    public long pid() {
        return PID;
    }
}
