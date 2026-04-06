package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

public final class NmtDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "nmt"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "Native memory tracking summary via jcmd VM.native_memory"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        return DiagnosticUtil.executeJcmd("VM.native_memory", "summary");
    }
}
