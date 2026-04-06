package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

public final class GcUtilDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "gcutil"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "Memory pool utilization summary"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("GC Util"));
        sb.append(String.format("%-30s %12s %12s %8s%n", "Pool", "Used", "Max", "Util%"));
        sb.append("─────────────────────────────────────────────────────────\n");

        for (MemoryPoolMXBean pool : pools) {
            MemoryUsage usage = pool.getUsage();
            if (usage == null) continue;
            long used = usage.getUsed();
            long max = usage.getMax();
            String pct = max > 0 ? String.format("%.1f%%", (used * 100.0) / max) : "N/A";
            sb.append(String.format("%-30s %12s %12s %8s%n",
                    pool.getName(),
                    DiagnosticUtil.formatBytes(used),
                    DiagnosticUtil.formatBytes(max),
                    pct));
        }
        return sb.toString();
    }
}
