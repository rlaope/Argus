package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.InfoResult;
import io.argus.cli.provider.InfoProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.util.Map;

/**
 * Shows JVM information for a given PID.
 */
public final class InfoCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

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

        System.out.print(RichRenderer.brandedHeader(useColor, "info", messages.get("desc.info")));
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

        // CPU section
        if (result.processCpuLoad() >= 0 || result.availableProcessors() > 0) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxSeparator(WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD) + "CPU"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

            if (result.availableProcessors() > 0) {
                System.out.println(RichRenderer.boxLine(
                        RichRenderer.padRight("  Processors", 18) + "  " + result.availableProcessors(), WIDTH));
            }
            if (result.processCpuLoad() >= 0) {
                double pct = result.processCpuLoad() * 100;
                String cpuColor = AnsiStyle.colorByThreshold(useColor, pct, 50, 80);
                System.out.println(RichRenderer.boxLine(
                        RichRenderer.padRight("  JVM CPU", 18) + "  " + cpuColor
                                + String.format("%.1f%%", pct)
                                + AnsiStyle.style(useColor, AnsiStyle.RESET)
                                + "  " + RichRenderer.progressBar(useColor, pct, 20), WIDTH));
            }
            if (result.systemCpuLoad() >= 0) {
                double pct = result.systemCpuLoad() * 100;
                System.out.println(RichRenderer.boxLine(
                        RichRenderer.padRight("  System CPU", 18) + "  "
                                + String.format("%.1f%%", pct)
                                + "  " + RichRenderer.progressBar(useColor, pct, 20), WIDTH));
            }
            if (result.systemLoadAverage() >= 0) {
                System.out.println(RichRenderer.boxLine(
                        RichRenderer.padRight("  Load Average", 18) + "  "
                                + String.format("%.2f", result.systemLoadAverage()), WIDTH));
            }
        }

        if (!result.vmFlags().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxSeparator(WIDTH));
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
        sb.append("{\"vmName\":\"").append(RichRenderer.escapeJson(result.vmName())).append('"')
          .append(",\"vmVersion\":\"").append(RichRenderer.escapeJson(result.vmVersion())).append('"')
          .append(",\"vmVendor\":\"").append(RichRenderer.escapeJson(result.vmVendor())).append('"')
          .append(",\"uptimeMs\":").append(result.uptimeMs())
          .append(",\"pid\":").append(result.pid())
          .append(",\"vmFlags\":[");
        for (int i = 0; i < result.vmFlags().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(result.vmFlags().get(i))).append('"');
        }
        sb.append("],\"systemProperties\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : result.systemProperties().entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(e.getKey())).append("\":\"")
              .append(RichRenderer.escapeJson(e.getValue())).append('"');
            first = false;
        }
        sb.append("}");
        sb.append(",\"processCpuLoad\":").append(result.processCpuLoad());
        sb.append(",\"systemCpuLoad\":").append(result.systemCpuLoad());
        sb.append(",\"availableProcessors\":").append(result.availableProcessors());
        sb.append(",\"systemLoadAverage\":").append(result.systemLoadAverage());
        sb.append('}');
        System.out.println(sb);
    }


}
