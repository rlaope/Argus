package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

public final class DeadlockDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "deadlock"; }
    @Override public CommandGroup group() { return CommandGroup.THREADS; }
    @Override public String description() { return "Detect deadlocked threads and print stack traces"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("Deadlock Detection"));

        long[] deadlocked = tmx.findDeadlockedThreads();
        if (deadlocked == null || deadlocked.length == 0) {
            sb.append("No deadlocked threads detected.\n");
            return sb.toString();
        }

        sb.append(String.format("WARNING: %d deadlocked thread(s) found!%n%n", deadlocked.length));

        ThreadInfo[] infos = tmx.getThreadInfo(deadlocked, true, true);
        for (ThreadInfo ti : infos) {
            sb.append(String.format("\"%s\" #%d [%s]%n",
                    ti.getThreadName(), ti.getThreadId(), ti.getThreadState()));
            if (ti.getLockName() != null) {
                sb.append(String.format("  waiting to lock: %s%n", ti.getLockName()));
            }
            if (ti.getLockOwnerName() != null) {
                sb.append(String.format("  held by: \"%s\" #%d%n",
                        ti.getLockOwnerName(), ti.getLockOwnerId()));
            }
            sb.append("  Stack trace:\n");
            for (StackTraceElement ste : ti.getStackTrace()) {
                sb.append("    at ").append(ste).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
