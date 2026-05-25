package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.jmx.JmxAttachment;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Implements {@code argus pool jdbc <pid>} — JDBC connection pool state for
 * HikariCP and Tomcat JDBC pools, via JMX.
 *
 * <p>Leak-thread attribution (which thread frame acquired a leaked connection)
 * is intentionally out of scope for this slice — that requires HikariCP's
 * {@code leakDetectionThreshold} opt-in or instrumentation and ships in a
 * follow-up slice of issue #225.
 */
final class PoolJdbcHandler {

    /** Verdict severities for a single JDBC pool. */
    enum Verdict { OK, WARN, CRIT }

    static final double UTILIZATION_WARN = 0.85;

    /**
     * Pure decision function — testable without a live JVM.
     * CRIT: any waiting threads. WARN: utilization at/above {@value #UTILIZATION_WARN}. OK: otherwise.
     */
    static Verdict computeVerdict(int active, int max, int waiting) {
        if (waiting > 0) return Verdict.CRIT;
        if (max > 0 && (double) active / (double) max >= UTILIZATION_WARN) return Verdict.WARN;
        return Verdict.OK;
    }

    void run(String[] args, CliConfig config, Messages messages) {
        boolean json = "json".equals(config.format());
        Long pidOverride = null;
        for (String a : args) {
            if ("--format=json".equals(a)) json = true;
            else if ("--help".equals(a) || "-h".equals(a)) {
                System.out.println(messages.get("cmd.pool.jdbc.usage"));
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

        List<PoolRow> rows;
        try {
            rows = JmxAttachment.withConnection(pid, this::collect);
        } catch (Exception e) {
            System.err.println(messages.get("pool.attach.error", pid, e.getMessage()));
            throw new CommandExitException(1);
        }

        if (json) {
            System.out.println(toJson(pid, rows));
            return;
        }

        if (rows.isEmpty()) {
            System.out.println(messages.get("pool.jdbc.none"));
            return;
        }

        System.out.println(messages.get("pool.jdbc.header", pid));
        System.out.printf("  %-32s %7s %7s %7s %9s   %s%n",
                "pool", "active", "idle", "total", "waiting", "verdict");
        for (PoolRow r : rows) {
            Verdict v = computeVerdict(r.active, r.max, r.waiting);
            System.out.printf("  %-32s %7d %7d %7d %9d   %s%n",
                    truncate(r.name, 32), r.active, r.idle, r.total, r.waiting, v.name());
        }
        long crit = rows.stream().filter(r -> computeVerdict(r.active, r.max, r.waiting) == Verdict.CRIT).count();
        long warn = rows.stream().filter(r -> computeVerdict(r.active, r.max, r.waiting) == Verdict.WARN).count();
        System.out.println(messages.get("pool.jdbc.verdict.summary", crit, warn, rows.size()));
    }

    private List<PoolRow> collect(MBeanServerConnection mbs) throws IOException {
        List<PoolRow> out = new ArrayList<>();
        out.addAll(collectHikari(mbs));
        out.addAll(collectTomcat(mbs));
        return out;
    }

    private List<PoolRow> collectHikari(MBeanServerConnection mbs) throws IOException {
        List<PoolRow> out = new ArrayList<>();
        try {
            Set<ObjectName> pools = mbs.queryNames(new ObjectName("com.zaxxer.hikari:type=Pool (*),*"), null);
            if (pools.isEmpty()) {
                pools = mbs.queryNames(new ObjectName("com.zaxxer.hikari:type=Pool*,*"), null);
            }
            Set<ObjectName> sorted = new TreeSet<>(pools);
            for (ObjectName on : sorted) {
                int active = readIntSafe(mbs, on, "ActiveConnections");
                int idle = readIntSafe(mbs, on, "IdleConnections");
                int total = readIntSafe(mbs, on, "TotalConnections");
                int waiting = readIntSafe(mbs, on, "ThreadsAwaitingConnection");
                int max = readMaxForHikari(mbs, on);
                out.add(new PoolRow(extractHikariPoolName(on), active, idle, total, waiting, max, "hikari"));
            }
        } catch (MalformedObjectNameException impossible) {
            // The query patterns are constants, so this is unreachable.
        }
        return out;
    }

    private int readMaxForHikari(MBeanServerConnection mbs, ObjectName poolName) {
        try {
            String configName = poolName.getCanonicalName()
                    .replace("type=Pool", "type=PoolConfig");
            ObjectName cfg = new ObjectName(configName);
            return readIntSafe(mbs, cfg, "MaximumPoolSize");
        } catch (Exception e) {
            return 0;
        }
    }

    private List<PoolRow> collectTomcat(MBeanServerConnection mbs) throws IOException {
        List<PoolRow> out = new ArrayList<>();
        try {
            Set<ObjectName> pools = mbs.queryNames(new ObjectName("tomcat.jdbc:type=ConnectionPool,*"), null);
            for (ObjectName on : new TreeSet<>(pools)) {
                int active = readIntSafe(mbs, on, "Active");
                int idle = readIntSafe(mbs, on, "Idle");
                int total = active + idle;
                int waiting = readIntSafe(mbs, on, "WaitCount");
                int max = readIntSafe(mbs, on, "MaxActive");
                String name = on.getKeyProperty("name");
                if (name == null) name = on.getKeyProperty("class");
                if (name == null) name = on.toString();
                out.add(new PoolRow(name, active, idle, total, waiting, max, "tomcat-jdbc"));
            }
        } catch (MalformedObjectNameException impossible) {
            // The query patterns are constants, so this is unreachable.
        }
        return out;
    }

    private static int readIntSafe(MBeanServerConnection mbs, ObjectName on, String attr) {
        try {
            Object v = mbs.getAttribute(on, attr);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String extractHikariPoolName(ObjectName on) {
        String name = on.getKeyProperty("type");
        if (name != null && name.startsWith("Pool (") && name.endsWith(")")) {
            return name.substring(6, name.length() - 1);
        }
        return on.toString();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private String toJson(long pid, List<PoolRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"pid\": ").append(pid)
          .append(",\n  \"pools\": [");
        for (int i = 0; i < rows.size(); i++) {
            PoolRow r = rows.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {")
              .append("\"name\": \"").append(escape(r.name)).append("\", ")
              .append("\"kind\": \"").append(r.kind).append("\", ")
              .append("\"active\": ").append(r.active).append(", ")
              .append("\"idle\": ").append(r.idle).append(", ")
              .append("\"total\": ").append(r.total).append(", ")
              .append("\"max\": ").append(r.max).append(", ")
              .append("\"waiting\": ").append(r.waiting).append(", ")
              .append("\"verdict\": \"").append(computeVerdict(r.active, r.max, r.waiting)).append("\"}");
        }
        sb.append(rows.isEmpty() ? "]" : "\n  ]").append("\n}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static final class PoolRow {
        final String name;
        final int active, idle, total, waiting, max;
        final String kind;

        PoolRow(String name, int active, int idle, int total, int waiting, int max, String kind) {
            this.name = name;
            this.active = active;
            this.idle = idle;
            this.total = total;
            this.waiting = waiting;
            this.max = max;
            this.kind = kind;
        }
    }
}
