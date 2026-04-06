package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.BuffersResult;
import io.argus.cli.model.BuffersResult.BufferPool;
import io.argus.cli.provider.BuffersProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

/**
 * Displays NIO buffer pool statistics (direct, mapped) for a given PID.
 * Essential for diagnosing direct buffer leaks in production.
 */
public final class BuffersCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "buffers";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.buffers.desc");
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

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        BuffersProvider provider = registry.findBuffersProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        BuffersResult result = provider.getBuffers(pid);

        if (json) {
            printJson(result);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "buffers", messages.get("desc.buffers")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.buffers"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (result.pools().isEmpty()) {
            String msg = AnsiStyle.style(useColor, AnsiStyle.DIM)
                    + messages.get("buffers.none")
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(msg, WIDTH));
        } else {
            // Table header
            String tableHeader = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                    + RichRenderer.padRight("Pool", 25)
                    + RichRenderer.padLeft("Count", 10)
                    + RichRenderer.padLeft("Capacity", 14)
                    + RichRenderer.padLeft("Used", 14)
                    + RichRenderer.padLeft("Usage", 10)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(tableHeader, WIDTH));
            System.out.println(RichRenderer.boxSeparator(WIDTH));

            for (BufferPool pool : result.pools()) {
                double usagePct = pool.totalCapacity() > 0
                        ? (double) pool.memoryUsed() / pool.totalCapacity() * 100
                        : 0;

                String usageStr = pool.totalCapacity() > 0
                        ? String.format("%.1f%%", usagePct)
                        : "-";

                String line = RichRenderer.padRight(pool.name(), 25)
                        + RichRenderer.padLeft(RichRenderer.formatNumber(pool.count()), 10)
                        + RichRenderer.padLeft(RichRenderer.formatBytes(pool.totalCapacity()), 14)
                        + RichRenderer.padLeft(RichRenderer.formatBytes(pool.memoryUsed()), 14)
                        + RichRenderer.padLeft(usageStr, 10);
                System.out.println(RichRenderer.boxLine(line, WIDTH));
            }

            System.out.println(RichRenderer.emptyLine(WIDTH));

            // Totals
            String totalLine = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                    + RichRenderer.padRight("Total", 25)
                    + RichRenderer.padLeft(RichRenderer.formatNumber(result.totalCount()), 10)
                    + RichRenderer.padLeft(RichRenderer.formatBytes(result.totalCapacity()), 14)
                    + RichRenderer.padLeft(RichRenderer.formatBytes(result.totalUsed()), 14)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(totalLine, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(BuffersResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalCount\":").append(result.totalCount());
        sb.append(",\"totalCapacity\":").append(result.totalCapacity());
        sb.append(",\"totalUsed\":").append(result.totalUsed());
        sb.append(",\"pools\":[");
        for (int i = 0; i < result.pools().size(); i++) {
            BufferPool pool = result.pools().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(pool.name())).append('"');
            sb.append(",\"count\":").append(pool.count());
            sb.append(",\"totalCapacity\":").append(pool.totalCapacity());
            sb.append(",\"memoryUsed\":").append(pool.memoryUsed());
            sb.append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
