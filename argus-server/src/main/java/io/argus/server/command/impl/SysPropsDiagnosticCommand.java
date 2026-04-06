package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.util.TreeMap;

public final class SysPropsDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "sysprops"; }
    @Override public CommandGroup group() { return CommandGroup.PROCESS; }
    @Override public String description() { return "System properties (sorted, sensitive values masked)"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("System Properties"));

        TreeMap<String, String> sorted = new TreeMap<>();
        System.getProperties().forEach((k, v) -> sorted.put(String.valueOf(k), String.valueOf(v)));

        for (var entry : sorted.entrySet()) {
            String key = entry.getKey();
            String value = DiagnosticUtil.maskIfSensitive(key, entry.getValue());
            sb.append(String.format("%-50s = %s%n", key, value));
        }
        return sb.toString();
    }
}
