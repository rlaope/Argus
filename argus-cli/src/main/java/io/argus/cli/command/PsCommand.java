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

    private static final int WIDTH = 60;

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

        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.ps"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Header row
        String header = RichRenderer.padRight("PID", 8) + "  "
                + RichRenderer.padRight("Main Class", 28) + "  "
                + "Arguments";
        System.out.println(RichRenderer.boxLine(header, WIDTH));

        String sep = "\u2500".repeat(8) + "  "
                + "\u2500".repeat(28) + "  "
                + "\u2500".repeat(14);
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        for (ProcessInfo p : processes) {
            String pidStr = RichRenderer.padRight(String.valueOf(p.pid()), 8);
            String cls = RichRenderer.padRight(truncate(p.mainClass(), 28), 28);
            String args2 = truncate(p.arguments(), 14);
            System.out.println(RichRenderer.boxLine(pidStr + "  " + cls + "  " + args2, WIDTH));
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
              .append(",\"mainClass\":\"").append(escape(p.mainClass())).append('"')
              .append(",\"arguments\":\"").append(escape(p.arguments())).append('"')
              .append('}');
        }
        sb.append(']');
        System.out.println(sb);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }
}
