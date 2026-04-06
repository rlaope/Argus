package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

public final class FinalizerDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "finalizer"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "Objects pending finalization count"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        int pending = mem.getObjectPendingFinalizationCount();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("Finalizer Queue"));
        sb.append(String.format("Objects pending finalization: %d%n%n", pending));
        if (pending == 0) {
            sb.append("Finalizer queue is empty.\n");
        } else if (pending < 100) {
            sb.append("Queue size is small — normal.\n");
        } else if (pending < 1000) {
            sb.append("Warning: elevated finalizer queue. Consider avoiding finalizers.\n");
        } else {
            sb.append("Alert: large finalizer queue may indicate a memory pressure issue.\n");
        }
        return sb.toString();
    }
}
