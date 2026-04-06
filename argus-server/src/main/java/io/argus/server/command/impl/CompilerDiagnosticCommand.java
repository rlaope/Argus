package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;

public final class CompilerDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "compiler"; }
    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }
    @Override public String description() { return "JIT compiler name and total compilation time"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        CompilationMXBean comp = ManagementFactory.getCompilationMXBean();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("JIT Compiler"));
        sb.append(String.format("%-30s %s%n", "Compiler name:", comp.getName()));
        if (comp.isCompilationTimeMonitoringSupported()) {
            long ms = comp.getTotalCompilationTime();
            sb.append(String.format("%-30s %,d ms%n", "Total compilation time:", ms));
        } else {
            sb.append(String.format("%-30s %s%n", "Total compilation time:", "monitoring not supported"));
        }
        return sb.toString();
    }
}
