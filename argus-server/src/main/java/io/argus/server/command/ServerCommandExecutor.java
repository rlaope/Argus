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

    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of(
            "PASSWORD", "SECRET", "KEY", "TOKEN", "CREDENTIAL", "AUTH", "PRIVATE");

    static {
        // System
        COMMANDS.put("info", new CommandInfo("JVM Information", "system", "JVM version, vendor, uptime, PID, and runtime details"));
        COMMANDS.put("sysprops", new CommandInfo("System Properties", "system", "All JVM system properties (secrets masked)"));
        COMMANDS.put("env", new CommandInfo("Environment", "system", "JVM environment variables (secrets masked)"));
        COMMANDS.put("vmflag", new CommandInfo("VM Flags", "system", "Active HotSpot VM input arguments"));
        // Memory & GC
        COMMANDS.put("heap", new CommandInfo("Heap Memory", "memory", "Heap and non-heap memory usage breakdown"));
        COMMANDS.put("gc", new CommandInfo("GC Statistics", "memory", "Garbage collector names, counts, and pause times"));
        COMMANDS.put("gcutil", new CommandInfo("GC Utilization", "memory", "Memory pool utilization by generation"));
        COMMANDS.put("gccause", new CommandInfo("GC Cause", "memory", "GC cause distribution analysis"));
        COMMANDS.put("gcnew", new CommandInfo("GC New Gen", "memory", "Young generation memory pool details"));
        COMMANDS.put("metaspace", new CommandInfo("Metaspace", "memory", "Metaspace and compressed class space usage"));
        COMMANDS.put("pool", new CommandInfo("Memory Pools", "memory", "All memory pool usage details"));
        COMMANDS.put("histo", new CommandInfo("Heap Histogram", "memory", "Top heap-consuming classes (triggers GC, via jcmd)"));
        COMMANDS.put("nmt", new CommandInfo("Native Memory", "memory", "Native memory tracking summary (via jcmd)"));
        COMMANDS.put("finalizer", new CommandInfo("Finalizer", "memory", "Pending finalization object count"));
        // Threads
        COMMANDS.put("threads", new CommandInfo("Thread Summary", "threads", "Thread counts by state and daemon status"));
        COMMANDS.put("deadlock", new CommandInfo("Deadlock", "threads", "Detect Java-level deadlocked threads"));
        // Class & Runtime
        COMMANDS.put("classloader", new CommandInfo("Class Loading", "runtime", "Class loading statistics and counts"));
        COMMANDS.put("classstat", new CommandInfo("Class Stats", "runtime", "Loaded class count by classloader"));
        COMMANDS.put("compiler", new CommandInfo("JIT Compiler", "runtime", "JIT compilation statistics"));
        COMMANDS.put("dynlibs", new CommandInfo("Native Libraries", "runtime", "List loaded native/shared libraries"));
        COMMANDS.put("stringtable", new CommandInfo("String Table", "runtime", "Interned string table statistics"));
        COMMANDS.put("symboltable", new CommandInfo("Symbol Table", "runtime", "JVM symbol table statistics"));
        // Diagnostics
        COMMANDS.put("jfr", new CommandInfo("Flight Recorder", "diagnostic", "JFR status and recording info (via jcmd)"));
        COMMANDS.put("vmlog", new CommandInfo("VM Logging", "diagnostic", "JVM unified logging configuration (via jcmd)"));
    }

    public static Map<String, CommandInfo> getAvailableCommands() {
        return COMMANDS;
    }

    public static String execute(String command) {
        if (!COMMANDS.containsKey(command)) {
            return "Unknown command: " + command + "\nType 'help' to see available commands.";
        }
        return switch (command) {
            case "info" -> executeInfo();
            case "sysprops" -> executeSysProps();
            case "env" -> executeEnv();
            case "heap" -> executeHeap();
            case "gc" -> executeGC();
            case "gcutil" -> executeGCUtil();
            case "gccause" -> executeGCCause();
            case "gcnew" -> executeGCNew();
            case "threads" -> executeThreads();
            case "deadlock" -> executeDeadlock();
            case "vmflag" -> executeVmFlag();
            case "classloader" -> executeClassLoader();
            case "classstat" -> executeClassStat();
            case "metaspace" -> executeMetaspace();
            case "pool" -> executePool();
            case "compiler" -> executeCompiler();
            case "finalizer" -> executeFinalizer();
            case "dynlibs" -> executeDynLibs();
            case "stringtable" -> executeJcmd("VM.stringtable", "");
            case "symboltable" -> executeJcmd("VM.symboltable", "");
            case "histo" -> executeJcmd("GC.class_histogram", "-all");
            case "nmt" -> executeJcmd("VM.native_memory", "summary");
            case "jfr" -> executeJcmd("JFR.check", "");
            case "vmlog" -> executeJcmd("VM.log", "list");
            default -> "Unknown command: " + command;
        };
    }

    public static String getProcessInfo() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        String name = rt.getName();
        long pid = rt.getPid();
        String mainClass = System.getProperty("sun.java.command", "unknown");
        if (mainClass.contains(" ")) mainClass = mainClass.substring(0, mainClass.indexOf(' '));
        return pid + " " + mainClass;
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
                sb.append(String.format("  %-40s = %s\n", key, maskIfSensitive(key, props.getProperty(key))))
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
                .forEach(e -> sb.append(String.format("  %-32s = %s\n", e.getKey(), maskIfSensitive(e.getKey(), e.getValue()))));
        return sb.toString();
    }

    private static String maskIfSensitive(String key, String value) {
        String upper = key.toUpperCase();
        for (String sensitive : SENSITIVE_KEYS) {
            if (upper.contains(sensitive)) {
                return value != null && value.length() > 4
                        ? value.substring(0, 2) + "****" + value.substring(value.length() - 2)
                        : "****";
            }
        }
        return value;
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

    private static String executeGCCause() {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  GC Cause Analysis\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        for (GarbageCollectorMXBean gc : gcs) {
            sb.append(String.format("  %-30s Collections: %,d  Time: %,d ms\n",
                    gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
        }
        sb.append("\n  Note: Detailed cause distribution available on the dashboard GC Cause section.\n");
        return sb.toString();
    }

    private static String executeGCNew() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Young Generation Memory Pools\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-30s %10s %10s %10s\n", "Pool", "Used", "Committed", "Max"));
        sb.append("  " + "─".repeat(60) + "\n");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = pool.getName().toLowerCase();
            if (name.contains("eden") || name.contains("survivor") || name.contains("young") || name.contains("nursery")) {
                MemoryUsage u = pool.getUsage();
                if (u != null) {
                    sb.append(String.format("  %-30s %10s %10s %10s\n",
                            pool.getName(), formatBytes(u.getUsed()), formatBytes(u.getCommitted()), formatBytes(u.getMax())));
                }
            }
        }
        return sb.toString();
    }

    private static String executeClassStat() {
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Class Statistics\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append(String.format("  %-24s %,d\n", "Currently Loaded:", cl.getLoadedClassCount()));
        sb.append(String.format("  %-24s %,d\n", "Total Loaded:", cl.getTotalLoadedClassCount()));
        sb.append(String.format("  %-24s %,d\n", "Total Unloaded:", cl.getUnloadedClassCount()));
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().toLowerCase().contains("metaspace")) {
                MemoryUsage u = pool.getUsage();
                if (u != null) {
                    sb.append(String.format("\n  %-24s %s\n", "Metaspace Used:", formatBytes(u.getUsed())));
                    sb.append(String.format("  %-24s %s\n", "Metaspace Committed:", formatBytes(u.getCommitted())));
                }
            }
        }
        return sb.toString();
    }

    private static String executeDynLibs() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  Loaded Native Libraries\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        try {
            return sb.toString() + executeJcmd("VM.dynlibs", "");
        } catch (Exception e) {
            sb.append("  (Requires jcmd access)\n");
            return sb.toString();
        }
    }

    private static String executeJcmd(String command, String arg) {
        try {
            long pid = ProcessHandle.current().pid();
            String jcmd = System.getProperty("java.home") + "/bin/jcmd";
            var cmdList = new java.util.ArrayList<>(java.util.List.of(jcmd, String.valueOf(pid), command));
            if (arg != null && !arg.isBlank()) cmdList.add(arg);
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "Command timed out after 10 seconds: jcmd " + command;
            }
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

    public record CommandInfo(String name, String group, String description) {}
}
