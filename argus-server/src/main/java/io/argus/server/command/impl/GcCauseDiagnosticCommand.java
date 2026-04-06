package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

public final class GcCauseDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "gccause"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "GC cause info per collector (count, time, memory pools)"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("GC Cause"));
        sb.append("Note: detailed cause tracking requires JMX dashboard or GC logging (-Xlog:gc*).\n\n");
        sb.append(String.format("%-30s %10s %12s %-30s%n",
                "Collector", "Count", "Time (ms)", "Memory Pools"));
        sb.append("────────────────────────────────────────────────────────────────────────────\n");

        for (GarbageCollectorMXBean gc : gcs) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            String pools = String.join(", ", gc.getMemoryPoolNames());
            sb.append(String.format("%-30s %,10d %,12d %-30s%n",
                    gc.getName(),
                    count < 0 ? 0 : count,
                    time < 0 ? 0 : time,
                    pools));
        }
        return sb.toString();
    }
}
