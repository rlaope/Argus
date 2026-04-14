package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.Set;

/**
 * Inspects Spring Boot applications via JMX MBeans.
 *
 * <p>Usage:
 * <pre>
 * argus spring &lt;pid&gt;              # overview: detect Spring Boot, show key metrics
 * argus spring &lt;pid&gt; --beans      # list beans with count
 * argus spring &lt;pid&gt; --datasource # connection pool stats
 * </pre>
 */
public final class SpringCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name() { return "spring"; }
    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }
    @Override public CommandMode mode() { return CommandMode.READ; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.spring.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println(messages.get("error.pid.required"));
            return;
        }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        boolean useColor = config.color();
        boolean showBeans = false;
        boolean showDatasource = false;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--beans")) showBeans = true;
            else if (arg.equals("--datasource")) showDatasource = true;
        }

        // Detect Spring Boot via system properties
        String appName = detectSpringApp(pid);
        if (appName == null) {
            System.err.println(messages.get("error.spring.not.detected", pid));
            return;
        }

        String connectorAddr = getConnectorAddress(pid);
        if (connectorAddr == null) {
            System.err.println(messages.get("error.spring.jmx.unavailable", pid));
            return;
        }

        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(connectorAddr))) {
            MBeanServerConnection mbs = connector.getMBeanServerConnection();

            if (showBeans) {
                renderBeans(mbs, pid, appName, useColor, messages);
            } else if (showDatasource) {
                renderDatasource(mbs, pid, appName, useColor, messages);
            } else {
                renderOverview(mbs, pid, appName, useColor, messages);
            }
        } catch (Exception e) {
            System.err.println(messages.get("error.spring.jmx.error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Spring detection
    // -------------------------------------------------------------------------

    private String detectSpringApp(long pid) {
        try {
            String output = JcmdExecutor.execute(pid, "VM.system_properties");
            String appName = null;
            for (String line : output.split("\n")) {
                if (line.startsWith("spring.application.name=")) {
                    appName = line.substring("spring.application.name=".length()).trim();
                }
            }
            // If not set via property, check if spring classes are loaded
            if (appName == null) {
                String histoOutput = JcmdExecutor.execute(pid, "GC.class_histogram -all");
                if (histoOutput.contains("org.springframework")) {
                    appName = "<spring-app>";
                }
            }
            return appName;
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private void renderOverview(MBeanServerConnection mbs, long pid, String appName,
                                boolean c, Messages messages) throws Exception {
        // Gather Spring metadata
        String springBootVersion = readStringAttr(mbs, "org.springframework.boot:type=Admin,name=SpringApplication", "SpringBootVersion");
        String activeProfiles = readStringAttr(mbs, "org.springframework.boot:type=Admin,name=SpringApplication", "ActiveProfiles");

        // Health endpoint
        String healthStatus = readHealthStatus(mbs);

        // HikariCP pool stats
        HikariStats hikari = readHikariStats(mbs);

        // Tomcat thread pool
        TomcatStats tomcat = readTomcatStats(mbs);

        String subtitle = appName + (springBootVersion != null ? " v" + springBootVersion : "");
        System.out.print(RichRenderer.brandedHeader(c, "spring", messages.get("desc.spring")));
        System.out.println(RichRenderer.boxHeader(c, messages.get("header.spring"), WIDTH,
                "pid:" + pid, subtitle));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Application info
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + RichRenderer.padRight(messages.get("spring.label.application"), 20)
                + AnsiStyle.style(c, AnsiStyle.RESET) + appName, WIDTH));
        if (springBootVersion != null) {
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.version"), 20) + springBootVersion, WIDTH));
        }
        if (activeProfiles != null && !activeProfiles.isEmpty() && !"[]".equals(activeProfiles)) {
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.profiles"), 20) + activeProfiles, WIDTH));
        }
        if (healthStatus != null) {
            String statusColor = "UP".equalsIgnoreCase(healthStatus) ? AnsiStyle.GREEN : AnsiStyle.RED;
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.health"), 20)
                    + AnsiStyle.style(c, statusColor) + healthStatus
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }

        // Datasource section (HikariCP)
        if (hikari != null) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + messages.get("spring.section.datasource")
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            double pct = hikari.maxSize > 0 ? (double) hikari.active / hikari.maxSize * 100 : 0;
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.active.conns"), 24)
                    + hikari.active + " / " + hikari.maxSize
                    + String.format(" (%.0f%%)", pct), WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.idle.conns"), 24)
                    + hikari.idle, WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.pending.threads"), 24)
                    + hikari.pending, WIDTH));
            if (hikari.maxLifetimeMs > 0) {
                System.out.println(RichRenderer.boxLine(
                        "  " + RichRenderer.padRight(messages.get("spring.label.max.lifetime"), 24)
                        + (hikari.maxLifetimeMs / 1000) + "s", WIDTH));
            }
        }

        // Tomcat section
        if (tomcat != null) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + messages.get("spring.section.tomcat")
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.active.threads"), 24)
                    + tomcat.currentThreadsBusy + " / " + tomcat.maxThreads, WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.request.count"), 24)
                    + formatWithCommas(tomcat.requestCount), WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.error.count"), 24)
                    + formatWithCommas(tomcat.errorCount), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c,
                messages.get("spring.hint.subcommands"), WIDTH));
    }

    private void renderBeans(MBeanServerConnection mbs, long pid, String appName,
                             boolean c, Messages messages) throws Exception {
        // Query Spring context MBean for bean count
        String beanCount = null;
        try {
            Set<ObjectName> ctxBeans = mbs.queryNames(
                    new ObjectName("org.springframework.context:*"), null);
            if (!ctxBeans.isEmpty()) {
                ObjectName ctx = ctxBeans.iterator().next();
                Object val = mbs.getAttribute(ctx, "BeanCount");
                if (val != null) beanCount = String.valueOf(val);
            }
        } catch (Exception ignored) {}

        System.out.print(RichRenderer.brandedHeader(c, "spring", messages.get("desc.spring")));
        System.out.println(RichRenderer.boxHeader(c, messages.get("header.spring.beans"), WIDTH,
                "pid:" + pid, appName));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (beanCount != null) {
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.bean.count"), 24)
                    + AnsiStyle.style(c, AnsiStyle.BOLD) + beanCount
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        } else {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                    + messages.get("spring.beans.unavailable")
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }

        // List context domains
        Set<ObjectName> ctxNames = mbs.queryNames(
                new ObjectName("org.springframework.context:*"), null);
        if (!ctxNames.isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + messages.get("spring.label.contexts")
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            for (ObjectName n : ctxNames) {
                System.out.println(RichRenderer.boxLine(
                        "    " + AnsiStyle.style(c, AnsiStyle.GREEN) + n.getKeyPropertyListString()
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c, null, WIDTH));
    }

    private void renderDatasource(MBeanServerConnection mbs, long pid, String appName,
                                  boolean c, Messages messages) throws Exception {
        HikariStats hikari = readHikariStats(mbs);

        System.out.print(RichRenderer.brandedHeader(c, "spring", messages.get("desc.spring")));
        System.out.println(RichRenderer.boxHeader(c, messages.get("header.spring.datasource"), WIDTH,
                "pid:" + pid, appName));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (hikari == null) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                    + messages.get("spring.datasource.unavailable")
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        } else {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + messages.get("spring.section.hikari")
                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            double pct = hikari.maxSize > 0 ? (double) hikari.active / hikari.maxSize * 100 : 0;
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.active.conns"), 24)
                    + hikari.active + " / " + hikari.maxSize
                    + String.format(" (%.0f%%)", pct), WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.idle.conns"), 24)
                    + hikari.idle, WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.pending.threads"), 24)
                    + hikari.pending, WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + RichRenderer.padRight(messages.get("spring.label.total.conns"), 24)
                    + hikari.total, WIDTH));
            if (hikari.maxLifetimeMs > 0) {
                System.out.println(RichRenderer.boxLine(
                        "  " + RichRenderer.padRight(messages.get("spring.label.max.lifetime"), 24)
                        + (hikari.maxLifetimeMs / 1000) + "s", WIDTH));
            }
            if (hikari.connectionTimeoutMs > 0) {
                System.out.println(RichRenderer.boxLine(
                        "  " + RichRenderer.padRight(messages.get("spring.label.conn.timeout"), 24)
                        + hikari.connectionTimeoutMs + "ms", WIDTH));
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c, null, WIDTH));
    }

    // -------------------------------------------------------------------------
    // MBean readers
    // -------------------------------------------------------------------------

    private String readHealthStatus(MBeanServerConnection mbs) {
        try {
            ObjectName health = new ObjectName("org.springframework.boot:type=Endpoint,name=Health");
            Object val = mbs.getAttribute(health, "Status");
            return val != null ? String.valueOf(val) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private HikariStats readHikariStats(MBeanServerConnection mbs) {
        try {
            // Try Pool (HikariCP) MBeans
            Set<ObjectName> pools = mbs.queryNames(
                    new ObjectName("com.zaxxer.hikari:type=Pool (*),*"), null);
            if (pools.isEmpty()) {
                pools = mbs.queryNames(new ObjectName("com.zaxxer.hikari:*"), null);
            }
            if (pools.isEmpty()) return null;

            ObjectName pool = pools.iterator().next();
            HikariStats stats = new HikariStats();
            stats.active = getLongAttr(mbs, pool, "ActiveConnections");
            stats.idle = getLongAttr(mbs, pool, "IdleConnections");
            stats.pending = getLongAttr(mbs, pool, "PendingThreads");
            stats.total = getLongAttr(mbs, pool, "TotalConnections");
            stats.maxSize = getLongAttr(mbs, pool, "MaximumPoolSize");

            // Pool config MBean
            try {
                Set<ObjectName> configs = mbs.queryNames(
                        new ObjectName("com.zaxxer.hikari:type=PoolConfig (*),*"), null);
                if (!configs.isEmpty()) {
                    ObjectName cfg = configs.iterator().next();
                    stats.maxLifetimeMs = getLongAttr(mbs, cfg, "MaxLifetime");
                    stats.connectionTimeoutMs = getLongAttr(mbs, cfg, "ConnectionTimeout");
                    if (stats.maxSize == 0) {
                        stats.maxSize = getLongAttr(mbs, cfg, "MaximumPoolSize");
                    }
                }
            } catch (Exception ignored) {}

            return stats;
        } catch (Exception ignored) {
            return null;
        }
    }

    private TomcatStats readTomcatStats(MBeanServerConnection mbs) {
        try {
            Set<ObjectName> threadPools = mbs.queryNames(
                    new ObjectName("Tomcat:type=ThreadPool,*"), null);
            if (threadPools.isEmpty()) {
                threadPools = mbs.queryNames(new ObjectName("*:type=ThreadPool,*"), null);
            }
            if (threadPools.isEmpty()) return null;

            ObjectName tp = threadPools.iterator().next();
            TomcatStats stats = new TomcatStats();
            stats.currentThreadsBusy = getLongAttr(mbs, tp, "currentThreadsBusy");
            stats.maxThreads = getLongAttr(mbs, tp, "maxThreads");

            // Request stats from GlobalRequestProcessor
            Set<ObjectName> grps = mbs.queryNames(
                    new ObjectName("Tomcat:type=GlobalRequestProcessor,*"), null);
            if (grps.isEmpty()) {
                grps = mbs.queryNames(new ObjectName("*:type=GlobalRequestProcessor,*"), null);
            }
            if (!grps.isEmpty()) {
                ObjectName grp = grps.iterator().next();
                stats.requestCount = getLongAttr(mbs, grp, "requestCount");
                stats.errorCount = getLongAttr(mbs, grp, "errorCount");
            }
            return stats;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readStringAttr(MBeanServerConnection mbs, String objectName, String attr) {
        try {
            ObjectName on = new ObjectName(objectName);
            Object val = mbs.getAttribute(on, attr);
            if (val == null) return null;
            String s = String.valueOf(val).trim();
            return s.isEmpty() ? null : s;
        } catch (Exception ignored) {
            return null;
        }
    }

    private long getLongAttr(MBeanServerConnection mbs, ObjectName on, String attr) {
        try {
            Object val = mbs.getAttribute(on, attr);
            if (val instanceof Number n) return n.longValue();
            if (val instanceof CompositeData cd) {
                Object v = cd.get(attr);
                if (v instanceof Number n) return n.longValue();
            }
            return 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // JMX attach
    // -------------------------------------------------------------------------

    private String getConnectorAddress(long pid) {
        try {
            var vm = com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
            try {
                String addr = vm.getAgentProperties().getProperty(
                        "com.sun.management.jmxremote.localConnectorAddress");
                if (addr == null) {
                    vm.startLocalManagementAgent();
                    addr = vm.getAgentProperties().getProperty(
                            "com.sun.management.jmxremote.localConnectorAddress");
                }
                return addr;
            } finally {
                vm.detach();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String formatWithCommas(long n) {
        String s = String.valueOf(n);
        if (s.length() <= 3) return s;
        StringBuilder sb = new StringBuilder();
        int rem = s.length() % 3;
        if (rem > 0) sb.append(s, 0, rem);
        for (int i = rem; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s, i, i + 3);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Data holders
    // -------------------------------------------------------------------------

    private static final class HikariStats {
        long active;
        long idle;
        long pending;
        long total;
        long maxSize;
        long maxLifetimeMs;
        long connectionTimeoutMs;
    }

    private static final class TomcatStats {
        long currentThreadsBusy;
        long maxThreads;
        long requestCount;
        long errorCount;
    }
}
