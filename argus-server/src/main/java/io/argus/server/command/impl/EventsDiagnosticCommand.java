package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

public final class EventsDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "events"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public String description() { return "VM internal event log (safepoints, deopt, GC)"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        try {
            long pid = ((CommandContext.InProcess) ctx).pid();
            ProcessBuilder pb = new ProcessBuilder("jcmd", String.valueOf(pid), "VM.events");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return output;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
