package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

public final class ThreadDumpDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "threaddump"; }
    @Override public CommandGroup group() { return CommandGroup.THREADS; }
    @Override public String description() { return "Full thread dump with stack traces"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threads = tmx.dumpAllThreads(true, true);

        StringBuilder sb = new StringBuilder();
        sb.append("Thread count: ").append(tmx.getThreadCount());
        sb.append(" (daemon: ").append(tmx.getDaemonThreadCount());
        sb.append(", peak: ").append(tmx.getPeakThreadCount()).append(")\n\n");

        for (ThreadInfo ti : threads) {
            sb.append('"').append(ti.getThreadName()).append('"');
            sb.append(" #").append(ti.getThreadId());
            if (ti.isDaemon()) sb.append(" daemon");
            sb.append(" prio=").append(ti.getPriority());
            sb.append(" [").append(ti.getThreadState()).append("]\n");

            for (StackTraceElement ste : ti.getStackTrace()) {
                sb.append("    at ").append(ste).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
