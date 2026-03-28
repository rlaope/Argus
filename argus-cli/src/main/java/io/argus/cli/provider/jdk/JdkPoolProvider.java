package io.argus.cli.provider.jdk;

import io.argus.cli.model.PoolResult;
import io.argus.cli.provider.PoolProvider;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PoolProvider that parses {@code jcmd Thread.print} output to group threads by pool.
 */
public final class JdkPoolProvider implements PoolProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public PoolResult getPoolInfo(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "Thread.print");
        } catch (RuntimeException e) {
            return new PoolResult(0, 0, List.of());
        }
        return parseOutput(output);
    }

    static PoolResult parseOutput(String output) {
        // Parse threads: name and state
        List<String[]> threads = new ArrayList<>(); // [name, state]
        String currentThread = null;
        String currentState = null;

        for (String line : output.split("\n")) {
            String trimmed = line.trim();

            if (trimmed.startsWith("\"") && !trimmed.startsWith("\"Found")) {
                if (currentThread != null && currentState != null) {
                    threads.add(new String[]{currentThread, currentState});
                }
                currentThread = extractThreadName(trimmed);
                currentState = null;
            }

            if (trimmed.startsWith("java.lang.Thread.State:")) {
                String[] parts = trimmed.split(":", 2);
                if (parts.length == 2) {
                    currentState = parts[1].trim().split("\\s+")[0];
                }
            }
        }
        if (currentThread != null && currentState != null) {
            threads.add(new String[]{currentThread, currentState});
        }

        // Group threads by pool name
        Map<String, List<String>> poolThreadStates = new LinkedHashMap<>();

        for (String[] t : threads) {
            String poolName = classifyPool(t[0]);
            poolThreadStates.computeIfAbsent(poolName, k -> new ArrayList<>()).add(t[1]);
        }

        List<PoolResult.PoolInfo> pools = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : poolThreadStates.entrySet()) {
            Map<String, Integer> states = new LinkedHashMap<>();
            for (String state : entry.getValue()) {
                states.merge(state, 1, Integer::sum);
            }
            pools.add(new PoolResult.PoolInfo(entry.getKey(), entry.getValue().size(), Map.copyOf(states)));
        }

        // Sort by thread count descending
        pools.sort((a, b) -> Integer.compare(b.threadCount(), a.threadCount()));

        return new PoolResult(threads.size(), pools.size(), List.copyOf(pools));
    }

    // Common pool name patterns
    private static final Pattern FORK_JOIN = Pattern.compile("ForkJoinPool[\\.-]");
    private static final Pattern POOL_PREFIX = Pattern.compile("^(.*pool)-\\d+");
    private static final Pattern NUMBERED_SUFFIX = Pattern.compile("^(.+)-\\d+$");

    static String classifyPool(String threadName) {
        if (threadName == null || threadName.isEmpty()) return "(unknown)";

        // Well-known thread groups
        if (threadName.startsWith("ForkJoinPool.commonPool")) return "ForkJoinPool.commonPool";
        if (threadName.startsWith("ForkJoinPool")) {
            Matcher m = Pattern.compile("(ForkJoinPool-\\d+)").matcher(threadName);
            if (m.find()) return m.group(1);
            return "ForkJoinPool";
        }

        // "pool-N-thread-M" pattern (Executors.newFixedThreadPool etc.)
        Matcher poolMatcher = Pattern.compile("^(pool-\\d+)-thread-\\d+$").matcher(threadName);
        if (poolMatcher.matches()) return poolMatcher.group(1);

        // "XXX-pool-N" or "XXX-N" patterns
        Matcher prefixMatcher = POOL_PREFIX.matcher(threadName);
        if (prefixMatcher.matches()) return prefixMatcher.group(1);

        // OkHttp, Netty, gRPC, Tomcat, etc.
        if (threadName.startsWith("OkHttp")) return "OkHttp";
        if (threadName.startsWith("nioEventLoopGroup")) {
            Matcher m = Pattern.compile("(nioEventLoopGroup-\\d+)").matcher(threadName);
            if (m.find()) return m.group(1);
            return "nioEventLoopGroup";
        }
        if (threadName.startsWith("grpc-")) return "grpc";
        if (threadName.startsWith("http-nio-")) return "http-nio (Tomcat)";
        if (threadName.startsWith("catalina-")) return "catalina (Tomcat)";
        if (threadName.startsWith("scheduling-")) return "scheduling";

        // Generic numbered suffix: "SomeName-N"
        Matcher numbered = NUMBERED_SUFFIX.matcher(threadName);
        if (numbered.matches()) {
            String base = numbered.group(1);
            // Only group if the base doesn't look like a unique thread name
            if (!base.contains(" ") && base.length() < 40) return base;
        }

        // JVM internal threads
        if (threadName.startsWith("GC ") || threadName.startsWith("G1 ") || threadName.startsWith("ZGC")) return "(GC)";
        if (threadName.startsWith("C1 ") || threadName.startsWith("C2 ")) return "(JIT Compiler)";
        if (threadName.equals("Reference Handler") || threadName.equals("Finalizer")
                || threadName.equals("Signal Dispatcher") || threadName.startsWith("Common-Cleaner")
                || threadName.startsWith("Notification ") || threadName.startsWith("Attach ")) {
            return "(JVM Internal)";
        }

        return threadName;
    }

    private static String extractThreadName(String headerLine) {
        int start = headerLine.indexOf('"');
        if (start < 0) return headerLine;
        int end = headerLine.indexOf('"', start + 1);
        if (end < 0) return headerLine.substring(start + 1);
        return headerLine.substring(start + 1, end);
    }
}
