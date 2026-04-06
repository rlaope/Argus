package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

public final class PoolDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "pool"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "All memory pools: used, committed, max, type"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("Memory Pools"));
        sb.append(String.format("%-30s %-10s %12s %12s %12s%n",
                "Pool", "Type", "Used", "Committed", "Max"));
        sb.append("──────────────────────────────────────────────────────────────────────\n");

        for (MemoryPoolMXBean pool : pools) {
            MemoryUsage usage = pool.getUsage();
            if (usage == null) continue;
            sb.append(String.format("%-30s %-10s %12s %12s %12s%n",
                    pool.getName(),
                    pool.getType().toString(),
                    DiagnosticUtil.formatBytes(usage.getUsed()),
                    DiagnosticUtil.formatBytes(usage.getCommitted()),
                    DiagnosticUtil.formatBytes(usage.getMax())));
        }
        return sb.toString();
    }
}
