package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

public final class CompilerQueueDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "compilerqueue"; }
    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }
    @Override public String description() { return "JIT compilation queue"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        try {
            long pid = ((CommandContext.InProcess) ctx).pid();
            ProcessBuilder pb = new ProcessBuilder("jcmd", String.valueOf(pid), "Compiler.queue");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return output.isBlank() ? "Compilation queue is empty" : output;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
