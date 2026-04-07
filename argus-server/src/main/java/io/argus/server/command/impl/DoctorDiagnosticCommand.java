package io.argus.server.command.impl;

import io.argus.core.command.CommandContext;
import io.argus.core.command.CommandGroup;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.*;
import java.util.List;

/**
 * In-process JVM health diagnosis for the dashboard console.
 * Lightweight version of the CLI doctor — runs all checks and returns text report.
 */
public final class DoctorDiagnosticCommand implements DiagnosticCommand {

    @Override public String id() { return "doctor"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public String description() { return "One-click JVM health diagnosis"; }
    @Override public boolean supportsExternal() { return false; }

    @Override
    public String execute(CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(DiagnosticUtil.header("JVM Health Report"));

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();

        double heapPct = heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() * 100 : 0;
        long totalGcTime = 0, totalGcCount = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalGcTime += gc.getCollectionTime();
            totalGcCount += gc.getCollectionCount();
        }
        double gcOverhead = rt.getUptime() > 0 ? (double) totalGcTime / rt.getUptime() * 100 : 0;

        double cpuPct = -1;
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            cpuPct = sunOs.getProcessCpuLoad() * 100;
        }

        int blocked = 0;
        ThreadInfo[] infos = tmx.getThreadInfo(tmx.getAllThreadIds());
        for (ThreadInfo info : infos) {
            if (info != null && info.getThreadState() == Thread.State.BLOCKED) blocked++;
        }
        long[] deadlocked = tmx.findDeadlockedThreads();
        int deadlockCount = deadlocked != null ? deadlocked.length : 0;
        int pending = mem.getObjectPendingFinalizationCount();

        // Run checks
        int issues = 0;

        if (deadlockCount > 0) {
            sb.append("  🔴 CRITICAL: ").append(deadlockCount).append(" deadlocked threads\n");
            sb.append("     → Run: argus deadlock for full chain analysis\n\n");
            issues++;
        }
        if (gcOverhead >= 15) {
            sb.append("  🔴 CRITICAL: GC overhead ").append(String.format("%.1f%%", gcOverhead)).append("\n");
            sb.append("     → Consider increasing -Xmx or tuning GC\n\n");
            issues++;
        } else if (gcOverhead >= 5) {
            sb.append("  🟡 WARNING: GC overhead ").append(String.format("%.1f%%", gcOverhead)).append("\n");
            sb.append("     → Monitor GC trend\n\n");
            issues++;
        }
        if (heapPct >= 92) {
            sb.append("  🔴 CRITICAL: Heap usage ").append(String.format("%.0f%%", heapPct)).append("\n");
            sb.append("     → OOM risk — increase -Xmx or investigate leaks\n\n");
            issues++;
        } else if (heapPct >= 75) {
            sb.append("  🟡 WARNING: Heap usage ").append(String.format("%.0f%%", heapPct)).append("\n");
            sb.append("     → Monitor heap trend\n\n");
            issues++;
        }
        if (blocked >= 5) {
            sb.append("  🟡 WARNING: ").append(blocked).append(" threads BLOCKED\n");
            sb.append("     → Check for lock contention\n\n");
            issues++;
        }
        if (cpuPct >= 90) {
            sb.append("  🔴 CRITICAL: CPU usage ").append(String.format("%.0f%%", cpuPct)).append("\n");
            sb.append("     → Profile with: argus flame\n\n");
            issues++;
        } else if (cpuPct >= 70) {
            sb.append("  🟡 WARNING: CPU usage ").append(String.format("%.0f%%", cpuPct)).append("\n\n");
            issues++;
        }
        if (pending >= 100) {
            sb.append("  🟡 WARNING: ").append(pending).append(" objects pending finalization\n\n");
            issues++;
        }

        if (issues == 0) {
            sb.append("  ✅ All checks passed — JVM is healthy\n\n");
            sb.append("  ✅ Heap: ").append(String.format("%.0f%%", heapPct)).append("\n");
            if (cpuPct >= 0) sb.append("  ✅ CPU: ").append(String.format("%.0f%%", cpuPct)).append("\n");
            sb.append("  ✅ GC overhead: ").append(String.format("%.1f%%", gcOverhead)).append("\n");
            sb.append("  ✅ Threads: ").append(tmx.getThreadCount()).append(" (0 deadlocked)\n");
        } else {
            sb.append("  ").append(issues).append(" issue(s) found");
        }

        return sb.toString();
    }
}
