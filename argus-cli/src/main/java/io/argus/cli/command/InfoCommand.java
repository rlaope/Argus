package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.InfoResult;
import io.argus.cli.provider.InfoProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.RichRenderer;

import java.util.Map;

/**
 * Shows JVM information for a given PID.
 */
public final class InfoCommand implements Command {

    private static final int WIDTH = 60;

    @Override
    public String name() {
        return "info";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.info.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println(messages.get("error.pid.required"));
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[0]));
            return;
        }

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            }
        }

        InfoProvider provider = registry.findInfoProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        InfoResult result = provider.getVmInfo(pid);

        if (json) {
            printJson(result);
            return;
        }

        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.info"),
                WIDTH, "pid:" + pid));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("VM Name", 10) + "  " + result.vmName(), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("Version", 10) + "  " + result.vmVersion(), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("Vendor", 10) + "  " + result.vmVendor(), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("Uptime", 10) + "  " + RichRenderer.formatDuration(result.uptimeMs()), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("PID", 10) + "  " + result.pid(), WIDTH));

        if (!result.vmFlags().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine("VM Flags:", WIDTH));
            for (String flag : result.vmFlags()) {
                System.out.println(RichRenderer.boxLine("  " + flag, WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(InfoResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"vmName\":\"").append(escape(result.vmName())).append('"')
          .append(",\"vmVersion\":\"").append(escape(result.vmVersion())).append('"')
          .append(",\"vmVendor\":\"").append(escape(result.vmVendor())).append('"')
          .append(",\"uptimeMs\":").append(result.uptimeMs())
          .append(",\"pid\":").append(result.pid())
          .append(",\"vmFlags\":[");
        for (int i = 0; i < result.vmFlags().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(result.vmFlags().get(i))).append('"');
        }
        sb.append("],\"systemProperties\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : result.systemProperties().entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escape(e.getKey())).append("\":\"")
              .append(escape(e.getValue())).append('"');
            first = false;
        }
        sb.append("}}");
        System.out.println(sb);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
