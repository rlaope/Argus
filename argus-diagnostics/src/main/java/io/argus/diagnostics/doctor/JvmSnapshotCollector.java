package io.argus.diagnostics.doctor;

import io.argus.diagnostics.gclog.G1Stats;
import io.argus.diagnostics.gclog.GcLogParser;
import io.argus.diagnostics.model.CompilerResult;
import io.argus.diagnostics.model.GcUtilResult;
import io.argus.diagnostics.model.NmtResult;
import io.argus.diagnostics.jcmd.JcmdExecutor;
import io.argus.diagnostics.jcmd.JdkCompilerProvider;
import io.argus.diagnostics.jcmd.JdkGcUtilProvider;
import io.argus.diagnostics.jcmd.JdkNmtProvider;

import java.lang.management.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects a consistent snapshot of all JVM metrics from a target process.
 * Uses MXBeans for the current JVM, jcmd parsing for remote JVMs.
 */
public final class JvmSnapshotCollector {

    private static final ThreadLocal<List<String>> warnings = ThreadLocal.withInitial(ArrayList::new);

    private static final Pattern JCMD_HEAP_TOTAL_USED_KB = Pattern.compile("total\\s+(\\d+)K.*used\\s+(\\d+)K");
    private static final Pattern JCMD_HEAP_POOL_PARSE = Pattern.compile("(\\w[\\w\\s-]*)\\s+(?:total\\s+)?(\\d+)K.*used\\s+(\\d+)K");

    /** Extracts the GC log file path from -Xlog:gc*:file=<path> style flags. Captures only the file= portion. */
    private static final Pattern GC_LOG_FILE_FLAG = Pattern.compile(
            "-Xlog:gc[^:\\s]*:(?:file=)?([^:\\s,]+)", Pattern.CASE_INSENSITIVE);

    /** Maximum GC log size we'll re-parse in the snapshot path (10 MB). */
    private static final long MAX_LOG_BYTES = 10L * 1024 * 1024;

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
        // Accumulate all collector names so canonicalGcAlgorithm can detect
        // generational ZGC ("ZGC Major" / "ZGC Minor") without re-querying MXBeans.
        List<String> gcBeanNames = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcInfos.add(new JvmSnapshot.GcInfo(gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
            totalGcCount += gc.getCollectionCount();
            totalGcTime += gc.getCollectionTime();
            gcBeanNames.add(gc.getName());
            // Use com.sun.management extension to get duration of the last individual GC pause.
            if (gc instanceof com.sun.management.GarbageCollectorMXBean) {
                com.sun.management.GarbageCollectorMXBean sunGc = (com.sun.management.GarbageCollectorMXBean) gc;
                com.sun.management.GcInfo lastGcInfo = sunGc.getLastGcInfo();
                if (lastGcInfo != null) {
                    maxRecentPauseMs = Math.max(maxRecentPauseMs, lastGcInfo.getDuration());
                }
            }
        }
        // Doctor rules expect canonical names (G1 / ZGC / Parallel / Serial / Shenandoah).
        // MXBean names are descriptive ("G1 Young Generation"), so normalise here so local
        // and remote paths agree.
        String rawAlgorithm = gcBeanNames.isEmpty() ? "" : gcBeanNames.get(0);
        String gcAlgorithm = canonicalGcAlgorithm(rawAlgorithm, rt.getInputArguments(), gcBeanNames);

        double processCpu = -1, systemCpu = -1;
        int processors = Runtime.getRuntime().availableProcessors();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOs = (com.sun.management.OperatingSystemMXBean) os;
            processCpu = sunOs.getProcessCpuLoad();
            systemCpu = sunOs.getSystemCpuLoad();
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

        long pid = rt.getPid();
        CodeCacheSnapshot cc = collectCodeCache(pid);
        Map<String, Long> nmt = collectNmtCommittedKb(pid);

        List<String> vmArgs = List.copyOf(rt.getInputArguments());
        G1Stats g1Stats = extractG1Stats(vmArgs, gcAlgorithm);

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
                vmArgs,
                maxRecentPauseMs,
                cc.usedKb, cc.sizeKb, nmt,
                g1Stats
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

        // jstat -gcutil: supplies both generation-pool breakdown (E/O percentages) AND
        // GC count/time totals (YGC, YGCT, FGC, FGCT, GCT). Run it once here and reuse
        // the result for both purposes. Modern JVMs do not emit invocation/ms lines in
        // VM.info, so that approach is dead; jstat is the canonical source.
        GcUtilResult gcutil = null;
        try {
            String gcutilOut = runJstatGcutil(pid);
            gcutil = JdkGcUtilProvider.parseOutput(gcutilOut);
            if (heapMax > 0) {
                supplementGenerationPools(gcutilOut, pools, heapMax);
            }
        } catch (RuntimeException e) {
            // Non-fatal: pool breakdown and GC stats will be absent/zeroed.
            warnings.get().add("jstat -gcutil failed: " + e.getMessage());
        }

        // GC stats derived from jstat -gcutil columns.
        List<JvmSnapshot.GcInfo> gcInfos = new ArrayList<>();
        long totalGcCount = 0, totalGcTime = 0;
        String gcAlgorithm = "unknown";
        if (gcutil != null) {
            totalGcCount = gcutil.ygc() + gcutil.fgc();
            totalGcTime = Math.round(gcutil.gct() * 1000.0);  // GCT is seconds → ms
            // Synthesize two logical collector entries for rules that iterate gcInfos.
            gcInfos.add(new JvmSnapshot.GcInfo(
                    "Young Generation", gcutil.ygc(), Math.round(gcutil.ygct() * 1000.0)));
            gcInfos.add(new JvmSnapshot.GcInfo(
                    "Old Generation", gcutil.fgc(), Math.round(gcutil.fgct() * 1000.0)));
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

        CodeCacheSnapshot cc = collectCodeCache(pid);
        Map<String, Long> nmt = collectNmtCommittedKb(pid);

        List<String> remoteVmArgs = List.copyOf(vmFlags);
        G1Stats g1Stats = extractG1Stats(remoteVmArgs, gcAlgorithm);

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
                remoteVmArgs,
                maxRecentPauseMs,
                cc.usedKb, cc.sizeKb, nmt,
                g1Stats
        );
    }

