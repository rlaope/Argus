package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

public final class HeapDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "heap"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "Heap and non-heap memory usage details"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("Heap Memory"));
        sb.append(String.format("%-14s %12s %12s %12s %12s%n",
                "", "Init", "Used", "Committed", "Max"));
        sb.append("─────────────────────────────────────────────────────────────\n");
        sb.append(String.format("%-14s %12s %12s %12s %12s%n",
                "Heap",
                DiagnosticUtil.formatBytes(heap.getInit()),
                DiagnosticUtil.formatBytes(heap.getUsed()),
                DiagnosticUtil.formatBytes(heap.getCommitted()),
                DiagnosticUtil.formatBytes(heap.getMax())));
        sb.append(String.format("%-14s %12s %12s %12s %12s%n",
                "Non-Heap",
                DiagnosticUtil.formatBytes(nonHeap.getInit()),
                DiagnosticUtil.formatBytes(nonHeap.getUsed()),
                DiagnosticUtil.formatBytes(nonHeap.getCommitted()),
                DiagnosticUtil.formatBytes(nonHeap.getMax())));
        sb.append('\n');

        long used = heap.getUsed();
        long max = heap.getMax();
        if (max > 0) {
            double pct = (used * 100.0) / max;
            int bars = (int) (pct / 5);
            String bar = "█".repeat(bars) + "░".repeat(20 - bars);
            sb.append(String.format("Heap utilization: %.1f%%%n", pct));
            sb.append(String.format("[%s] %s / %s%n", bar,
                    DiagnosticUtil.formatBytes(used), DiagnosticUtil.formatBytes(max)));
        }
        return sb.toString();
    }
}
