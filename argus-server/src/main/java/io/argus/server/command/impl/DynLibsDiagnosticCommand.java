package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

public final class DynLibsDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "dynlibs"; }
    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }
    @Override public String description() { return "Dynamically loaded native libraries via jcmd VM.dynlibs"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        return DiagnosticUtil.executeJcmd("VM.dynlibs", "");
    }
}
