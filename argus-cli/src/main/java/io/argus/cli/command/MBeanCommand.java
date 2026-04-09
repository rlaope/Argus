package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * MBean browser — inspect JVM MBeans via JMX attach.
 *
 * <p>Connects to a local JVM process using the Attach API to obtain the
 * JMX connector address, then browses and reads MBean attributes.
 *
 * <p>Usage:
 * <pre>
 * argus mbean 12345                                          # list all MBean domains
 * argus mbean 12345 --list                                   # list all MBean names
 * argus mbean 12345 --name java.lang:type=Memory             # show all attributes
 * argus mbean 12345 --name java.lang:type=Memory --attr HeapMemoryUsage
 * argus mbean 12345 --domain java.lang                       # list MBeans in domain
 * argus mbean 12345 --format=json                            # JSON output
 * </pre>
 */
public final class MBeanCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name() { return "mbean"; }
    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.mbean.desc");
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

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        boolean listAll = false;
        String mbeanName = null;
        String attrName = null;
        String domain = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--list")) listAll = true;
            else if (arg.startsWith("--name=")) mbeanName = arg.substring(7);
            else if (arg.equals("--name") && i + 1 < args.length) mbeanName = args[++i];
            else if (arg.startsWith("--attr=")) attrName = arg.substring(7);
            else if (arg.equals("--attr") && i + 1 < args.length) attrName = args[++i];
            else if (arg.startsWith("--domain=")) domain = arg.substring(9);
            else if (arg.equals("--domain") && i + 1 < args.length) domain = args[++i];
            else if (arg.equals("--format=json")) json = true;
        }

        String connectorAddr = getConnectorAddress(pid);
        if (connectorAddr == null) {
            System.err.println("Cannot connect to JVM " + pid + " via JMX. Try: argus jmx " + pid + " start-local");
            return;
        }

        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(connectorAddr))) {
            MBeanServerConnection mbs = connector.getMBeanServerConnection();

            if (mbeanName != null) {
                showMBeanAttributes(mbs, mbeanName, attrName, useColor, json);
            } else if (listAll || domain != null) {
                listMBeans(mbs, domain, useColor, json);
            } else {
                listDomains(mbs, useColor, json);
            }
        } catch (Exception e) {
            System.err.println("JMX connection error: " + e.getMessage());
        }
    }

    private void listDomains(MBeanServerConnection mbs, boolean c, boolean json) throws Exception {
        String[] domains = mbs.getDomains();
        java.util.Arrays.sort(domains);

        if (json) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < domains.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(domains[i])).append('"');
            }
            sb.append(']');
            System.out.println(sb);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(c, "mbean", "JMX MBean browser"));
        System.out.println(RichRenderer.boxHeader(c, "MBean Domains", WIDTH,
                domains.length + " domains"));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        int mbeanCount = mbs.getMBeanCount();
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(c, AnsiStyle.DIM) + "  " + mbeanCount + " total MBeans"
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        for (String d : domains) {
            Set<ObjectName> names = mbs.queryNames(new ObjectName(d + ":*"), null);
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD, AnsiStyle.CYAN) + d
                            + AnsiStyle.style(c, AnsiStyle.RESET)
                            + AnsiStyle.style(c, AnsiStyle.DIM) + "  (" + names.size() + " beans)"
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(c, AnsiStyle.DIM)
                        + "  Use --domain <name> to list beans, --name <objectName> to inspect"
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxFooter(c, null, WIDTH));
    }

    private void listMBeans(MBeanServerConnection mbs, String domain, boolean c, boolean json)
            throws Exception {
        ObjectName pattern = domain != null ? new ObjectName(domain + ":*") : null;
        Set<ObjectName> names = new TreeSet<>(mbs.queryNames(pattern, null));

        if (json) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (ObjectName n : names) {
                if (!first) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(n.toString())).append('"');
                first = false;
            }
            sb.append(']');
            System.out.println(sb);
            return;
        }

        String title = domain != null ? "MBeans: " + domain : "All MBeans";
        System.out.print(RichRenderer.brandedHeader(c, "mbean", "JMX MBean browser"));
        System.out.println(RichRenderer.boxHeader(c, title, WIDTH, names.size() + " beans"));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String lastDomain = "";
        for (ObjectName n : names) {
            if (!n.getDomain().equals(lastDomain)) {
                if (!lastDomain.isEmpty()) System.out.println(RichRenderer.emptyLine(WIDTH));
                lastDomain = n.getDomain();
                System.out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(c, AnsiStyle.BOLD, AnsiStyle.CYAN)
                                + lastDomain + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            }
            String props = n.getKeyPropertyListString();
            System.out.println(RichRenderer.boxLine(
                    "    " + AnsiStyle.style(c, AnsiStyle.GREEN) + props
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(c, null, WIDTH));
    }

    private void showMBeanAttributes(MBeanServerConnection mbs, String name, String attrFilter,
                                     boolean c, boolean json) throws Exception {
        ObjectName objName = new ObjectName(name);
        MBeanInfo info = mbs.getMBeanInfo(objName);
        MBeanAttributeInfo[] attrs = info.getAttributes();

        List<AttrEntry> entries = new ArrayList<>();
        for (MBeanAttributeInfo attr : attrs) {
            if (!attr.isReadable()) continue;
            if (attrFilter != null && !attr.getName().equalsIgnoreCase(attrFilter)) continue;
            try {
                Object value = mbs.getAttribute(objName, attr.getName());
                entries.add(new AttrEntry(attr.getName(), attr.getType(), formatAttrValue(value)));
            } catch (Exception e) {
                entries.add(new AttrEntry(attr.getName(), attr.getType(), "<error: " + e.getMessage() + ">"));
            }
        }

        if (json) {
            printJsonAttrs(name, entries);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(c, "mbean", "JMX MBean browser"));
        System.out.println(RichRenderer.boxHeader(c, "MBean: " + name, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                        + "Class: " + info.getClassName()
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        if (info.getDescription() != null && !info.getDescription().isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                            + info.getDescription()
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        }
        System.out.println(RichRenderer.emptyLine(WIDTH));

        for (AttrEntry e : entries) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD, AnsiStyle.GREEN)
                            + e.name + AnsiStyle.style(c, AnsiStyle.RESET)
                            + AnsiStyle.style(c, AnsiStyle.DIM) + " (" + shortenType(e.type) + ")"
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            // Multi-line values
            for (String line : e.value.split("\n")) {
                System.out.println(RichRenderer.boxLine("    " + line, WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(c, entries.size() + " attributes", WIDTH));
    }

    private String formatAttrValue(Object value) {
        if (value == null) return "null";
        if (value instanceof CompositeData cd) {
            StringBuilder sb = new StringBuilder();
            for (String key : cd.getCompositeType().keySet()) {
                Object v = cd.get(key);
                String formatted = v instanceof Long l && l > 1024 * 1024
                        ? String.format("%,d (%s)", l, RichRenderer.formatBytes(l))
                        : String.valueOf(v);
                sb.append(key).append(" = ").append(formatted).append('\n');
            }
            return sb.toString().stripTrailing();
        }
        if (value instanceof TabularData td) {
            StringBuilder sb = new StringBuilder();
            for (Object row : td.values()) {
                if (row instanceof CompositeData cd) {
                    for (String key : cd.getCompositeType().keySet()) {
                        sb.append(key).append("=").append(cd.get(key)).append("  ");
                    }
                    sb.append('\n');
                }
            }
            return sb.toString().stripTrailing();
        }
        if (value instanceof long[] la) {
            return "length=" + la.length;
        }
        if (value instanceof Long l && l > 1024 * 1024) {
            return String.format("%,d (%s)", l, RichRenderer.formatBytes(l));
        }
        return String.valueOf(value);
    }

    private String shortenType(String type) {
        if (type == null) return "?";
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private void printJsonAttrs(String name, List<AttrEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"mbean\":\"").append(RichRenderer.escapeJson(name)).append("\",\"attributes\":{");
        for (int i = 0; i < entries.size(); i++) {
            AttrEntry e = entries.get(i);
            if (i > 0) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(e.name)).append("\":\"")
              .append(RichRenderer.escapeJson(e.value)).append('"');
        }
        sb.append("}}");
        System.out.println(sb);
    }

    /**
     * Obtains the JMX connector address for a local JVM by PID using the Attach API.
     */
    private String getConnectorAddress(long pid) {
        try {
            var vm = com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
            try {
                String addr = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
                if (addr == null) {
                    // Start the local management agent
                    vm.startLocalManagementAgent();
                    addr = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
                }
                return addr;
            } finally {
                vm.detach();
            }
        } catch (Exception e) {
            System.err.println("Cannot attach to PID " + pid + ": " + e.getMessage());
            return null;
        }
    }

    private record AttrEntry(String name, String type, String value) {}
}
