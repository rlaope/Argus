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

    /**
     * Collect snapshot — routes to local or remote based on PID.
     */
    public static JvmSnapshot collect(long pid) {
        if (pid <= 0 || pid == ProcessHandle.current().pid()) {
            return collectLocal();
        }
        return collectRemote(pid);
    }

    /**
     * Collect snapshot for the current JVM (in-process mode).
     */
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
     * Parses GC.heap_info, Thread.print, VM.version, VM.uptime, VM.flags.
     */
    public static JvmSnapshot collectRemote(long pid) {
        // Heap via GC.heap_info
        long heapUsed = 0, heapMax = 0, heapCommitted = 0, nonHeapUsed = 0;
        Map<String, JvmSnapshot.PoolInfo> pools = new LinkedHashMap<>();
        try {
            String heapInfo = JcmdExecutor.execute(pid, "GC.heap_info");
            parseHeapInfo(heapInfo, pools);
            for (var p : pools.values()) {
                if (p.type().equalsIgnoreCase("HEAP") || p.name().toLowerCase().contains("heap")) {
                    heapUsed += p.used();
                    if (p.max() > 0) heapMax = Math.max(heapMax, p.max());
                }
            }
            // Fallback: sum all pool used values
            if (heapUsed == 0) {
                for (var p : pools.values()) heapUsed += p.used();
            }
        } catch (RuntimeException ignored) {}

        // GC via jcmd (parse from VM.flags to detect GC, count from GC.heap_info not available — use defaults)
        List<JvmSnapshot.GcInfo> gcInfos = new ArrayList<>();
        long totalGcCount = 0, totalGcTime = 0;
        String gcAlgorithm = "unknown";

        // VM version
        String vmName = "", vmVersion = "";
        try {
            String versionOut = JcmdExecutor.execute(pid, "VM.version");
            for (String line : versionOut.split("\n")) {
                String t = line.trim();
                if (vmName.isEmpty() && !t.isEmpty()) vmName = t;
                else if (vmVersion.isEmpty() && t.contains("build")) vmVersion = t;
            }
        } catch (RuntimeException ignored) {}

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
        } catch (RuntimeException ignored) {}

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
        } catch (RuntimeException ignored) {}

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
                if (t.contains("Found one Java-level deadlock") || t.contains("Found") && t.contains("deadlock")) {
                    deadlockCount++;
                }
            }
        } catch (RuntimeException ignored) {}

        // Class loading via jcmd VM.classloader_stats
        int loadedClasses = 0;
        long totalLoaded = 0, unloaded = 0;
        try {
            String classOut = JcmdExecutor.execute(pid, "VM.classloaders");
            // Count lines with class counts
            for (String line : classOut.split("\n")) {
                if (line.trim().matches(".*\\d+.*classes.*")) loadedClasses++;
            }
        } catch (RuntimeException ignored) {}

        return new JvmSnapshot(
                heapUsed, heapMax, heapCommitted, nonHeapUsed,
                Map.copyOf(pools),
                List.copyOf(gcInfos), totalGcCount, totalGcTime, uptimeMs,
                -1, -1, Runtime.getRuntime().availableProcessors(),
                threadCount, daemonCount, threadCount, // peak = current for remote
                Map.copyOf(threadStates), deadlockCount,
                List.of(), // buffers not available via jcmd
                loadedClasses, totalLoaded, unloaded,
                0, // finalizer not available via jcmd
                vmName, vmVersion, gcAlgorithm,
                List.copyOf(vmFlags)
        );
    }

    /**
     * Parse GC.heap_info output into pool map.
     * Format varies by GC but generally:
     *  garbage-first heap   total 262144K, used 45678K [...]
     *   region size 1024K, 10 young, 2 survivors
     *  Metaspace       used 34567K, ...
     */
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
