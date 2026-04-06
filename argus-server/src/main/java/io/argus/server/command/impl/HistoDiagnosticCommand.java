package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

public final class HistoDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "histo"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public String description() { return "Heap object histogram via jcmd GC.class_histogram"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        return DiagnosticUtil.executeJcmd("GC.class_histogram", "-all");
    }
}
