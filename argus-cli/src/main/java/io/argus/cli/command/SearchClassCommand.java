package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.SearchClassResult;
import io.argus.cli.model.SearchClassResult.ClassInfo;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.SearchClassProvider;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Search loaded classes by pattern. Useful for diagnosing classpath conflicts
 * and finding duplicate class loading.
 *
 * Usage:
 *   argus sc <pid> <pattern>
 *   argus sc 12345 "*.UserService"
 *   argus sc 12345 "org.slf4j.*"
 */
public final class SearchClassCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_LIMIT = 50;

    @Override
    public String name() {
        return "sc";
    }

    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.sc.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length < 2) {
            System.err.println("Usage: argus sc <pid> <pattern>");
            System.err.println("  Example: argus sc 12345 \"*.UserService\"");
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[0]));
            return;
        }

        String pattern = args[1];
        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        int limit = DEFAULT_LIMIT;

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--limit=")) {
                try { limit = Integer.parseInt(arg.substring(8)); } catch (NumberFormatException ignored) {}
            }
        }

        SearchClassProvider provider = registry.findSearchClassProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        SearchClassResult result = provider.searchClasses(pid, pattern);

        if (json) {
            printJson(result, limit);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "sc", messages.get("desc.sc")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.sc"),
                WIDTH, "pid:" + pid, "pattern:" + pattern));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (result.classes().isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM)
                            + messages.get("sc.none", pattern)
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        } else {
            // Table header
            String header = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                    + RichRenderer.padLeft("#", 5)
                    + RichRenderer.padLeft("Instances", 12)
                    + RichRenderer.padLeft("Bytes", 14)
                    + "  Class"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(header, WIDTH));
            System.out.println(RichRenderer.boxSeparator(WIDTH));

            int shown = Math.min(result.classes().size(), limit);
            for (int i = 0; i < shown; i++) {
                ClassInfo cls = result.classes().get(i);
                String line = RichRenderer.padLeft(String.valueOf(i + 1), 5)
                        + RichRenderer.padLeft(RichRenderer.formatNumber(cls.instanceCount()), 12)
                        + RichRenderer.padLeft(RichRenderer.formatBytes(cls.totalBytes()), 14)
                        + "  " + AnsiStyle.style(useColor, AnsiStyle.CYAN)
                        + RichRenderer.truncate(cls.name(), WIDTH - 38)
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(line, WIDTH));
            }

            if (result.totalMatches() > limit) {
                System.out.println(RichRenderer.emptyLine(WIDTH));
                System.out.println(RichRenderer.boxLine(
                        AnsiStyle.style(useColor, AnsiStyle.DIM)
                                + "  ... " + (result.totalMatches() - limit) + " more (use --limit=N)"
                                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor,
                result.totalMatches() + " match(es)", WIDTH));
    }

    private static void printJson(SearchClassResult result, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"pattern\":\"").append(RichRenderer.escapeJson(result.pattern())).append('"');
        sb.append(",\"totalMatches\":").append(result.totalMatches());
        sb.append(",\"classes\":[");
        int shown = Math.min(result.classes().size(), limit);
        for (int i = 0; i < shown; i++) {
            ClassInfo cls = result.classes().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(cls.name())).append('"');
            sb.append(",\"instances\":").append(cls.instanceCount());
            sb.append(",\"bytes\":").append(cls.totalBytes());
            sb.append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
