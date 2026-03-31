package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.SymbolTableResult;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.SymbolTableProvider;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

/**
 * Shows symbol table statistics.
 */
public final class SymbolTableCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "symboltable"; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.symboltable.desc");
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
        SymbolTableProvider provider = registry.findSymbolTableProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        SymbolTableResult result = provider.getSymbolTableInfo(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "symboltable", messages.get("desc.symboltable")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.symboltable"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);

        String headerLine = bold + RichRenderer.padRight(messages.get("stringtable.category"), 20)
                + RichRenderer.padLeft(messages.get("label.count"), 12)
                + RichRenderer.padLeft(messages.get("label.size"), 12) + reset;
        System.out.println(RichRenderer.boxLine(headerLine, WIDTH));

        String sep = dim + "\u2500".repeat(44) + reset;
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        printRow(messages.get("stringtable.buckets"), result.bucketCount(), result.bucketBytes());
        printRow(messages.get("stringtable.entries"), result.entryCount(), result.entryBytes());
        printRow(messages.get("stringtable.literals"), result.literalCount(), result.literalBytes());

        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        String totalLine = bold + RichRenderer.padRight(messages.get("label.total"), 20)
                + RichRenderer.padLeft("", 12)
                + RichRenderer.padLeft(RichRenderer.formatBytes(result.totalBytes()), 12) + reset;
        System.out.println(RichRenderer.boxLine(totalLine, WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        String avgLine = messages.get("stringtable.avg") + ": " + String.format("%.1f bytes", result.avgLiteralSize());
        System.out.println(RichRenderer.boxLine(avgLine, WIDTH));

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printRow(String label, long count, long bytes) {
        String line = RichRenderer.padRight(label, 20)
                + RichRenderer.padLeft(RichRenderer.formatNumber(count), 12)
                + RichRenderer.padLeft(RichRenderer.formatBytes(bytes), 12);
        System.out.println(RichRenderer.boxLine(line, WIDTH));
    }

    private static void printJson(SymbolTableResult r) {
        System.out.println("{\"bucketCount\":" + r.bucketCount()
                + ",\"entryCount\":" + r.entryCount()
                + ",\"literalCount\":" + r.literalCount()
                + ",\"bucketBytes\":" + r.bucketBytes()
                + ",\"entryBytes\":" + r.entryBytes()
                + ",\"literalBytes\":" + r.literalBytes()
                + ",\"totalBytes\":" + r.totalBytes()
                + ",\"avgLiteralSize\":" + r.avgLiteralSize() + "}");
    }
}
