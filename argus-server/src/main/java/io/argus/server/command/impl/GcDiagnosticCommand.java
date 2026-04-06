package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

public final class GcDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "gc"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "Garbage collector statistics: count and time"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("GC Statistics"));
        sb.append(String.format("%-30s %10s %12s%n", "Collector", "Count", "Time (ms)"));
        sb.append("─────────────────────────────────────────────────────\n");

        long totalCount = 0, totalTime = 0;
        for (GarbageCollectorMXBean gc : gcs) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            sb.append(String.format("%-30s %,10d %,12d%n", gc.getName(),
                    count < 0 ? 0 : count, time < 0 ? 0 : time));
            if (count > 0) totalCount += count;
            if (time > 0) totalTime += time;
        }
        sb.append("─────────────────────────────────────────────────────\n");
        sb.append(String.format("%-30s %,10d %,12d%n", "Total", totalCount, totalTime));
        return sb.toString();
    }
}
