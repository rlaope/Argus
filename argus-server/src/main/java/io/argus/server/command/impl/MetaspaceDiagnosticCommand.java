package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

public final class MetaspaceDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "metaspace"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "Metaspace and compressed class space usage"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("Metaspace"));
        sb.append(String.format("%-35s %12s %12s %12s %12s%n",
                "Pool", "Init", "Used", "Committed", "Max"));
        sb.append("───────────────────────────────────────────────────────────────────────\n");

        boolean found = false;
        for (MemoryPoolMXBean pool : pools) {
            String nameLower = pool.getName().toLowerCase();
            if (nameLower.contains("metaspace") || nameLower.contains("compressed")) {
                MemoryUsage usage = pool.getUsage();
                if (usage == null) continue;
                sb.append(String.format("%-35s %12s %12s %12s %12s%n",
                        pool.getName(),
                        DiagnosticUtil.formatBytes(usage.getInit()),
                        DiagnosticUtil.formatBytes(usage.getUsed()),
                        DiagnosticUtil.formatBytes(usage.getCommitted()),
                        DiagnosticUtil.formatBytes(usage.getMax())));
                found = true;
            }
        }
        if (!found) {
            sb.append("No metaspace pools found.\n");
        }
        return sb.toString();
    }
}