    /**
     * Extracts G1-specific stats by detecting {@code -Xlog:gc*:file=<path>} in
     * the VM arguments and parsing the log file. Returns {@link G1Stats#empty()}
     * when:
     * <ul>
     *   <li>the collector is not G1,</li>
     *   <li>no GC log file flag is present,</li>
     *   <li>the log file is missing, unreadable, or larger than {@link #MAX_LOG_BYTES}.</li>
     * </ul>
     * Best-effort — failures are added to {@link #warnings} but never throw.
     */
    static G1Stats extractG1Stats(List<String> vmArgs, String gcAlgorithm) {
        if (vmArgs == null || vmArgs.isEmpty()) return G1Stats.empty();
        if (gcAlgorithm == null || !gcAlgorithm.contains("G1")) return G1Stats.empty();

        Path logPath = null;
        for (String flag : vmArgs) {
            Matcher m = GC_LOG_FILE_FLAG.matcher(flag);
            if (m.find()) {
                logPath = Path.of(m.group(1));
                break;
            }
        }
        if (logPath == null) return G1Stats.empty();

        try {
            if (!Files.isReadable(logPath)) return G1Stats.empty();
            long size = Files.size(logPath);
            if (size <= 0 || size > MAX_LOG_BYTES) return G1Stats.empty();
            GcLogParser.ParseResult result = GcLogParser.parseWithPhases(logPath);
            return result.g1Stats();
        } catch (Exception e) {
            warnings.get().add("GC log parse failed for " + logPath + ": " + e.getMessage());
            return G1Stats.empty();
        }
    }

    /** Best-effort code-cache totals; returns zeros on failure (doctor degrades gracefully). */
    private static CodeCacheSnapshot collectCodeCache(long pid) {
        try {
            CompilerResult r = new JdkCompilerProvider().getCompilerInfo(pid);
            return new CodeCacheSnapshot(r.codeCacheUsedKb(), r.codeCacheSizeKb());
        } catch (RuntimeException e) {
            warnings.get().add("Compiler.codecache failed: " + e.getMessage());
            return new CodeCacheSnapshot(0L, 0L);
        }
    }

