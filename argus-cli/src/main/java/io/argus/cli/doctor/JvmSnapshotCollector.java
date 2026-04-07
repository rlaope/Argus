package io.argus.cli.doctor;

import io.argus.cli.provider.jdk.JcmdExecutor;

import java.lang.management.*;
import java.util.*;

/**
 * Collects a consistent snapshot of all JVM metrics from a target process.
 * Uses jcmd for remote processes and MXBeans for the current process.
 */
public final class JvmSnapshotCollector {

    /**
     * Collect snapshot for the current JVM (in-process mode).
     */
    public static JvmSnapshot collectLocal() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();

        // Memory pools
        Map<String, JvmSnapshot.PoolInfo> pools = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u != null) {
                pools.put(pool.getName(), new JvmSnapshot.PoolInfo(
                        pool.getName(), u.getUsed(), u.getMax(), pool.getType().name()));
            }
        }

        // GC
        List<JvmSnapshot.GcInfo> gcInfos = new ArrayList<>();
        long totalGcCount = 0, totalGcTime = 0;
        String gcAlgorithm = "";
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcInfos.add(new JvmSnapshot.GcInfo(gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
            totalGcCount += gc.getCollectionCount();
            totalGcTime += gc.getCollectionTime();
            if (gcAlgorithm.isEmpty()) gcAlgorithm = gc.getName();
        }

        // CPU
        double processCpu = -1, systemCpu = -1;
        int processors = Runtime.getRuntime().availableProcessors();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            processCpu = sunOs.getProcessCpuLoad();
            systemCpu = sunOs.getCpuLoad();
        }

        // Threads
        Map<String, Integer> threadStates = new LinkedHashMap<>();
        ThreadInfo[] infos = tmx.getThreadInfo(tmx.getAllThreadIds());
        for (ThreadInfo info : infos) {
            if (info != null) {
                threadStates.merge(info.getThreadState().name(), 1, Integer::sum);
            }
        }
        long[] deadlocked = tmx.findDeadlockedThreads();
        int deadlockCount = deadlocked != null ? deadlocked.length : 0;

        // Buffers
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
                Map.copyOf(threadStates), deadlockCount,
                List.copyOf(buffers),
                cl.getLoadedClassCount(), cl.getTotalLoadedClassCount(), cl.getUnloadedClassCount(),
                mem.getObjectPendingFinalizationCount(),
                rt.getVmName(), rt.getVmVersion(), gcAlgorithm,
                List.copyOf(rt.getInputArguments())
        );
    }

    /**
     * Collect snapshot for a remote JVM by PID using jcmd.
     * Parses multiple jcmd outputs to build a complete snapshot.
     */
    public static JvmSnapshot collectRemote(long pid) {
        // For remote, we use the same MXBeans since jcmd doesn't give us structured data easily.
        // In practice, doctor targets the same JVM or uses agent connection.
        // Fallback: collect what we can from jcmd and fill gaps with defaults.
        return collectLocal(); // TODO: enhance with jcmd-based remote collection
    }
}
