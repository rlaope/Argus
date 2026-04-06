package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

public final class VmLogDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "vmlog"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public String description() { return "JVM log configuration via jcmd VM.log list"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        return DiagnosticUtil.executeJcmd("VM.log", "list");
    }
}