    /**
     * Best-effort NMT committed-KB per category. Returns empty map if NMT is not enabled
     * or the call fails — rules that consume this must treat empty as "no signal".
     */
    private static Map<String, Long> collectNmtCommittedKb(long pid) {
        try {
            NmtResult r = new JdkNmtProvider().getNativeMemory(pid);
            if (r.isNmtNotEnabled() || r.categories().isEmpty()) return Map.of();
            Map<String, Long> out = new LinkedHashMap<>();
            for (NmtResult.NmtCategory c : r.categories()) {
                out.put(c.name(), c.committedKB());
            }
            return Map.copyOf(out);
        } catch (RuntimeException e) {
            warnings.get().add("VM.native_memory summary failed: " + e.getMessage());
            return Map.of();
        }
    }

    private static final class CodeCacheSnapshot {
        final long usedKb;
        final long sizeKb;
        CodeCacheSnapshot(long usedKb, long sizeKb) {
            this.usedKb = usedKb;
            this.sizeKb = sizeKb;
        }
    }

    /**
     * Map raw collector / flag info to canonical algorithm name expected by Doctor rules.
     *
     * <p>For ZGC, detects Generational ZGC by checking the full list of collector
     * MBean names passed from the caller (already collected during iteration).
     * If any name contains {@code "ZGC Major"} or {@code "ZGC Minor"}, returns
     * {@code "ZGC (Generational)"}; otherwise returns {@code "ZGC"}.
     * All downstream code uses {@code gcAlgorithm.contains("ZGC")} so both values work.
     *
     * @param raw          first GC collector name (or flag-derived name for remote path)
     * @param vmArgs       JVM input arguments (used to detect GC flags)
     * @param allGcNames   all GC collector names; may be empty for the remote path
     */
    private static String canonicalGcAlgorithm(String raw, List<String> vmArgs,
                                                List<String> allGcNames) {
        // Prefer explicit user flags, but for ZGC fall through so we can detect generational.
        boolean flagIsZgc = false;
        for (String a : vmArgs) {
            if (a.contains("UseZGC")) { flagIsZgc = true; break; }
            if (a.contains("UseG1GC")) return "G1";
            if (a.contains("UseShenandoahGC")) return "Shenandoah";
            if (a.contains("UseParallelGC") || a.contains("UseParallelOldGC")) return "Parallel";
            if (a.contains("UseSerialGC")) return "Serial";
        }
        if (raw == null) return "unknown";
        String l = raw.toLowerCase();
        if (flagIsZgc || l.contains("zgc") || l.contains("z gc")) {
            // Detect generational ZGC: collector MBean names contain "ZGC Major" / "ZGC Minor"
            for (String name : allGcNames) {
                if (name.contains("ZGC Major") || name.contains("ZGC Minor")) {
                    return "ZGC (Generational)";
                }
            }
            return "ZGC";
        }
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
    private static final class HeapTotals {
        final long totalBytes;
        final long usedBytes;
        HeapTotals(long totalBytes, long usedBytes) {
            this.totalBytes = totalBytes;
            this.usedBytes = usedBytes;
        }
    }

    private static HeapTotals parseHeapTotals(String output) {
        if (output == null) return null;
        // e.g.: "garbage-first heap   total 524288K, used 65536K [0x..., 0x..., 0x...)"
        for (String line : output.split("\n")) {
            Matcher m = JCMD_HEAP_TOTAL_USED_KB.matcher(line.trim());
            if (m.find()) {
                return new HeapTotals(Long.parseLong(m.group(1)) * 1024L,
                        Long.parseLong(m.group(2)) * 1024L);
            }
        }
        return null;
    }

    private static void parseHeapInfo(String output, Map<String, JvmSnapshot.PoolInfo> pools) {
        if (output == null) return;
        for (String line : output.split("\n")) {
            Matcher m = JCMD_HEAP_POOL_PARSE.matcher(line.trim());
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
