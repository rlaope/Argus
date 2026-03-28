package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.CompilerResult;
import io.argus.cli.provider.CompilerProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

/**
 * Shows JIT compiler statistics and code cache usage.
 */
public final class CompilerCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int BAR_WIDTH = 20;

    @Override
    public String name() { return "compiler"; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.compiler.desc");
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
        CompilerProvider provider = registry.findCompilerProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        CompilerResult result = provider.getCompilerInfo(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "compiler", messages.get("desc.compiler")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.compiler"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Compilation status
        String status = result.compilationEnabled()
                ? AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u2714 " + messages.get("compiler.enabled") + AnsiStyle.style(useColor, AnsiStyle.RESET)
                : AnsiStyle.style(useColor, AnsiStyle.RED) + "\u2718 " + messages.get("compiler.disabled") + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(status, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Code cache bar
        double usedPct = result.codeCacheSizeKb() > 0
                ? (result.codeCacheUsedKb() * 100.0) / result.codeCacheSizeKb() : 0;
        String bar = RichRenderer.progressBar(useColor, usedPct, BAR_WIDTH);
        String cacheLine = messages.get("compiler.codecache") + "  " + bar + "  "
                + RichRenderer.formatKB(result.codeCacheUsedKb()) + " / " + RichRenderer.formatKB(result.codeCacheSizeKb())
                + "  (" + String.format("%.0f%%", usedPct) + ")";
        System.out.println(RichRenderer.boxLine(cacheLine, WIDTH));

        String maxLine = "  " + messages.get("compiler.maxused") + ": " + RichRenderer.formatKB(result.codeCacheMaxUsedKb())
                + "    " + messages.get("compiler.free") + ": " + RichRenderer.formatKB(result.codeCacheFreeKb());
        System.out.println(RichRenderer.boxLine(maxLine, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Code blobs
        String blobLine = messages.get("compiler.blobs") + ": " + RichRenderer.formatNumber(result.totalBlobs())
                + "    nmethods: " + RichRenderer.formatNumber(result.nmethods())
                + "    adapters: " + RichRenderer.formatNumber(result.adapters());
        System.out.println(RichRenderer.boxLine(blobLine, WIDTH));

        // Queue
        if (result.queueSize() > 0) {
            String queueLine = messages.get("compiler.queue") + ": "
                    + AnsiStyle.style(useColor, AnsiStyle.YELLOW) + result.queueSize()
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(queueLine, WIDTH));
        }

        // Warning if code cache usage > 80%
        if (usedPct > 80) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            String warn = AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0 "
                    + messages.get("compiler.warn.cache")
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(warn, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(CompilerResult result) {
        System.out.println("{\"codeCacheSizeKb\":" + result.codeCacheSizeKb()
                + ",\"codeCacheUsedKb\":" + result.codeCacheUsedKb()
                + ",\"codeCacheMaxUsedKb\":" + result.codeCacheMaxUsedKb()
                + ",\"codeCacheFreeKb\":" + result.codeCacheFreeKb()
                + ",\"totalBlobs\":" + result.totalBlobs()
                + ",\"nmethods\":" + result.nmethods()
                + ",\"adapters\":" + result.adapters()
                + ",\"compilationEnabled\":" + result.compilationEnabled()
                + ",\"queueSize\":" + result.queueSize() + "}");
    }
}
