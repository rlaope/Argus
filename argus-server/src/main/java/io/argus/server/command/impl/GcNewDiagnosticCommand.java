package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

public final class GcNewDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "gcnew"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "Young generation memory pool statistics"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("Young Gen / New Space"));
        sb.append(String.format("%-30s %12s %12s %12s %12s%n",
                "Pool", "Init", "Used", "Committed", "Max"));
        sb.append("────────────────────────────────────────────────────────────────────\n");

        boolean found = false;
        for (MemoryPoolMXBean pool : pools) {
            String nameLower = pool.getName().toLowerCase();
            if (nameLower.contains("eden") || nameLower.contains("survivor")
                    || nameLower.contains("young") || nameLower.contains("nursery")) {
                MemoryUsage usage = pool.getUsage();
                if (usage == null) continue;
                sb.append(String.format("%-30s %12s %12s %12s %12s%n",
                        pool.getName(),
                        DiagnosticUtil.formatBytes(usage.getInit()),
                        DiagnosticUtil.formatBytes(usage.getUsed()),
                        DiagnosticUtil.formatBytes(usage.getCommitted()),
                        DiagnosticUtil.formatBytes(usage.getMax())));
                found = true;
            }
        }
        if (!found) {
            sb.append("No young generation pools found (may depend on GC algorithm).\n");
        }
        return sb.toString();
    }
}
