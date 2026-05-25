package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.jmx.JmxAttachment;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Implements {@code argus pool advise <pid>} — sizing advisor based on
 * ThreadMXBean sampling of the target JVM over a window.
 *
 * <p>Recommendation is the simpler heuristic {@code ceil(p99 * 1.5)} with a floor
 * of {@value #MIN_RECOMMENDED} — full Little's Law requires per-pool arrival rate
 * which is not available via standard JMX for arbitrary {@link java.util.concurrent.ExecutorService}.
 */
final class PoolAdviseHandler {

    static final int MIN_RECOMMENDED = 4;
    static final long DEFAULT_WINDOW_MS = 5_000L;
    static final long SAMPLE_INTERVAL_MS = 250L;

    /**
     * Pure recommendation function — testable without sampling.
     * Returns ceil(p99 * 1.5), floored at {@link #MIN_RECOMMENDED}.
     */
    static int recommendSize(int p99Active) {
        int scaled = (int) Math.ceil(p99Active * 1.5);
        return Math.max(MIN_RECOMMENDED, scaled);
    }

    void run(String[] args, CliConfig config, Messages messages) {
        boolean json = "json".equals(config.format());
        Long pidOverride = null;
        long windowMs = DEFAULT_WINDOW_MS;
        for (String a : args) {
            if ("--format=json".equals(a)) json = true;
            else if (a.startsWith("--window=")) {
                windowMs = parseWindow(a.substring(9), DEFAULT_WINDOW_MS);
            } else if ("--help".equals(a) || "-h".equals(a)) {
                System.out.println(messages.get("cmd.pool.advise.usage"));
                return;
            } else if (!a.startsWith("--")) {
                try { pidOverride = Long.parseLong(a); } catch (NumberFormatException ignored) {}
            }
        }
        if (pidOverride == null) {
            System.err.println(messages.get("error.pid.required"));
            throw new CommandExitException(2);
        }
        final long pid = pidOverride;
        if (!ProcessHandle.of(pid).isPresent()) {
            System.err.println(messages.get("error.pid.notfound", pid));
            throw new CommandExitException(1);
        }

        List<GroupStats> stats;
        try {
            final long winMs = windowMs;
            stats = JmxAttachment.withConnection(pid, mbs -> sample(mbs, winMs));
        } catch (Exception e) {
            System.err.println(messages.get("pool.attach.error", pid, e.getMessage()));
            throw new CommandExitException(1);
        }

        if (json) {
            System.out.println(toJson(pid, windowMs, stats));
            return;
        }
        if (stats.isEmpty()) {
            System.out.println(messages.get("pool.advise.none"));
            return;
        }

        System.out.println(messages.get("pool.advise.header", pid, windowMs));
        System.out.printf("  %-30s %10s %10s %12s %12s%n",
                "group", "p99Active", "blocking%", "configured", "recommended");
        for (GroupStats g : stats) {
            int rec = recommendSize(g.p99Active);
            String cfg = g.configuredSize > 0 ? String.valueOf(g.configuredSize) : "-";
            System.out.printf("  %-30s %10d %9.1f%% %12s %12d%n",
                    truncate(g.prefix, 30), g.p99Active, g.blockingRatio * 100.0, cfg, rec);
        }
        System.out.println(messages.get("pool.advise.rationale"));
    }

    static long parseWindow(String s, long fallback) {
        if (s == null || s.isEmpty()) return fallback;
        long mult = 1L;
        String num = s;
        if (s.endsWith("ms")) { mult = 1L; num = s.substring(0, s.length() - 2); }
        else if (s.endsWith("s")) { mult = 1_000L; num = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 60_000L; num = s.substring(0, s.length() - 1); }
        try { return Long.parseLong(num) * mult; }
        catch (NumberFormatException e) { return fallback; }
    }

    private List<GroupStats> sample(MBeanServerConnection mbs, long windowMs) throws Exception {
        ThreadMXBean threads = java.lang.management.ManagementFactory.newPlatformMXBeanProxy(
                mbs, java.lang.management.ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);

        Map<String, List<Integer>> perSampleActive = new HashMap<>();
        Map<String, Integer> nonRunnableTotals = new HashMap<>();
        Map<String, Integer> sampleTotals = new HashMap<>();

        long deadline = System.currentTimeMillis() + windowMs;
        int samples = 0;
        while (System.currentTimeMillis() < deadline) {
            ThreadInfo[] infos = threads.dumpAllThreads(false, false);
            Map<String, Integer> activeThisSample = new HashMap<>();
            for (ThreadInfo info : infos) {
                if (info == null) continue;
                String prefix = prefixOf(info.getThreadName());
                if (prefix == null) continue;
                activeThisSample.merge(prefix, 1, Integer::sum);
                sampleTotals.merge(prefix, 1, Integer::sum);
                if (info.getThreadState() != Thread.State.RUNNABLE) {
                    nonRunnableTotals.merge(prefix, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> e : activeThisSample.entrySet()) {
                perSampleActive.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
            samples++;
            if (samples >= 3 && System.currentTimeMillis() + SAMPLE_INTERVAL_MS >= deadline) break;
            try { Thread.sleep(SAMPLE_INTERVAL_MS); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Map<String, Integer> tomcatConfigured = readTomcatConfigured(mbs);

        List<GroupStats> result = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : new TreeMap<>(perSampleActive).entrySet()) {
            String prefix = e.getKey();
            List<Integer> series = e.getValue();
            int p99 = percentile(series, 99);
            int totalObs = sampleTotals.getOrDefault(prefix, 0);
            int nonRun = nonRunnableTotals.getOrDefault(prefix, 0);
            double ratio = totalObs == 0 ? 0.0 : (double) nonRun / (double) totalObs;
            int configured = tomcatConfigured.getOrDefault(prefix, 0);
            result.add(new GroupStats(prefix, p99, ratio, configured));
        }
        result.sort(Comparator.comparingInt((GroupStats g) -> g.p99Active).reversed());
        return result;
    }

    /**
     * Best-effort discovery of Tomcat thread-pool maxThreads, keyed by the
     * thread-name prefix Tomcat uses (typically "http-nio-").
     */
    private Map<String, Integer> readTomcatConfigured(MBeanServerConnection mbs) {
        Map<String, Integer> out = new HashMap<>();
        try {
            Set<ObjectName> tomcat = mbs.queryNames(new ObjectName("Catalina:type=ThreadPool,*"), null);
            for (ObjectName on : tomcat) {
                String name = on.getKeyProperty("name");
                if (name == null) continue;
                int max = readIntSafe(mbs, on, "maxThreads");
                String clean = name.replace("\"", "");
                out.put("http-nio-" + extractPort(clean), max);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String extractPort(String connectorName) {
        int dash = connectorName.lastIndexOf('-');
        return dash >= 0 ? connectorName.substring(dash + 1) : connectorName;
    }

    private static int readIntSafe(MBeanServerConnection mbs, ObjectName on, String attr) {
        try {
            Object v = mbs.getAttribute(on, attr);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Exception ignored) {}
        return 0;
    }

    static int percentile(List<Integer> values, int p) {
        if (values.isEmpty()) return 0;
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    /**
     * Maps a raw thread name to a stable group prefix. Returns null for threads
     * that should not be grouped (single-purpose daemon threads).
     */
    static String prefixOf(String threadName) {
        if (threadName == null || threadName.isEmpty()) return null;
        if (threadName.startsWith("http-nio-")) {
            int second = threadName.indexOf('-', 9);
            return second > 0 ? threadName.substring(0, second) : threadName;
        }
        if (threadName.startsWith("ForkJoinPool")) {
            int worker = threadName.indexOf("-worker-");
            return worker > 0 ? threadName.substring(0, worker) : threadName;
        }
        if (threadName.startsWith("pool-")) {
            int thread = threadName.indexOf("-thread-");
            return thread > 0 ? threadName.substring(0, thread) : threadName;
        }
        if (threadName.startsWith("scheduling-")) return "scheduling";
        if (threadName.contains("-")) {
            int last = threadName.lastIndexOf('-');
            String tail = threadName.substring(last + 1);
            try { Integer.parseInt(tail); return threadName.substring(0, last); }
            catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private String toJson(long pid, long windowMs, List<GroupStats> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"pid\": ").append(pid)
          .append(",\n  \"windowMs\": ").append(windowMs)
          .append(",\n  \"pools\": [");
        for (int i = 0; i < stats.size(); i++) {
            GroupStats g = stats.get(i);
            int rec = recommendSize(g.p99Active);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {")
              .append("\"group\": \"").append(escape(g.prefix)).append("\", ")
              .append("\"p99Active\": ").append(g.p99Active).append(", ")
              .append("\"blockingRatio\": ").append(String.format("%.4f", g.blockingRatio)).append(", ")
              .append("\"configured\": ").append(g.configuredSize).append(", ")
              .append("\"recommended\": ").append(rec).append("}");
        }
        sb.append(stats.isEmpty() ? "]" : "\n  ]").append("\n}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static final class GroupStats {
        final String prefix;
        final int p99Active;
        final double blockingRatio;
        final int configuredSize;

        GroupStats(String prefix, int p99Active, double blockingRatio, int configuredSize) {
            this.prefix = prefix;
            this.p99Active = p99Active;
            this.blockingRatio = blockingRatio;
            this.configuredSize = configuredSize;
        }
    }
}
