package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.ProcessInfo;
import io.argus.cli.provider.ProcessProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * Lists running JVM processes.
 */
public final class PsCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "ps";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.ps.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        boolean useColor = config.color();
        boolean json = "json".equals(config.format()) || hasFlag(args, "--format=json");

        ProcessProvider provider = registry.findProcessProvider();
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", "ps"));
            return;
        }

        List<ProcessInfo> processes = provider.listProcesses();

        if (json) {
            printJson(processes);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "ps", messages.get("desc.ps")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.ps"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        int innerWidth = WIDTH - 4;
        int classWidth = (innerWidth - 12) * 2 / 3;
        int argsWidth = innerWidth - 12 - classWidth;
        // Header row
        String header = RichRenderer.padRight("PID", 8) + "  "
                + RichRenderer.padRight("Main Class", classWidth) + "  "
                + "Arguments";
        System.out.println(RichRenderer.boxLine(header, WIDTH));

        String sep = "\u2500".repeat(8) + "  "
                + "\u2500".repeat(classWidth) + "  "
                + "\u2500".repeat(argsWidth);
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        for (ProcessInfo p : processes) {
            String pidStr = RichRenderer.padRight(String.valueOf(p.pid()), 8);
            String cls = RichRenderer.padRight(RichRenderer.truncate(p.mainClass(), classWidth), classWidth);
            String args2 = RichRenderer.truncate(p.arguments(), argsWidth);
            System.out.println(RichRenderer.boxLine(pidStr + "  " + cls + "  " + args2, WIDTH));

            // Show Java version and uptime if available
            if (!p.javaVersion().isEmpty() || p.uptimeMs() > 0) {
                StringBuilder detail = new StringBuilder("          ");
                if (!p.javaVersion().isEmpty()) {
                    detail.append(RichRenderer.truncate(p.javaVersion(), 40));
                }
                if (p.uptimeMs() > 0) {
                    if (!p.javaVersion().isEmpty()) detail.append("  ");
                    detail.append("uptime: ").append(RichRenderer.formatDuration(p.uptimeMs()));
                }
                System.out.println(RichRenderer.boxLine(detail.toString(), WIDTH));
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(processes.size() + " process(es) found", WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(List<ProcessInfo> processes) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < processes.size(); i++) {
            ProcessInfo p = processes.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"pid\":").append(p.pid())
              .append(",\"mainClass\":\"").append(RichRenderer.escapeJson(p.mainClass())).append('"')
              .append(",\"arguments\":\"").append(RichRenderer.escapeJson(p.arguments())).append('"')
              .append('}');
        }
        sb.append(']');
        System.out.println(sb);
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }
}
