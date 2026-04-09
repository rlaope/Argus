package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.ThreadDumpResult;
import io.argus.cli.model.ThreadDumpResult.ThreadInfo;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.ThreadDumpProvider;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.Map;
import java.util.TreeMap;

/**
 * Captures and displays a full thread dump for a given PID.
 * Equivalent to jstack but with structured output, state grouping, and lock analysis.
 */
public final class ThreadDumpCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int MAX_STACK_DEPTH = 20;

    @Override
    public String name() {
        return "threaddump";
    }

    @Override public CommandGroup group() { return CommandGroup.THREADS; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.threaddump.desc");
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
        boolean raw = false;
        int maxDepth = MAX_STACK_DEPTH;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.equals("--raw")) {
                raw = true;
            } else if (arg.startsWith("--depth=")) {
                try { maxDepth = Integer.parseInt(arg.substring(8)); } catch (NumberFormatException ignored) {}
            }
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        ThreadDumpProvider provider = registry.findThreadDumpProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        ThreadDumpResult result = provider.dumpThreads(pid);

        if (raw) {
            System.out.println(result.rawOutput());
            return;
        }

        if (json) {
            printJson(result);
            return;
        }

        printRich(result, pid, source, useColor, messages, maxDepth);
    }

    private void printRich(ThreadDumpResult result, long pid, String source,
                           boolean useColor, Messages messages, int maxDepth) {
        System.out.print(RichRenderer.brandedHeader(useColor, "threaddump",
                messages.get("desc.threaddump")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.threaddump"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // State summary
        Map<String, Integer> stateCounts = new TreeMap<>();
        int daemonCount = 0;
        for (ThreadInfo t : result.threads()) {
            stateCounts.merge(t.state(), 1, Integer::sum);
            if (t.daemon()) daemonCount++;
        }

        String totalLine = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + messages.get("threaddump.total", result.totalThreads())
                + AnsiStyle.style(useColor, AnsiStyle.RESET)
                + "  (daemon: " + daemonCount + ")";
        System.out.println(RichRenderer.boxLine(totalLine, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // State distribution
        for (Map.Entry<String, Integer> entry : stateCounts.entrySet()) {
            String stateColor = stateColor(useColor, entry.getKey());
            String stateLine = "  " + stateColor
                    + RichRenderer.padRight(entry.getKey(), 16)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET)
                    + RichRenderer.padLeft(String.valueOf(entry.getValue()), 5);
            System.out.println(RichRenderer.boxLine(stateLine, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));

        // Thread details
        for (ThreadInfo t : result.threads()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));

            String stateColor = stateColor(useColor, t.state());
            String header = "  " + AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.CYAN)
                    + RichRenderer.truncate(t.name(), 45)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET)
                    + "  " + stateColor + "[" + t.state() + "]"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            if (t.daemon()) {
                header += " " + AnsiStyle.style(useColor, AnsiStyle.DIM) + "daemon"
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
            }
            System.out.println(RichRenderer.boxLine(header, WIDTH));

            String meta = "    tid=" + String.format("0x%x", t.tid())
                    + "  nid=" + String.format("0x%x", t.nid())
                    + "  prio=" + t.priority();
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM) + meta
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

            if (!t.waitingOn().isEmpty()) {
                String waitLine = "    " + AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                        + "waiting on <" + t.waitingOn() + ">"
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(waitLine, WIDTH));
            }

            for (String lock : t.locksHeld()) {
                String lockLine = "    " + AnsiStyle.style(useColor, AnsiStyle.GREEN)
                        + "locked <" + lock + ">"
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(lockLine, WIDTH));
            }

            // Stack trace (limited depth)
            int depth = Math.min(t.stackTrace().size(), maxDepth);
            for (int i = 0; i < depth; i++) {
                String frame = "      at " + AnsiStyle.style(useColor, AnsiStyle.DIM)
                        + RichRenderer.truncate(t.stackTrace().get(i), WIDTH - 16)
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(frame, WIDTH));
            }
            if (t.stackTrace().size() > maxDepth) {
                String more = "      ... " + (t.stackTrace().size() - maxDepth) + " more";
                System.out.println(RichRenderer.boxLine(
                        AnsiStyle.style(useColor, AnsiStyle.DIM) + more
                                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor,
                messages.get("threaddump.total", result.totalThreads()), WIDTH));
    }

    private static String stateColor(boolean useColor, String state) {
        return switch (state) {
            case "RUNNABLE" -> AnsiStyle.style(useColor, AnsiStyle.GREEN);
            case "BLOCKED" -> AnsiStyle.style(useColor, AnsiStyle.RED);
            case "WAITING", "TIMED_WAITING" -> AnsiStyle.style(useColor, AnsiStyle.YELLOW);
            default -> AnsiStyle.style(useColor, AnsiStyle.DIM);
        };
    }

    private static void printJson(ThreadDumpResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalThreads\":").append(result.totalThreads());
        sb.append(",\"threads\":[");
        for (int i = 0; i < result.threads().size(); i++) {
            ThreadInfo t = result.threads().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(t.name())).append('"');
            sb.append(",\"tid\":").append(t.tid());
            sb.append(",\"nid\":").append(t.nid());
            sb.append(",\"state\":\"").append(t.state()).append('"');
            sb.append(",\"daemon\":").append(t.daemon());
            sb.append(",\"priority\":").append(t.priority());
            if (!t.waitingOn().isEmpty()) {
                sb.append(",\"waitingOn\":\"").append(RichRenderer.escapeJson(t.waitingOn())).append('"');
            }
            sb.append(",\"locksHeld\":[");
            for (int j = 0; j < t.locksHeld().size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(t.locksHeld().get(j))).append('"');
            }
            sb.append("]");
            sb.append(",\"stackTrace\":[");
            for (int j = 0; j < t.stackTrace().size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(t.stackTrace().get(j))).append('"');
            }
            sb.append("]}");
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
