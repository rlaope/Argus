package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.NmtResult;
import io.argus.cli.provider.NmtProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.List;

/**
 * Shows native memory usage by category via jcmd VM.native_memory summary.
 * Requires the target JVM to be started with -XX:NativeMemoryTracking=summary.
 */
public final class NmtCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "nmt";
    }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.nmt.desc");
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

        NmtProvider provider = registry.findNmtProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        NmtResult result = provider.getNativeMemory(pid);

        if (json) {
            printJson(result);
        } else {
            System.out.print(RichRenderer.brandedHeader(useColor, "nmt", messages.get("desc.nmt")));
            printTable(result, pid, provider.source(), useColor, messages);
        }
    }

    private static void printTable(NmtResult result, long pid, String source,
                                   boolean useColor, Messages messages) {
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.nmt"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (result.totalReservedKB() == 0 && result.categories().isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                            + "NMT not enabled on this JVM."
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM)
                            + "Start the JVM with: -XX:NativeMemoryTracking=summary"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
            return;
        }

        // Total summary with progress bars
        long totalReserved = result.totalReservedKB();
        long totalCommitted = result.totalCommittedKB();

        String totalLabel = AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Total"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(totalLabel, WIDTH));

        double reservedGB = totalReserved / 1024.0 / 1024.0;
        double committedGB = totalCommitted / 1024.0 / 1024.0;
        String totalInfo = String.format("  Reserved: %s    Committed: %s",
                RichRenderer.formatKB(totalReserved), RichRenderer.formatKB(totalCommitted));
        System.out.println(RichRenderer.boxLine(totalInfo, WIDTH));

        if (totalReserved > 0) {
            double pct = (double) totalCommitted / totalReserved * 100.0;
            String bar = RichRenderer.progressBar(useColor, pct, 24);
            String barLine = "  " + bar + "  " + String.format("%.1f%%", pct) + " committed of reserved";
            System.out.println(RichRenderer.boxLine(barLine, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Column header
        String hdr = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + RichRenderer.padRight("Category", 20)
                + RichRenderer.padLeft("Reserved", 12) + "  "
                + RichRenderer.padLeft("Committed", 12) + "  "
                + "Usage"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(hdr, WIDTH));
        System.out.println(RichRenderer.boxLine("\u2500".repeat(Math.min(70, WIDTH - 4)), WIDTH));

        // Per-category rows
        List<NmtResult.NmtCategory> cats = result.categories();
        for (NmtResult.NmtCategory cat : cats) {
            double pct = cat.reservedKB() > 0
                    ? (double) cat.committedKB() / cat.reservedKB() * 100.0
                    : 0.0;

            String bar = RichRenderer.progressBar(useColor, pct, 16);
            String nameCell = AnsiStyle.style(useColor, AnsiStyle.CYAN)
                    + RichRenderer.padRight(RichRenderer.truncate(cat.name(), 18), 20)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            String line = nameCell
                    + RichRenderer.padLeft(RichRenderer.formatKB(cat.reservedKB()), 12) + "  "
                    + RichRenderer.padLeft(RichRenderer.formatKB(cat.committedKB()), 12) + "  "
                    + bar + "  " + String.format("%5.1f%%", pct);
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, cats.size() + " categories", WIDTH));
    }

    private static void printJson(NmtResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalReservedKB\":").append(result.totalReservedKB())
          .append(",\"totalCommittedKB\":").append(result.totalCommittedKB())
          .append(",\"categories\":[");
        boolean first = true;
        for (NmtResult.NmtCategory cat : result.categories()) {
            if (!first) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(cat.name())).append('"')
              .append(",\"reservedKB\":").append(cat.reservedKB())
              .append(",\"committedKB\":").append(cat.committedKB())
              .append('}');
            first = false;
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
