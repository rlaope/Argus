package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

public final class ClassStatDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "classstat"; }
    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }
    @Override public String description() { return "Class statistics with metaspace usage"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("Class Statistics"));
        sb.append(String.format("%-30s %,d%n", "Currently loaded:", cl.getLoadedClassCount()));
        sb.append(String.format("%-30s %,d%n", "Total loaded:", cl.getTotalLoadedClassCount()));
        sb.append(String.format("%-30s %,d%n", "Unloaded:", cl.getUnloadedClassCount()));
        sb.append('\n');

        sb.append("Metaspace:\n");
        sb.append("─────────────────────────────────────────────────────────────\n");
        sb.append(String.format("  %-33s %12s %12s %12s%n", "Pool", "Used", "Committed", "Max"));
        for (MemoryPoolMXBean pool : pools) {
            String nameLower = pool.getName().toLowerCase();
            if (nameLower.contains("metaspace") || nameLower.contains("compressed")) {
                MemoryUsage usage = pool.getUsage();
                if (usage == null) continue;
                sb.append(String.format("  %-33s %12s %12s %12s%n",
                        pool.getName(),
                        DiagnosticUtil.formatBytes(usage.getUsed()),
                        DiagnosticUtil.formatBytes(usage.getCommitted()),
                        DiagnosticUtil.formatBytes(usage.getMax())));
            }
        }
        return sb.toString();
    }
}
