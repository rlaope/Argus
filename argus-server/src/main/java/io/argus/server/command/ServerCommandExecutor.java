package io.argus.server.command;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Executes diagnostic commands from within the server JVM using MXBeans.
 * Returns formatted text output similar to CLI commands.
 */
public final class ServerCommandExecutor {

    private static final Map<String, CommandInfo> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("info", new CommandInfo("JVM Information", "Show JVM version, vendor, uptime, and runtime details"));
        COMMANDS.put("sysprops", new CommandInfo("System Properties", "Display all JVM system properties"));
        COMMANDS.put("env", new CommandInfo("Environment Variables", "Show JVM launch environment variables"));
        COMMANDS.put("heap", new CommandInfo("Heap Memory", "Current heap memory usage breakdown"));
        COMMANDS.put("gc", new CommandInfo("GC Statistics", "Garbage collector names, counts, and total pause times"));
        COMMANDS.put("gcutil", new CommandInfo("GC Utilization", "Memory pool utilization by generation"));
        COMMANDS.put("threads", new CommandInfo("Thread Summary", "Thread counts by state and daemon status"));
        COMMANDS.put("deadlock", new CommandInfo("Deadlock Detection", "Detect Java-level deadlocked threads"));
        COMMANDS.put("vmflag", new CommandInfo("VM Flags", "Show active HotSpot VM flags"));
        COMMANDS.put("classloader", new CommandInfo("Class Loading", "Class loading statistics and counts"));
        COMMANDS.put("metaspace", new CommandInfo("Metaspace", "Metaspace and compressed class space usage"));
        COMMANDS.put("pool", new CommandInfo("Memory Pools", "Detailed memory pool usage by region"));
        COMMANDS.put("compiler", new CommandInfo("JIT Compiler", "JIT compilation statistics"));
        COMMANDS.put("finalizer", new CommandInfo("Finalizer Queue", "Pending finalization object count"));
        COMMANDS.put("histo", new CommandInfo("Heap Histogram", "Top heap-consuming classes (via jcmd)"));
        COMMANDS.put("nmt", new CommandInfo("Native Memory", "Native memory tracking summary (via jcmd)"));
    }

    public static Map<String, CommandInfo> getAvailableCommands() {
        return COMMANDS;
    }

    public static String execute(String command) {
        return switch (command) {
            case "info" -> executeInfo();
            case "sysprops" -> executeSysProps();
            case "env" -> executeEnv();
            case "heap" -> executeHeap();
            case "gc" -> executeGC();
            case "gcutil" -> executeGCUtil();
            case "threads" -> executeThreads();
            case "deadlock" -> executeDeadlock();
            case "vmflag" -> executeVmFlag();
            case "classloader" -> executeClassLoader();
            case "metaspace" -> executeMetaspace();
            case "pool" -> executePool();
            case "compiler" -> executeCompiler();
            case "finalizer" -> executeFinalizer();
            case "histo" -> executeJcmd("GC.class_histogram", "-all");
            case "nmt" -> executeJcmd("VM.native_memory", "summary");
            default -> "Unknown command: " + command + "\nType 'help' to see available commands.";
        };
    }

    private static String executeInfo() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long uptime = rt.getUptime();
        Duration d = Duration.ofMillis(uptime);

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  JVM Information\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-24s %s\n", "Java Version:", rt.getSpecVersion()));
        sb.append(String.format("  %-24s %s\n", "VM Name:", rt.getVmName()));
        sb.append(String.format("  %-24s %s\n", "VM Version:", rt.getVmVersion()));
        sb.append(String.format("  %-24s %s\n", "VM Vendor:", rt.getVmVendor()));
        sb.append(String.format("  %-24s %s\n", "PID:", rt.getPid()));
        sb.append(String.format("  %-24s %dh %dm %ds\n", "Uptime:", d.toHours(), d.toMinutesPart(), d.toSecondsPart()));
        sb.append(String.format("  %-24s %s\n", "Start Time:", Instant.ofEpochMilli(rt.getStartTime())));
        sb.append(String.format("  %-24s %s / %s\n", "OS:", os.getName(), os.getArch()));
        sb.append(String.format("  %-24s %d\n", "Available Processors:", os.getAvailableProcessors()));
        sb.append(String.format("  %-24s %s\n", "Heap Max:", formatBytes(mem.getHeapMemoryUsage().getMax())));
        return sb.toString();
    }

    private static String executeSysProps() {
        Properties props = System.getProperties();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  System Properties\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        props.stringPropertyNames().stream().sorted().forEach(key ->
                sb.append(String.format("  %-40s = %s\n", key, props.getProperty(key)))
        );
        return sb.toString();
    }

    private static String executeEnv() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Environment Variables\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        System.getenv().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(String.format("  %-32s = %s\n", e.getKey(), e.getValue())));
        return sb.toString();
    }

    private static String executeHeap() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Heap Memory Usage\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-20s %12s %12s %12s %12s\n", "Region", "Init", "Used", "Committed", "Max"));
        sb.append("  " + "─".repeat(68) + "\n");
        sb.append(String.format("  %-20s %12s %12s %12s %12s\n", "Heap",
                formatBytes(heap.getInit()), formatBytes(heap.getUsed()),
                formatBytes(heap.getCommitted()), formatBytes(heap.getMax())));
        sb.append(String.format("  %-20s %12s %12s %12s %12s\n", "Non-Heap",
                formatBytes(nonHeap.getInit()), formatBytes(nonHeap.getUsed()),
                formatBytes(nonHeap.getCommitted()), formatBytes(nonHeap.getMax())));
        sb.append("\n");
        double usedPercent = (double) heap.getUsed() / heap.getMax() * 100;
        sb.append(String.format("  Heap Utilization: %.1f%%\n", usedPercent));
        return sb.toString();
    }

    private static String executeGC() {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Garbage Collector Statistics\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-30s %12s %12s\n", "Collector", "Collections", "Time (ms)"));
        sb.append("  " + "─".repeat(54) + "\n");
        long totalCollections = 0, totalTime = 0;
        for (GarbageCollectorMXBean gc : gcs) {
            sb.append(String.format("  %-30s %,12d %,12d\n", gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
            totalCollections += gc.getCollectionCount();
            totalTime += gc.getCollectionTime();
        }
        sb.append("  " + "─".repeat(54) + "\n");
        sb.append(String.format("  %-30s %,12d %,12d\n", "TOTAL", totalCollections, totalTime));
        return sb.toString();
    }

    private static String executeGCUtil() {
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  GC Utilization by Memory Pool\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-30s %10s %10s %8s\n", "Pool", "Used", "Max", "Util%"));
        sb.append("  " + "─".repeat(58) + "\n");
        for (MemoryPoolMXBean pool : pools) {
            MemoryUsage usage = pool.getUsage();
            if (usage == null) continue;
            double util = usage.getMax() > 0 ? (double) usage.getUsed() / usage.getMax() * 100 : 0;
            sb.append(String.format("  %-30s %10s %10s %7.1f%%\n",
                    pool.getName(), formatBytes(usage.getUsed()), formatBytes(usage.getMax()), util));
        }
        return sb.toString();
    }

    private static String executeThreads() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        int total = tmx.getThreadCount();
        int daemon = tmx.getDaemonThreadCount();
        int peak = tmx.getPeakThreadCount();
        long started = tmx.getTotalStartedThreadCount();

        Map<Thread.State, Integer> stateCounts = new LinkedHashMap<>();
        for (Thread.State s : Thread.State.values()) stateCounts.put(s, 0);
        ThreadInfo[] infos = tmx.getThreadInfo(tmx.getAllThreadIds());
        for (ThreadInfo info : infos) {
            if (info != null) stateCounts.merge(info.getThreadState(), 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Thread Summary\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-24s %d\n", "Current Threads:", total));
        sb.append(String.format("  %-24s %d\n", "Daemon Threads:", daemon));
        sb.append(String.format("  %-24s %d\n", "Peak Threads:", peak));
        sb.append(String.format("  %-24s %,d\n", "Total Started:", started));
        sb.append("\n  By State:\n");
        stateCounts.forEach((state, count) -> {
            if (count > 0) sb.append(String.format("    %-20s %d\n", state, count));
        });
        return sb.toString();
    }

    private static String executeDeadlock() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = tmx.findDeadlockedThreads();

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Deadlock Detection\n");
        sb.append("═══════════════════════════════════════════════════\n\n");

        if (deadlocked == null || deadlocked.length == 0) {
            sb.append("  ✓ No deadlocks detected.\n");
            return sb.toString();
        }

        sb.append(String.format("  ⚠ %d deadlocked thread(s) found!\n\n", deadlocked.length));
        ThreadInfo[] infos = tmx.getThreadInfo(deadlocked, true, true);
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            sb.append(String.format("  Thread: \"%s\" (id=%d)\n", info.getThreadName(), info.getThreadId()));
            sb.append(String.format("    State: %s\n", info.getThreadState()));
            sb.append(String.format("    Waiting for: %s\n", info.getLockInfo()));
            sb.append(String.format("    Held by: \"%s\"\n", info.getLockOwnerName()));
            sb.append("    Stack:\n");
            for (StackTraceElement ste : info.getStackTrace()) {
                sb.append("      at ").append(ste).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String executeVmFlag() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  VM Input Arguments\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        for (String arg : rt.getInputArguments()) {
            sb.append("  ").append(arg).append("\n");
        }
        return sb.toString();
    }

    private static String executeClassLoader() {
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Class Loading Statistics\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-24s %,d\n", "Loaded Classes:", cl.getLoadedClassCount()));
        sb.append(String.format("  %-24s %,d\n", "Total Loaded:", cl.getTotalLoadedClassCount()));
        sb.append(String.format("  %-24s %,d\n", "Unloaded:", cl.getUnloadedClassCount()));
        return sb.toString();
    }

    private static String executeMetaspace() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Metaspace Usage\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-30s %10s %10s %10s\n", "Pool", "Used", "Committed", "Max"));
        sb.append("  " + "─".repeat(60) + "\n");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().toLowerCase().contains("metaspace") || pool.getName().toLowerCase().contains("compressed")) {
                MemoryUsage u = pool.getUsage();
                if (u != null) {
                    sb.append(String.format("  %-30s %10s %10s %10s\n",
                            pool.getName(), formatBytes(u.getUsed()), formatBytes(u.getCommitted()), formatBytes(u.getMax())));
                }
            }
        }
        return sb.toString();
    }

    private static String executePool() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Memory Pool Details\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-30s %10s %10s %10s %8s\n", "Pool", "Used", "Committed", "Max", "Type"));
        sb.append("  " + "─".repeat(68) + "\n");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u == null) continue;
            sb.append(String.format("  %-30s %10s %10s %10s %8s\n",
                    pool.getName(), formatBytes(u.getUsed()), formatBytes(u.getCommitted()),
                    formatBytes(u.getMax()), pool.getType()));
        }
        return sb.toString();
    }

    private static String executeCompiler() {
        var compiler = ManagementFactory.getCompilationMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  JIT Compiler Statistics\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        if (compiler != null) {
            sb.append(String.format("  %-24s %s\n", "Compiler Name:", compiler.getName()));
            if (compiler.isCompilationTimeMonitoringSupported()) {
                sb.append(String.format("  %-24s %,d ms\n", "Total Compile Time:", compiler.getTotalCompilationTime()));
            }
        }
        return sb.toString();
    }

    private static String executeFinalizer() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Finalizer Queue\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-24s %d\n", "Pending Finalization:", mem.getObjectPendingFinalizationCount()));
        return sb.toString();
    }

    private static String executeJcmd(String command, String arg) {
        try {
            long pid = ProcessHandle.current().pid();
            String jcmd = System.getProperty("java.home") + "/bin/jcmd";
            ProcessBuilder pb = new ProcessBuilder(jcmd, String.valueOf(pid), command, arg);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return output;
        } catch (Exception e) {
            return "Failed to execute jcmd " + command + ": " + e.getMessage();
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public record CommandInfo(String name, String description) {}
}
