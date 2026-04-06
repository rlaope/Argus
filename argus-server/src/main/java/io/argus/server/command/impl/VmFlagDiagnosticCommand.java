package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.util.List;

public final class VmFlagDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "vmflag"; }
    @Override public CommandGroup group() { return CommandGroup.PROCESS; }
    @Override public String description() { return "JVM input arguments and flags"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("JVM Flags"));
        sb.append(String.format("Argument count: %d%n%n", args.size()));
        for (String arg : args) {
            sb.append("  ").append(arg).append('\n');
        }
        if (args.isEmpty()) {
            sb.append("  (no JVM arguments)");
        }
        return sb.toString();
    }
}
