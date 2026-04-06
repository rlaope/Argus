package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;

public final class ClassLoaderDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "classloader"; }
    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }
    @Override public String description() { return "Class loading statistics: loaded, total, unloaded"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("Class Loader"));
        sb.append(String.format("%-30s %,d%n", "Currently loaded classes:", cl.getLoadedClassCount()));
        sb.append(String.format("%-30s %,d%n", "Total loaded classes:", cl.getTotalLoadedClassCount()));
        sb.append(String.format("%-30s %,d%n", "Unloaded classes:", cl.getUnloadedClassCount()));
        return sb.toString();
    }
}
