package io.argus.cli.doctor;

import io.argus.cli.provider.jdk.JcmdExecutor;

import java.lang.management.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects a consistent snapshot of all JVM metrics from a target process.
 * Uses MXBeans for the current JVM, jcmd parsing for remote JVMs.
 */
public final class JvmSnapshotCollector {

    private static final List<String> warnings = new ArrayList<>();

    /**
     * Collect snapshot — routes to local or remote based on PID.
     */
    public static JvmSnapshot collect(long pid) {
        warnings.clear();
        if (pid <= 0 || pid == ProcessHandle.current().pid()) {
            return collectLocal();
        }
        return collectRemote(pid);
    }

    /** Get warnings from the last collection (e.g., jcmd failures). */
    public static List<String> lastWarnings() {
        return List.copyOf(warnings);
    }

    @SuppressWarnings("deprecation")
    public static JvmSnapshot collectLocal() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();

        Map<String, JvmSnapshot.PoolInfo> pools = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u != null) {
                pools.put(pool.getName(), new JvmSnapshot.PoolInfo(
                        pool.getName(), u.getUsed(), u.getMax(), pool.getType().name()));
            }
        }

        List<JvmSnapshot.GcInfo> gcInfos = new ArrayList<>();
        long totalGcCount = 0, totalGcTime = 0;
        String gcAlgorithm = "";
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcInfos.add(new JvmSnapshot.GcInfo(gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
            totalGcCount += gc.getCollectionCount();
            totalGcTime += gc.getCollectionTime();
            if (gcAlgorithm.isEmpty()) gcAlgorithm = gc.getName();
        }

        double processCpu = -1, systemCpu = -1;
        int processors = Runtime.getRuntime().availableProcessors();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            processCpu = sunOs.getProcessCpuLoad();
            systemCpu = sunOs.getCpuLoad();
        }

        Map<String, Integer> threadStates = new LinkedHashMap<>();
        ThreadInfo[] infos = tmx.getThreadInfo(tmx.getAllThreadIds());
        for (ThreadInfo info : infos) {
            if (info != null) threadStates.merge(info.getThreadState().name(), 1, Integer::sum);
        }
        long[] deadlocked = tmx.findDeadlockedThreads();

        List<JvmSnapshot.BufferInfo> buffers = new ArrayList<>();
        for (BufferPoolMXBean buf : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            buffers.add(new JvmSnapshot.BufferInfo(
                    buf.getName(), buf.getCount(), buf.getTotalCapacity(), buf.getMemoryUsed()));
        }

        return new JvmSnapshot(
                heap.getUsed(), heap.getMax(), heap.getCommitted(),
                mem.getNonHeapMemoryUsage().getUsed(),
                Map.copyOf(pools),
                List.copyOf(gcInfos), totalGcCount, totalGcTime, rt.getUptime(),
                processCpu, systemCpu, processors,
                tmx.getThreadCount(), tmx.getDaemonThreadCount(), tmx.getPeakThreadCount(),
                Map.copyOf(threadStates), deadlocked != null ? deadlocked.length : 0,
                List.copyOf(buffers),
                cl.getLoadedClassCount(), cl.getTotalLoadedClassCount(), cl.getUnloadedClassCount(),
                mem.getObjectPendingFinalizationCount(),
                rt.getVmName(), rt.getVmVersion(), gcAlgorithm,
                List.copyOf(rt.getInputArguments())
        );
    }

    /**
     * Collect snapshot for a remote JVM by PID using jcmd.
     * Errors are collected in warnings list instead of silently swallowed.
     */
    public static JvmSnapshot collectRemote(long pid) {
        int failures = 0;

        // Heap via GC.heap_info
        long heapUsed = 0, heapMax = 0, heapCommitted = 0, nonHeapUsed = 0;
        Map<String, JvmSnapshot.PoolInfo> pools = new LinkedHashMap<>();
        try {
            String heapInfo = JcmdExecutor.execute(pid, "GC.heap_info");
            parseHeapInfo(heapInfo, pools);
            for (var p : pools.values()) {
                heapUsed += p.used();
                if (p.max() > 0) heapMax = Math.max(heapMax, p.max());
            }
        } catch (RuntimeException e) {
            warnings.add("GC.heap_info failed: " + e.getMessage());
            failures++;
        }

        // GC stats via jcmd — parse collector info
        List<JvmSnapshot.GcInfo> gcInfos = new ArrayList<>();
        long totalGcCount = 0, totalGcTime = 0;
        String gcAlgorithm = "unknown";
        try {
            // Use VM.info which contains GC collector stats
            String vmInfo = JcmdExecutor.execute(pid, "VM.info");
            for (String line : vmInfo.split("\n")) {
                String t = line.trim();
                // Parse: "garbage-first heap" or "PS Young Generation" etc.
                if (t.contains("invocations") && t.contains("ms")) {
                    // Try to parse GC invocation lines from VM.info
                    var matcher = Pattern.compile("(\\d+)\\s+invocations.*?(\\d+)\\s*ms").matcher(t);
                    if (matcher.find()) {
                        long count = Long.parseLong(matcher.group(1));
                        long time = Long.parseLong(matcher.group(2));
                        totalGcCount += count;
                        totalGcTime += time;
                    }
                }
            }
        } catch (RuntimeException e) {
            warnings.add("VM.info failed (GC stats unavailable): " + e.getMessage());
            failures++;
        }

        // VM version
        String vmName = "", vmVersion = "";
        try {
            String versionOut = JcmdExecutor.execute(pid, "VM.version");
            for (String line : versionOut.split("\n")) {
                String t = line.trim();
                if (vmName.isEmpty() && !t.isEmpty()) vmName = t;
                else if (vmVersion.isEmpty() && t.contains("build")) vmVersion = t;
            }
        } catch (RuntimeException e) {
            warnings.add("VM.version failed: " + e.getMessage());
            failures++;
        }

        // VM uptime
        long uptimeMs = 0;
        try {
            String uptimeOut = JcmdExecutor.execute(pid, "VM.uptime");
            for (String line : uptimeOut.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                try {
                    uptimeMs = (long) (Double.parseDouble(t.split("\\s+")[0]) * 1000);
                    break;
                } catch (NumberFormatException ignored2) {}
            }
        } catch (RuntimeException e) {
            warnings.add("VM.uptime failed: " + e.getMessage());
            failures++;
        }

        // VM flags — detect GC algorithm
        List<String> vmFlags = new ArrayList<>();
        try {
            String flagsOut = JcmdExecutor.execute(pid, "VM.flags");
            for (String line : flagsOut.split("\n")) {
                for (String token : line.trim().split("\\s+")) {
                    if (token.startsWith("-")) {
                        vmFlags.add(token);
                        if (token.contains("UseG1GC")) gcAlgorithm = "G1";
                        else if (token.contains("UseZGC")) gcAlgorithm = "ZGC";
                        else if (token.contains("UseParallelGC")) gcAlgorithm = "Parallel";
                        else if (token.contains("UseSerialGC")) gcAlgorithm = "Serial";
                        else if (token.contains("UseShenandoahGC")) gcAlgorithm = "Shenandoah";
                    }
                }
            }
        } catch (RuntimeException e) {
            warnings.add("VM.flags failed: " + e.getMessage());
            failures++;
        }

        // Threads via Thread.print
        int threadCount = 0, daemonCount = 0;
        Map<String, Integer> threadStates = new LinkedHashMap<>();
        int deadlockCount = 0;
        try {
            String threadOut = JcmdExecutor.execute(pid, "Thread.print");
            for (String line : threadOut.split("\n")) {
                String t = line.trim();
                if (t.startsWith("\"") && t.contains("#")) {
                    threadCount++;
                    if (t.contains("daemon")) daemonCount++;
                }
                if (t.startsWith("java.lang.Thread.State:")) {
                    String state = t.split(":\\s*")[1].split("\\s+")[0];
                    threadStates.merge(state, 1, Integer::sum);
                }
                if (t.contains("Found") && t.contains("deadlock")) {
                    deadlockCount++;
                }
            }
        } catch (RuntimeException e) {
            warnings.add("Thread.print failed: " + e.getMessage());
            failures++;
        }

        // Print warnings to stderr
        if (failures > 0) {
            System.err.println("[Argus] WARNING: " + failures + " jcmd call(s) failed for PID " + pid + ":");
            for (String w : warnings) {
                System.err.println("  → " + w);
            }
            if (failures >= 4) {
                System.err.println("[Argus] Most data unavailable. Are you running as the same user as PID " + pid + "?");
                System.err.println("[Argus] Try: sudo argus doctor " + pid);
            }
        }

        return new JvmSnapshot(
                heapUsed, heapMax, heapCommitted, nonHeapUsed,
                Map.copyOf(pools),
                List.copyOf(gcInfos), totalGcCount, totalGcTime, uptimeMs,
                -1, -1, Runtime.getRuntime().availableProcessors(),
                threadCount, daemonCount, threadCount,
                Map.copyOf(threadStates), deadlockCount,
                List.of(),
                0, 0, 0, 0,
                vmName, vmVersion, gcAlgorithm,
                List.copyOf(vmFlags)
        );
    }

    private static void parseHeapInfo(String output, Map<String, JvmSnapshot.PoolInfo> pools) {
        if (output == null) return;
        Pattern sizePattern = Pattern.compile("(\\w[\\w\\s-]*)\\s+(?:total\\s+)?(\\d+)K.*used\\s+(\\d+)K");
        for (String line : output.split("\n")) {
            Matcher m = sizePattern.matcher(line.trim());
            if (m.find()) {
                String name = m.group(1).trim();
                long totalKB = Long.parseLong(m.group(2));
                long usedKB = Long.parseLong(m.group(3));
                pools.put(name, new JvmSnapshot.PoolInfo(
                        name, usedKB * 1024, totalKB * 1024, "HEAP"));
            }
        }
    }
}
