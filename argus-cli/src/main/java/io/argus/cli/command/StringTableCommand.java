package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.StringTableResult;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.StringTableProvider;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows interned string table statistics.
 */
public final class StringTableCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "stringtable"; }

    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.stringtable.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) { System.err.println(messages.get("error.pid.required")); return; }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--source=")) sourceOverride = args[i].substring(9);
            else if (args[i].equals("--format=json")) json = true;
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        StringTableProvider provider = registry.findStringTableProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        StringTableResult result = provider.getStringTableInfo(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "stringtable", messages.get("desc.stringtable")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.stringtable"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Table layout
        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);

        String headerLine = bold + RichRenderer.padRight(messages.get("stringtable.category"), 20)
                + RichRenderer.padLeft(messages.get("label.count"), 12)
                + RichRenderer.padLeft(messages.get("label.size"), 12) + reset;
        System.out.println(RichRenderer.boxLine(headerLine, WIDTH));

        String sep = dim + "\u2500".repeat(44) + reset;
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        printRow(useColor, messages.get("stringtable.buckets"), result.bucketCount(), result.bucketBytes());
        printRow(useColor, messages.get("stringtable.entries"), result.entryCount(), result.entryBytes());
        printRow(useColor, messages.get("stringtable.literals"), result.literalCount(), result.literalBytes());

        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        String totalLine = bold + RichRenderer.padRight(messages.get("label.total"), 20)
                + RichRenderer.padLeft("", 12)
                + RichRenderer.padLeft(RichRenderer.formatBytes(result.totalBytes()), 12) + reset;
        System.out.println(RichRenderer.boxLine(totalLine, WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Average literal size
        String avgLine = messages.get("stringtable.avg") + ": " + String.format("%.1f bytes", result.avgLiteralSize());
        System.out.println(RichRenderer.boxLine(avgLine, WIDTH));

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printRow(boolean useColor, String label, long count, long bytes) {
        String line = RichRenderer.padRight(label, 20)
                + RichRenderer.padLeft(RichRenderer.formatNumber(count), 12)
                + RichRenderer.padLeft(RichRenderer.formatBytes(bytes), 12);
        System.out.println(RichRenderer.boxLine(line, WIDTH));
    }

    private static void printJson(StringTableResult result) {
        System.out.println("{\"bucketCount\":" + result.bucketCount()
                + ",\"entryCount\":" + result.entryCount()
                + ",\"literalCount\":" + result.literalCount()
                + ",\"bucketBytes\":" + result.bucketBytes()
                + ",\"entryBytes\":" + result.entryBytes()
                + ",\"literalBytes\":" + result.literalBytes()
                + ",\"totalBytes\":" + result.totalBytes()
                + ",\"avgLiteralSize\":" + result.avgLiteralSize() + "}");
    }
}
