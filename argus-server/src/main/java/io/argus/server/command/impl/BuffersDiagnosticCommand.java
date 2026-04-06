package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;

public final class BuffersDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "buffers"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "NIO buffer pool statistics (direct, mapped)"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pool                      Count      Capacity        Used\n");
        sb.append("─────────────────────────────────────────────────────────\n");

        long totalCount = 0, totalCap = 0, totalUsed = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            long count = pool.getCount();
            long cap = pool.getTotalCapacity();
            long used = pool.getMemoryUsed();
            sb.append(String.format("%-25s %,6d  %,12d  %,12d\n", pool.getName(), count, cap, used));
            totalCount += count;
            totalCap += cap;
            totalUsed += used;
        }
        sb.append("─────────────────────────────────────────────────────────\n");
        sb.append(String.format("%-25s %,6d  %,12d  %,12d", "Total", totalCount, totalCap, totalUsed));
        return sb.toString();
    }
}
