package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class InfoDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "info"; }
    @Override public CommandGroup group() { return CommandGroup.PROCESS; }
    @Override public String description() { return "JVM process info: runtime, OS, memory summary"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(ZoneId.systemDefault());
        String startTime = fmt.format(Instant.ofEpochMilli(rt.getStartTime()));
        long uptimeSec = rt.getUptime() / 1000;
        String uptime = String.format("%dd %02dh %02dm %02ds",
                uptimeSec / 86400, (uptimeSec % 86400) / 3600,
                (uptimeSec % 3600) / 60, uptimeSec % 60);

        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("JVM Process Info"));
        sb.append(String.format("%-28s %s%n", "Spec Version:", rt.getSpecVersion()));
        sb.append(String.format("%-28s %s%n", "VM Name:", rt.getVmName()));
        sb.append(String.format("%-28s %s%n", "VM Version:", rt.getVmVersion()));
        sb.append(String.format("%-28s %s%n", "VM Vendor:", rt.getVmVendor()));
        sb.append(String.format("%-28s %d%n", "PID:", ProcessHandle.current().pid()));
        sb.append(String.format("%-28s %s%n", "Uptime:", uptime));
        sb.append(String.format("%-28s %s%n", "Start Time:", startTime));
        sb.append('\n');
        sb.append(String.format("%-28s %s%n", "OS Name:", os.getName()));
        sb.append(String.format("%-28s %s%n", "OS Arch:", os.getArch()));
        sb.append(String.format("%-28s %d%n", "Available Processors:", os.getAvailableProcessors()));
        sb.append('\n');
        sb.append(String.format("%-28s %s%n", "Heap Max:",
                DiagnosticUtil.formatBytes(mem.getHeapMemoryUsage().getMax())));
        return sb.toString();
    }
}
