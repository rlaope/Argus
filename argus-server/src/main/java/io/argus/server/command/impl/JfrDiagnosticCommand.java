package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

public final class JfrDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "jfr"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public String description() { return "Java Flight Recorder status via jcmd JFR.check"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        return DiagnosticUtil.executeJcmd("JFR.check", "");
    }
}
