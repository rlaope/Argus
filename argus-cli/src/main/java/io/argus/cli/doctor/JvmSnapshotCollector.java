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

    private static final ThreadLocal<List<String>> warnings = ThreadLocal.withInitial(ArrayList::new);

    /**
     * Collect snapshot — routes to local or remote based on PID.
     */
    public static JvmSnapshot collect(long pid) {
        warnings.get().clear();
        if (pid <= 0 || pid == ProcessHandle.current().pid()) {
            return collectLocal();
        }
        return collectRemote(pid);
    }

    /** Get warnings from the last collection (e.g., jcmd failures). */
    public static List<String> lastWarnings() {
        return List.copyOf(warnings.get());
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
        long maxRecentPauseMs = 0;
        String rawAlgorithm = "";
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcInfos.add(new JvmSnapshot.GcInfo(gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
            totalGcCount += gc.getCollectionCount();
            totalGcTime += gc.getCollectionTime();
            if (rawAlgorithm.isEmpty()) rawAlgorithm = gc.getName();
            // Use com.sun.management extension to get duration of the last individual GC pause.
            if (gc instanceof com.sun.management.GarbageCollectorMXBean sunGc) {
                com.sun.management.GcInfo lastGcInfo = sunGc.getLastGcInfo();
                if (lastGcInfo != null) {
                    maxRecentPauseMs = Math.max(maxRecentPauseMs, lastGcInfo.getDuration());
                }
            }
        }
        // Doctor rules expect canonical names (G1 / ZGC / Parallel / Serial / Shenandoah).
        // MXBean names are descriptive ("G1 Young Generation"), so normalise here so local
        // and remote paths agree.
        String gcAlgorithm = canonicalGcAlgorithm(rawAlgorithm, rt.getInputArguments());

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
                List.copyOf(rt.getInputArguments()),
                maxRecentPauseMs
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
            // Total heap is the sum of pool-used, but capacity is summed too (G1 region max
            // is per-region, not heap-wide — Math.max would have given < 1% of true capacity).
            for (var p : pools.values()) {
                heapUsed += p.used();
                if (p.max() > 0) heapMax += p.max();
            }
            // GC.heap_info also reports a top-line "garbage-first heap   total NK, used NK"; if
            // present, prefer it (more accurate for ZGC where individual pools may not be exposed).
            HeapTotals tt = parseHeapTotals(heapInfo);
            if (tt != null) {
                heapUsed = tt.usedBytes;
                heapMax = tt.totalBytes;
                heapCommitted = tt.totalBytes;
            }
        } catch (RuntimeException e) {
            warnings.get().add("GC.heap_info failed: " + e.getMessage());
            failures++;
        }

        // GC.heap_info on G1/ZGC reports only a top-line total — no separate Eden/Old breakdown,
        // which is what HeapPressureRule needs to detect Old-gen saturation. Synthesize generation
        // pools from `jstat -gcutil` (E/O/M percentages → PoolInfo entries scaled to heapMax).
        if (heapMax > 0) {
            try {
                String gcutil = runJstatGcutil(pid);
                supplementGenerationPools(gcutil, pools, heapMax);
            } catch (RuntimeException e) {
                // Non-fatal: rule simply won't fire on Old gen alone.
                warnings.get().add("jstat -gcutil failed: " + e.getMessage());
            }
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
            warnings.get().add("VM.info failed (GC stats unavailable): " + e.getMessage());
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
            warnings.get().add("VM.version failed: " + e.getMessage());
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
            warnings.get().add("VM.uptime failed: " + e.getMessage());
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
            warnings.get().add("VM.flags failed: " + e.getMessage());
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
                // jstack/Thread.print emits one banner line per deadlock chain:
                // "Found one Java-level deadlock:" — only the *header* line is signal.
                // Per-thread "...waiting to lock..." entries below the header are NOT
                // independent deadlocks. The chains within each banner all use the same
                // sentence start, so we anchor on it.
                if (t.startsWith("Found ") && t.contains("Java-level deadlock")) {
                    deadlockCount++;
                }
            }
        } catch (RuntimeException e) {
            warnings.get().add("Thread.print failed: " + e.getMessage());
            failures++;
        }

        // Print warnings to stderr
        if (failures > 0) {
            System.err.println("[Argus] WARNING: " + failures + " jcmd call(s) failed for PID " + pid + ":");
            for (String w : warnings.get()) {
                System.err.println("  → " + w);
            }
            if (failures >= 4) {
                System.err.println("[Argus] Most data unavailable. Are you running as the same user as PID " + pid + "?");
                System.err.println("[Argus] Try: sudo argus doctor " + pid);
            }
        }

        // Remote path: individual pause data is not available via jcmd/jstat.
        // Best heuristic: take the largest per-collector average (totalTime/count).
        // This is explicitly noted in finding detail when this path is taken.
        long maxRecentPauseMs = 0;
        for (JvmSnapshot.GcInfo info : gcInfos) {
            if (info.count() > 0) {
                long avgPause = info.timeMs() / info.count();
                maxRecentPauseMs = Math.max(maxRecentPauseMs, avgPause);
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
                List.copyOf(vmFlags),
                maxRecentPauseMs
        );
    }

    /**
     * Map raw collector / flag info to canonical algorithm name expected by Doctor rules.
     */
    private static String canonicalGcAlgorithm(String raw, List<String> vmArgs) {
        // Prefer explicit user flags
        for (String a : vmArgs) {
            if (a.contains("UseZGC")) return "ZGC";
            if (a.contains("UseG1GC")) return "G1";
            if (a.contains("UseShenandoahGC")) return "Shenandoah";
            if (a.contains("UseParallelGC") || a.contains("UseParallelOldGC")) return "Parallel";
            if (a.contains("UseSerialGC")) return "Serial";
        }
        if (raw == null) return "unknown";
        String l = raw.toLowerCase();
        if (l.contains("zgc") || l.contains("z gc")) return "ZGC";
        if (l.contains("g1")) return "G1";
        if (l.contains("shenandoah")) return "Shenandoah";
        if (l.contains("ps ") || l.contains("parallel")) return "Parallel";
        if (l.contains("copy") || l.contains("marksweep") || l.contains("serial")) return "Serial";
        return raw;
    }

    /**
     * Run {@code jstat -gcutil <pid>} and return its output, or null on failure.
     * Wrapped here so the collector doesn't depend on the provider hierarchy.
     */
    private static String runJstatGcutil(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("jstat", "-gcutil", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("jstat timeout");
            }
            return new String(out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * Parse {@code jstat -gcutil} columns (E, O, M) and synthesize Eden/Old/Metaspace
     * {@link JvmSnapshot.PoolInfo} entries scaled to {@code heapMax}. Existing entries
     * (e.g., from GC.heap_info) are kept; we only add the generation-level breakdown
     * that the totals view doesn't expose.
     */
    private static void supplementGenerationPools(String gcutil,
                                                   Map<String, JvmSnapshot.PoolInfo> pools,
                                                   long heapMax) {
        if (gcutil == null) return;
        String[] lines = gcutil.split("\n");
        if (lines.length < 2) return;
        String[] headers = lines[0].trim().split("\\s+");
        String[] values = lines[lines.length - 1].trim().split("\\s+");
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) idx.put(headers[i], i);

        // Eden + Old together approximate heap usage; budget Old to (heapMax * fractional split)
        // is brittle without knowing region sizes, so we just scale per-pool % directly.
        addPoolFromPct("Eden", idx.get("E"), values, pools, heapMax / 4);
        addPoolFromPct("G1 Old Gen", idx.get("O"), values, pools, heapMax * 3 / 4);
    }

    private static void addPoolFromPct(String poolName, Integer idx, String[] values,
                                       Map<String, JvmSnapshot.PoolInfo> pools, long maxBytes) {
        if (idx == null || idx >= values.length) return;
        try {
            double pct = Double.parseDouble(values[idx]);
            long used = (long) (maxBytes * pct / 100.0);
            pools.put(poolName, new JvmSnapshot.PoolInfo(poolName, used, maxBytes, "HEAP"));
        } catch (NumberFormatException ignored) {
        }
    }

    /** Heap-wide totals from the first summary line of GC.heap_info, if present. */
    private record HeapTotals(long totalBytes, long usedBytes) {}

    private static HeapTotals parseHeapTotals(String output) {
        if (output == null) return null;
        // e.g.: "garbage-first heap   total 524288K, used 65536K [0x..., 0x..., 0x...)"
        Pattern total = Pattern.compile("total\\s+(\\d+)K.*used\\s+(\\d+)K");
        for (String line : output.split("\n")) {
            Matcher m = total.matcher(line.trim());
            if (m.find()) {
                return new HeapTotals(Long.parseLong(m.group(1)) * 1024L,
                        Long.parseLong(m.group(2)) * 1024L);
            }
        }
        return null;
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
