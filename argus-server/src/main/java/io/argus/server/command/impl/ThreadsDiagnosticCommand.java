package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ThreadsDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "threads"; }
    @Override public CommandGroup group() { return CommandGroup.THREADS; }
    @Override public String description() { return "Thread counts and state distribution"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("Thread Summary"));
        sb.append(String.format("%-28s %d%n", "Live threads:", tmx.getThreadCount()));
        sb.append(String.format("%-28s %d%n", "Daemon threads:", tmx.getDaemonThreadCount()));
        sb.append(String.format("%-28s %d%n", "Peak thread count:", tmx.getPeakThreadCount()));
        sb.append(String.format("%-28s %d%n", "Total started:", tmx.getTotalStartedThreadCount()));
        sb.append('\n');

        ThreadInfo[] threads = tmx.dumpAllThreads(false, false);
        Map<Thread.State, Integer> stateCounts = new LinkedHashMap<>();
        for (Thread.State s : Thread.State.values()) stateCounts.put(s, 0);
        for (ThreadInfo ti : threads) {
            stateCounts.merge(ti.getThreadState(), 1, Integer::sum);
        }

        sb.append("State Distribution:\n");
        sb.append("─────────────────────────────────\n");
        for (var entry : stateCounts.entrySet()) {
            if (entry.getValue() > 0) {
                sb.append(String.format("  %-20s %d%n", entry.getKey(), entry.getValue()));
            }
        }
        return sb.toString();
    }
}
