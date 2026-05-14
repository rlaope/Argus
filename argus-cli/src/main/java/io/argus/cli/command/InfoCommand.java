package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.json.JsonOutput;
import io.argus.cli.model.InfoResult;
import io.argus.cli.provider.InfoProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows JVM information for a given PID.
 */
public final class InfoCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "info";
    }

    @Override public CommandGroup group() { return CommandGroup.PROCESS; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.info.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        long pid = CommandUtils.parsePidOrExit(args, messages);

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

        InfoProvider provider = Providers.require(registry.find(InfoProvider.class, pid, sourceOverride), pid, messages);

        InfoResult result = provider.getVmInfo(pid);

        if (json) {
            JsonOutput.println(result);
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

}
